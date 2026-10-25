package com.zamotkin.extrahealthgarmin.ui.screens.measurements

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.data.GarminRepository
import com.zamotkin.extrahealthgarmin.data.UploadOutcome
import com.zamotkin.extrahealthgarmin.devices.SourceReadOutcome
import com.zamotkin.extrahealthgarmin.devices.SourceReadUseCase
import com.zamotkin.extrahealthgarmin.devices.SourceRegistry
import com.zamotkin.extrahealthgarmin.devices.api.BpReading
import com.zamotkin.extrahealthgarmin.devices.api.BpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.SourceCounters
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSourceStore
import com.zamotkin.extrahealthgarmin.health.HealthSyncWiring
import com.zamotkin.extrahealthgarmin.omron.data.BloodPressureDatabase
import com.zamotkin.extrahealthgarmin.omron.data.BpSyncCoordinator
import com.zamotkin.extrahealthgarmin.omron.data.OmronBpRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One source card of the Measurements page (task
 * tefal2garmin-app-redesign-v3-xlg.6): the stored [source] row plus this
 * source's current read-now [status]. One emission per state change, so the
 * cards never mix a stale status with a fresh row.
 */
data class SourceCardState(
    val source: ConnectedSource,
    val status: ReadNowState,
)

/**
 * Read-now status of one source (task xlg.6) - the successor of the old
 * Omron dashboard's UI state, generalized over every [SourceReadResult]
 * variant the source seam reports. Same convention as the other UI state machines:
 * the state carries NO user-visible text, only markers - the screen resolves
 * every message from an R.string.
 */
sealed interface ReadNowState {

    /** No pass yet, or none worth remembering - the resting state. */
    data object Idle : ReadNowState

    /** A read pass of this source is in flight. */
    data object Reading : ReadNowState

    /**
     * Terminal outcome of one read-now pass. [messageRes] is the localized
     * primary message; [count] is its numeric argument (readings count)
     * when the message takes one; [counters] is the internal-pipeline
     * summary of sources that consumed their readings themselves (Health
     * Connect); [detail] is an optional non-localized diagnostic (the raw
     * reason/step of the failing layer) shown beneath the message;
     * [isError] picks the error color for failures and attention states
     * (the screen never matches resource ids).
     */
    data class Finished(
        @StringRes val messageRes: Int,
        val count: Int? = null,
        val counters: SourceCounters? = null,
        val detail: String? = null,
        val isError: Boolean = false,
    ) : ReadNowState
}

/**
 * The day-grouped readings list (task xlg.6): already windowed entries (day
 * headers interleaved with reading rows, newest first) plus how many
 * readings sit below the window - the "show more" affordance renders only
 * while this is positive.
 */
data class MeasurementsListState(
    val entries: List<MeasurementsEntry>,
    val hiddenReadingsCount: Int,
)

/** One element of the day-grouped list: a day header or a reading row. */
sealed interface MeasurementsEntry {

    /** Header of one local day ([epochDay] - the screen formats it). */
    data class DayHeader(val epochDay: Long) : MeasurementsEntry

    /** One reading, tagged with the connected source it came through. */
    data class Reading(
        val reading: OmronBpRepository.StoredBpReading,
        val source: ConnectedSource,
    ) : MeasurementsEntry
}

/**
 * ViewModel of the Measurements page (task
 * tefal2garmin-app-redesign-v3-xlg.6, epic R6/R8) - the single BP page that
 * absorbs the old Omron dashboard:
 *
 *  - the gating state: the Measurements tab exists only while at least one
 *    source is connected ([tabVisible]);
 *  - one card per connected source ([sourceCards]): last-read summary, the
 *    per-source autosync toggle ([setAutoSync]) and the read-now action
 *    ([readNow]) wired through [SourceReadUseCase];
 *  - the day-grouped readings list ([records]): the unified cross-source
 *    query ([OmronBpRepository.allForSources]) filtered by each source's
 *    picked user (the epic-R4 display filter), newest first, capped at an
 *    initial window with "show more" ([showMore]) so large histories never
 *    load unbounded.
 *
 * Autosync is a POST-READ flag, not a scheduler: after a successful read
 * pass of a source whose stored row has [ConnectedSource.autoSync] set, the
 * same foreground pass runs one [BpSyncCoordinator.syncOnce] upload for
 * that source's slot - the generalization of the old Omron dashboard's
 * auto-upload switch. There is no background BP work.
 *
 * Every environment dependency is an injected seam (manual wiring, no DI
 * framework - same style as ui.screens.devices.SourcesViewModel), so the
 * whole machine is unit-testable with fakes: the store, the registry, the
 * real never-throws repository over a fake DAO, the read pass and the
 * upload pass as lambdas. Never throws to the UI: every failure lands on a
 * [ReadNowState.Finished] status; cancellation propagates without a stuck
 * progress line.
 *
 * Open solely so the unit test can subclass it and simulate [onCleared].
 */
open class MeasurementsViewModel(
    private val store: ConnectedSourceStore,
    private val registry: SourceRegistry,
    private val repository: OmronBpRepository,
    private val readSource: suspend (ConnectedSource) -> SourceReadOutcome,
    private val upload: suspend (userSlot: Int) -> UploadOutcome,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val _sourceCards = MutableLiveData<List<SourceCardState>>(emptyList())
    /** One card state per connected source, in store order. */
    val sourceCards: LiveData<List<SourceCardState>> = _sourceCards

    private val _tabVisible = MutableLiveData(false)
    /**
     * Epic R8 gate: the Measurements tab (and any BP UI) exists only while
     * at least one source is connected.
     */
    val tabVisible: LiveData<Boolean> = _tabVisible

    private val _records = MutableLiveData(MeasurementsListState(emptyList(), 0))
    /** The windowed, day-grouped readings of every connected source. */
    val records: LiveData<MeasurementsListState> = _records

    /** Read-now status per source id; absent = [ReadNowState.Idle]. */
    private val readStates = mutableMapOf<String, ReadNowState>()

    /** The in-flight read pass per source id; one pass per source at a time. */
    private val readJobs = mutableMapOf<String, Job>()

    /** Last loaded rows/sources - the rebuild base of [showMore] (no DB hit). */
    private var sourcesSnapshot: List<ConnectedSource> = emptyList()
    private var readingsSnapshot: List<OmronBpRepository.StoredBpReading> = emptyList()

    /** How many readings the list currently shows (the "show more" window). */
    private var visibleReadings = INITIAL_WINDOW_READINGS

    init {
        refresh()
    }

    /**
     * Re-reads the store and the readings - first entry, every return to
     * the tab (the scaffold refreshes on every navigation change) and
     * after every read pass. Cheap by design: one small JSON file and one
     * Room query.
     */
    fun refresh() {
        viewModelScope.launch {
            sourcesSnapshot = store.load()
            readingsSnapshot = repository.allForSources(sourcesSnapshot.map { it.id })
            _tabVisible.value = sourcesSnapshot.isNotEmpty()
            rebuild()
        }
    }

    /** The registry type behind a stored source, or null for an unknown id. */
    fun typeOf(sourceTypeId: String): BpSourceType? = registry.type(sourceTypeId)

    // ------------------------------------------------------------------ //
    //  Read now                                                           //
    // ------------------------------------------------------------------ //

    /**
     * Runs one read pass of the source ([SourceReadUseCase.read] in the
     * wiring) and surfaces its outcome as this source's status - success
     * with the stored-reading count, every [SourceReadResult] failure
     * variant, and the post-read autosync upload result. A second tap
     * while a pass runs is ignored (one pass per source). Never throws to
     * the UI: a broken seam lands on an unexpected-error status; a
     * cancelled pass resets to [ReadNowState.Idle] (no stuck progress
     * line) and lets the cancellation propagate.
     */
    fun readNow(sourceId: String) {
        if (readJobs[sourceId]?.isActive == true) return
        readJobs[sourceId] = viewModelScope.launch {
            setStatus(sourceId, ReadNowState.Reading)
            val pass = coroutineContext[Job]
            try {
                // The row as of now - a pass must not read a snapshot the
                // user changed since the screen loaded (mode, slot).
                val source = store.load().firstOrNull { it.id == sourceId }
                if (source == null) {
                    // Removed while the tap was in flight: nothing to read.
                    setStatus(sourceId, ReadNowState.Idle)
                } else {
                    applyReadOutcome(sourceId, readSource(source))
                }
            } catch (e: CancellationException) {
                setStatus(sourceId, ReadNowState.Idle)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Read pass of source '$sourceId' failed unexpectedly", e)
                setStatus(
                    sourceId,
                    ReadNowState.Finished(
                        messageRes = R.string.measurement_read_failed_unexpected,
                        detail = e.message ?: e.javaClass.simpleName,
                        isError = true
                    )
                )
            } finally {
                // Untrack only THIS pass: a newer one that already replaced
                // a cancelled pass must not lose its slot.
                if (readJobs[sourceId] === pass) readJobs.remove(sourceId)
            }
            // New readings/anchors only show after a completed pass.
            refresh()
        }
    }

    /**
     * Maps one read outcome onto the source's status. A successful pass
     * that stored nothing new still succeeds; a pass whose store step
     * failed (DAO failure - nothing was written) says so instead of
     * pretending; and when the source's CURRENT stored row has autosync
     * on, the same pass runs one Garmin upload of that source's slot and
     * an upload problem outranks the read success (the readings are
     * already saved and stay retriable - the status says what happened).
     */
    private suspend fun applyReadOutcome(sourceId: String, outcome: SourceReadOutcome) {
        val result = outcome.result
        if (result !is SourceReadResult.Success) {
            setStatus(
                sourceId,
                ReadNowState.Finished(
                    messageRes = readFailureRes(result),
                    detail = readFailureDetail(result),
                    isError = true
                )
            )
            return
        }
        val stored = outcome.stored
        if (stored != null && !stored.success) {
            setStatus(
                sourceId,
                ReadNowState.Finished(R.string.measurement_read_store_failed, isError = true)
            )
            return
        }
        var status = when {
            // A source whose pipeline consumed the readings itself (Health
            // Connect uploads to Garmin inside the pass) tells its story
            // through the counters, not through stored rows.
            result.readings.isEmpty() && result.counters.hasActivity() ->
                ReadNowState.Finished(
                    messageRes = R.string.measurement_read_pipeline_summary,
                    counters = result.counters
                )

            (stored?.insertedCount ?: 0) > 0 -> ReadNowState.Finished(
                messageRes = R.string.measurement_read_success_new,
                count = stored?.insertedCount ?: 0
            )

            else -> ReadNowState.Finished(R.string.measurement_read_success_none)
        }
        // Autosync: the flag comes from the stored row as of now, not the
        // row the pass started from - a toggle mid-pass is honored.
        val current = store.load().firstOrNull { it.id == sourceId }
        if (current?.autoSync == true) {
            val slot = current.userSlot ?: BpReading.APP_OWNER_SLOT
            status = uploadOutcomeStatus(status, upload(slot))
        }
        setStatus(sourceId, status)
    }

    /**
     * The read-failure headline per [SourceReadResult] variant - the
     * standard-BLE wordings task xlg.9 wrote for exactly this mapping; the
     * raw reason rides along as the detail line. [SourceReadResult.Success]
     * never reaches here (the caller handles it); it maps to the generic
     * connect failure only to keep the when exhaustive.
     */
    private fun readFailureRes(result: SourceReadResult): Int = when (result) {
        is SourceReadResult.ConnectionRequired -> R.string.ble1810_error_bonding
        is SourceReadResult.Timeout -> R.string.ble1810_error_timeout
        is SourceReadResult.Disconnected -> R.string.ble1810_error_disconnected
        is SourceReadResult.Failed,
        is SourceReadResult.Success -> R.string.ble1810_error_connect
    }

    /** The raw reason/step of the failing layer, shown under the headline. */
    private fun readFailureDetail(result: SourceReadResult): String? = when (result) {
        is SourceReadResult.ConnectionRequired -> result.reason
        is SourceReadResult.Timeout -> result.reason
        is SourceReadResult.Disconnected -> result.reason
        is SourceReadResult.Failed -> result.reason
        is SourceReadResult.Success -> null
    }

    /**
     * Post-read upload result onto the status: a confirmed (or empty)
     * upload keeps the read's success story; a problem replaces it - the
     * readings are already saved and stay retriable, only the status says
     * what happened (the old dashboard mapped its upload outcomes the same
     * way). Weigh-in-only variants ([UploadOutcome.Success],
     * [UploadOutcome.Partial]) are unreachable through the BP coordinator
     * wiring; mapped by closest semantics, defensively.
     */
    private fun uploadOutcomeStatus(
        read: ReadNowState.Finished,
        outcome: UploadOutcome,
    ): ReadNowState.Finished = when (outcome) {
        UploadOutcome.NotNeeded,
        is UploadOutcome.Success -> read

        is UploadOutcome.BpUploaded ->
            if (outcome.failureMessages.isEmpty()) {
                read
            } else {
                ReadNowState.Finished(
                    messageRes = R.string.measurement_upload_partial,
                    detail = outcome.failureMessages.first(),
                    isError = true
                )
            }

        is UploadOutcome.Partial -> ReadNowState.Finished(
            messageRes = R.string.measurement_upload_partial,
            detail = outcome.failureMessages.firstOrNull(),
            isError = true
        )

        is UploadOutcome.Retryable -> ReadNowState.Finished(
            messageRes = R.string.measurement_upload_retryable,
            detail = outcome.reason,
            isError = true
        )

        UploadOutcome.LoginRequired ->
            ReadNowState.Finished(R.string.login_required, isError = true)
    }

    // ------------------------------------------------------------------ //
    //  Autosync toggle                                                    //
    // ------------------------------------------------------------------ //

    /**
     * Persists the per-source autosync flag through the store's row-atomic
     * [ConnectedSourceStore.update] (a concurrent anchor write of a
     * background pass cannot be lost). True when the store holds the new
     * value; false when the id is unknown or the write failed - the old
     * flag stays.
     */
    fun setAutoSync(sourceId: String, enabled: Boolean): Boolean {
        val saved = store.update(sourceId) { it.copy(autoSync = enabled) }
        if (saved) refresh()
        return saved
    }

    // ------------------------------------------------------------------ //
    //  List window                                                        //
    // ------------------------------------------------------------------ //

    /** Widens the visible window by one step; no database round-trip. */
    fun showMore() {
        visibleReadings += WINDOW_STEP_READINGS
        rebuild()
    }

    // ------------------------------------------------------------------ //
    //  State plumbing                                                     //
    // ------------------------------------------------------------------ //

    /** Rebuilds [records] and [sourceCards] from the last loaded snapshots. */
    private fun rebuild() {
        _records.value = buildMeasurementsState(
            readings = readingsSnapshot,
            sourcesById = sourcesSnapshot.associateBy { it.id },
            zone = zoneId,
            limit = visibleReadings
        )
        emitCards()
    }

    private fun emitCards() {
        _sourceCards.value = sourcesSnapshot.map { source ->
            SourceCardState(source, readStates[source.id] ?: ReadNowState.Idle)
        }
    }

    private fun setStatus(sourceId: String, status: ReadNowState) {
        readStates[sourceId] = status
        emitCards()
    }

    override fun onCleared() {
        // Cancels the in-flight read passes; cancellation propagates into
        // the source reads (the drivers close their GATT connections in
        // their own finally blocks), and every pass resets to Idle first.
        readJobs.values.forEach { it.cancel() }
        super.onCleared()
    }

    private companion object {
        const val TAG = "MeasurementsViewModel"

        /** Readings the list shows before the first "show more". */
        const val INITIAL_WINDOW_READINGS = 50

        /** Readings each "show more" adds. */
        const val WINDOW_STEP_READINGS = 50
    }
}

/**
 * The day-grouped list builder (task xlg.6) - pure, so the R4 display
 * filter, the newest-first order, the day grouping and the window are
 * unit-tested directly:
 *
 *  - a row is visible only when its source is connected AND (the epic-R4
 *    display filter) it carries that source's picked user slot - rows of
 *    other slots and rows of removed sources stay invisible;
 *  - rows sort newest first (source id as the deterministic tie-break);
 *  - the first row of every local day gets a [MeasurementsEntry.DayHeader]
 *    ([zone] decides the day boundary);
 *  - the window caps how many READINGS (not entries) show; the rest count
 *    into [MeasurementsListState.hiddenReadingsCount].
 */
internal fun buildMeasurementsState(
    readings: List<OmronBpRepository.StoredBpReading>,
    sourcesById: Map<String, ConnectedSource>,
    zone: ZoneId,
    limit: Int,
): MeasurementsListState {
    val visible = readings
        .filter { row ->
            val source = sourcesById[row.sourceId]
            source != null && (source.userSlot == null || row.userSlot == source.userSlot)
        }
        .sortedWith(
            compareByDescending<OmronBpRepository.StoredBpReading> { it.timestampEpochSec }
                .thenBy { it.sourceId }
        )
    val windowed = visible.take(limit)
    val entries = mutableListOf<MeasurementsEntry>()
    var currentDay: Long? = null
    for (row in windowed) {
        // Instant.atZone + toLocalDate: the API-26-safe day split
        // (LocalDate.ofInstant needs API 34).
        val day = Instant
            .ofEpochSecond(row.timestampEpochSec)
            .atZone(zone)
            .toLocalDate()
            .toEpochDay()
        if (day != currentDay) {
            entries += MeasurementsEntry.DayHeader(day)
            currentDay = day
        }
        entries += MeasurementsEntry.Reading(row, sourcesById.getValue(row.sourceId))
    }
    return MeasurementsListState(entries, hiddenReadingsCount = visible.size - windowed.size)
}

/** Whether a counters triple carries any story (all-zero = nothing to say). */
private fun SourceCounters.hasActivity(): Boolean = uploaded + waiting + dropped > 0

/**
 * Manual wiring for [MeasurementsViewModel] (no DI framework - same style
 * as ui.MainViewModelFactory / SourcesViewModelFactory): the app's shared
 * connected-source store and registry, the never-throws repository over
 * the process-wide Room database (memoized here since this factory took
 * over the pre-redesign Omron ViewModel factory's role as its owner - one
 * SQLite connection must serve every caller), the read orchestrator
 * ([SourceReadUseCase.create]) and the slot-scoped upload coordinator.
 */
@Suppress("UNCHECKED_CAST")
class MeasurementsViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MeasurementsViewModel::class.java)) {
            return MeasurementsViewModelFactory.createInstance(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }

    companion object {
        private const val TAG = "MeasurementsViewModelFactory"

        /** The one Room database of the app (omron/data), memoized process-wide. */
        @Volatile
        private var database: BloodPressureDatabase? = null

        private fun database(app: Application): BloodPressureDatabase =
            database ?: synchronized(this) {
                database ?: Room.databaseBuilder(
                    app,
                    BloodPressureDatabase::class.java,
                    BloodPressureDatabase.DATABASE_NAME
                )
                    // Schema v2 (task tefal2garmin-app-redesign-v3-xlg.2) has
                    // no migration path by product-owner decision: BP data is
                    // re-read from sources, legacy rows are not preserved.
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build().also { database = it }
            }

        fun createInstance(application: Application): MeasurementsViewModel {
            val repository = OmronBpRepository(
                dao = database(application).dao(),
                onError = { e -> Log.w(TAG, "BP repository failure: ${e.message}") }
            )
            val registry = HealthSyncWiring.sourceRegistry()
            val store = HealthSyncWiring.connectedSourceStore(application)
            val readUseCase = SourceReadUseCase.create(
                context = application,
                registry = registry,
                repository = repository,
                store = store,
            )
            // One GarminRepository instance for the BP upload, mirroring the
            // old OmronViewModelFactory wiring; the file-backed BP journal
            // defuses cross-instance concurrency (merge-on-save).
            val garminRepository = GarminRepository(application)
            val coordinator = BpSyncCoordinator(
                repository = repository,
                upload = { garminRepository.uploadBloodPressure(it) },
                onError = { e -> Log.w(TAG, "BP sync failure: ${e.message}") }
            )
            return MeasurementsViewModel(
                store = store,
                registry = registry,
                repository = repository,
                readSource = { source -> readUseCase.read(source) },
                upload = { userSlot -> coordinator.syncOnce(userSlot) },
            )
        }
    }
}
