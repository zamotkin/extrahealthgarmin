package com.zamotkin.extrahealthgarmin.ui.screens.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zamotkin.extrahealthgarmin.BuildConfig
import com.zamotkin.extrahealthgarmin.devices.SourceRegistry
import com.zamotkin.extrahealthgarmin.devices.api.BpSourceType
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSourceStore
import com.zamotkin.extrahealthgarmin.garmin.BpUploadJournal
import com.zamotkin.extrahealthgarmin.garmin.UploadJournal
import com.zamotkin.extrahealthgarmin.health.HealthPermissions
import com.zamotkin.extrahealthgarmin.health.HealthSyncWiring
import com.zamotkin.extrahealthgarmin.omron.data.OmronBpRepository
import com.zamotkin.extrahealthgarmin.ui.MainUiState
import com.zamotkin.extrahealthgarmin.ui.MainViewModel
import com.zamotkin.extrahealthgarmin.ui.UploadSummary
import com.zamotkin.extrahealthgarmin.ui.screens.measurements.MeasurementsViewModelFactory
import com.zamotkin.extrahealthgarmin.ui.uploadSummary
import java.time.Duration
import kotlinx.coroutines.launch

/**
 * Garmin Connect group state (task tefal2garmin-app-redesign-v3-xlg.7, epic
 * R7). [signedIn] decides which action the group renders — sign-in (opens
 * the shared login dialog) versus sign-out; [lastUpload] is the same
 * outcome line Home renders (null before the first pass).
 */
data class GarminGroupState(
    val signedIn: Boolean,
    val lastUpload: UploadSummary?,
)

/**
 * Health Connect group state: the permission/integration status plus the
 * permission-screen cancellation streak. [granted] is the foreground read
 * set (the sync gate); [directToHcSettings] is the Health Connect UX
 * guideline's twice-cancelled state — stop re-prompting with the permission
 * screen and direct the user to the Health Connect settings instead.
 */
data class HcGroupState(
    /** `true` while the first availability check is still in flight. */
    val checking: Boolean,
    /** Provider present and new enough to talk to. */
    val available: Boolean,
    /** Foreground read permissions granted — the "Sync with Health Connect" toggle's checked state. */
    val granted: Boolean,
    val bloodPressureGranted: Boolean,
    val hydrationGranted: Boolean,
    /** A sync pass is in flight (disables the backfill entry). */
    val syncRunning: Boolean,
    val directToHcSettings: Boolean,
)

/** Connections group state: the connected sources and their destructive actions. */
data class ConnectionsGroupState(
    val sources: List<ConnectedSource>,
    /** Distinct picked device-user slots among slot-attributed sources, ascending. */
    val userSlots: List<Int>,
    /** Outcome of the last confirmed delete; null until one ran. */
    val deleteResult: DeleteReadingsResult?,
)

/** Outcome of a confirmed delete-readings action. */
enum class DeleteReadingsResult { DELETED, FAILED }

/**
 * One source's read anchor for the About diagnostics — enough to name the
 * source on screen (the type's display name for singleton sources, the
 * device name otherwise, the same rule as the sources list).
 */
data class SourceReadAnchor(
    val sourceTypeId: String,
    val name: String,
    val address: String?,
    val anchorEpochSec: Long?,
)

/** About group state: version, source link inputs and the read-only diagnostics rows. */
data class AboutGroupState(
    val appVersion: String,
    val weighInJournalCount: Int,
    val bpJournalCount: Int,
    val lastWeighInSyncEpochMs: Long?,
    val readAnchors: List<SourceReadAnchor>,
)

/**
 * Backs the grouped Settings screen (task
 * tefal2garmin-app-redesign-v3-xlg.7, epic R7): one state class per group —
 * Connections, Garmin Connect, Health Connect, About — over the existing
 * seams. No new business logic anywhere:
 *
 *  - the Garmin and Health Connect groups are views over the shared
 *    [MainViewModel] state machine (the one Home renders, so the two tabs
 *    can never disagree about the account or permissions); the sign-in
 *    entry opens the shared login dialog, sign-out and backfill delegate to
 *    the same passes Home runs;
 *  - Connections read [ConnectedSourceStore]; the destructive actions are
 *    the deleted ui/omron SettingsScreen's semantics on the existing
 *    never-throws [OmronBpRepository] calls — delete one monitor user's
 *    readings / all readings, Garmin-uploaded readings never touched there;
 *  - About's diagnostics read the dedup journals and the stored anchors.
 *
 * The Health Connect cancellation streak is the only state owned here: a
 * permission-screen result that does not grant the foreground set advances
 * it, a granted result resets it, and at [MAX_PERMISSION_CANCELLATIONS] the
 * grant action stops re-prompting and directs to Health Connect settings
 * (official Health Connect UX guideline — consistency, transparency,
 * clarity).
 *
 * Open solely so the unit test can subclass it and simulate [onCleared].
 */
open class SettingsViewModel(
    private val mainViewModel: MainViewModel,
    private val store: ConnectedSourceStore,
    private val registry: SourceRegistry,
    private val bpRepository: OmronBpRepository,
    private val weighInJournalCount: () -> Int,
    private val bpJournalCount: () -> Int,
    private val appVersion: String,
) : ViewModel() {

    private val _garmin = MutableLiveData<GarminGroupState>()
    val garmin: LiveData<GarminGroupState> = _garmin

    private val _healthConnect = MutableLiveData<HcGroupState>()
    val healthConnect: LiveData<HcGroupState> = _healthConnect

    private val _connections = MutableLiveData(
        ConnectionsGroupState(emptyList(), emptyList(), null)
    )
    val connections: LiveData<ConnectionsGroupState> = _connections

    private val _about = MutableLiveData(
        AboutGroupState(appVersion, 0, 0, null, emptyList())
    )
    val about: LiveData<AboutGroupState> = _about

    /** Consecutive permission-screen cancellations; reset by a granted result. */
    private var permissionCancellations = 0

    /**
     * Re-derives the Garmin and Health Connect groups on every shared-state
     * emission (a sync finishing, a status refresh) — observeForever so the
     * groups stay live without an on-screen observer, detached in
     * [onCleared] (the shared MainViewModel outlives this screen's entry).
     */
    private val sharedStateObserver = Observer<MainUiState> {
        emitGarmin()
        emitHealthConnect()
    }

    init {
        mainViewModel.uiState.observeForever(sharedStateObserver)
        refresh()
    }

    /**
     * Re-reads the store-backed groups — first entry, every return to the
     * tab and after every confirmed delete. Cheap by design: one small JSON
     * file and two journal reads.
     */
    fun refresh() {
        val sources = store.load()
        _connections.value = ConnectionsGroupState(
            sources = sources,
            userSlots = sources.mapNotNull { it.userSlot }.distinct().sorted(),
            deleteResult = _connections.value?.deleteResult,
        )
        _about.value = AboutGroupState(
            appVersion = appVersion,
            weighInJournalCount = weighInJournalCount(),
            bpJournalCount = bpJournalCount(),
            lastWeighInSyncEpochMs = mainViewModel.uiState.value?.hcLastSyncEpochMs,
            readAnchors = sources.map {
                SourceReadAnchor(it.sourceTypeId, it.name, it.address, it.lastReadAnchor)
            },
        )
    }

    /** The registry type behind a stored source, or null for an unknown id. */
    fun typeOf(sourceTypeId: String): BpSourceType? = registry.type(sourceTypeId)

    // ------------------------------------------------------------------ //
    //  Garmin Connect group                                               //
    // ------------------------------------------------------------------ //

    /**
     * The sign-in entry: opens the shared login dialog (rendered at the app
     * level, over whichever tab is active) through the shared state machine.
     */
    fun requestGarminSignIn() = mainViewModel.requestGarminLogin()

    /**
     * Sign-out — delegates to the shared machine so Home's status flips with
     * it. Only the Garmin tokens go; every local sync state stays.
     */
    fun signOutGarmin() = mainViewModel.signOut()

    // ------------------------------------------------------------------ //
    //  Health Connect group                                               //
    // ------------------------------------------------------------------ //

    /** The permission set for the Health Connect permission screen. */
    fun hcPermissionsToRequest(): Set<String> = mainViewModel.permissionsToRequest()

    /**
     * Permission-screen result: a result without the foreground set advances
     * the cancellation streak (after two, the grant action directs to the
     * Health Connect settings instead of re-prompting), a granted result
     * resets it. The result then feeds the shared machine — Home's status
     * and the sync gate update with it.
     */
    fun onHcPermissionsResult(granted: Set<String>) {
        permissionCancellations =
            if (HealthPermissions.hasForegroundPermissions(granted)) 0
            else permissionCancellations + 1
        emitHealthConnect()
        mainViewModel.onHcPermissionsResult(granted)
    }

    /**
     * The relocated backfill entry (Home's picker moved here — it belongs
     * with the weigh-in/Health Connect sync context). Runs the identical
     * pass "Sync now" runs, over the last [window].
     */
    fun backfill(window: Duration) = mainViewModel.backfill(window)

    // ------------------------------------------------------------------ //
    //  Connections group — destructive actions                            //
    // ------------------------------------------------------------------ //

    /**
     * Confirmed "delete readings by user": deletes every stored reading of
     * [userSlot] (the other slot's rows are untouched) through the
     * never-throws repository. Garmin-uploaded readings are NOT removed
     * there. The outcome lands on [ConnectionsGroupState.deleteResult].
     */
    fun deleteReadingsForUser(userSlot: Int) {
        viewModelScope.launch {
            setDeleteResult(if (bpRepository.deleteForUser(userSlot)) {
                DeleteReadingsResult.DELETED
            } else {
                DeleteReadingsResult.FAILED
            })
        }
    }

    /**
     * Confirmed "delete all readings": deletes every stored reading through
     * the never-throws repository. Garmin-uploaded readings are NOT removed
     * there. The outcome lands on [ConnectionsGroupState.deleteResult].
     */
    fun deleteAllReadings() {
        viewModelScope.launch {
            setDeleteResult(if (bpRepository.deleteAll()) {
                DeleteReadingsResult.DELETED
            } else {
                DeleteReadingsResult.FAILED
            })
        }
    }

    private fun setDeleteResult(result: DeleteReadingsResult) {
        _connections.value = _connections.value?.copy(deleteResult = result)
    }

    // ------------------------------------------------------------------ //
    //  State plumbing                                                     //
    // ------------------------------------------------------------------ //

    private fun emitGarmin() {
        val state = mainViewModel.uiState.value ?: return
        _garmin.value = GarminGroupState(
            signedIn = state.garminConnected,
            lastUpload = state.uploadSummary(),
        )
    }

    private fun emitHealthConnect() {
        val state = mainViewModel.uiState.value ?: return
        _healthConnect.value = HcGroupState(
            checking = state.hcAvailable == null,
            available = state.hcAvailable == true,
            granted = state.hcPermissionsGranted,
            bloodPressureGranted = state.hcBloodPressureGranted,
            hydrationGranted = state.hcHydrationGranted,
            syncRunning = state.hcSyncRunning,
            directToHcSettings = !state.hcPermissionsGranted &&
                permissionCancellations >= MAX_PERMISSION_CANCELLATIONS,
        )
    }

    override fun onCleared() {
        mainViewModel.uiState.removeObserver(sharedStateObserver)
        super.onCleared()
    }

    companion object {
        /**
         * Health Connect UX guideline: after two consecutive permission-screen
         * cancellations stop re-prompting and direct the user to the Health
         * Connect settings instead.
         */
        const val MAX_PERMISSION_CANCELLATIONS = 2
    }
}

/**
 * Manual wiring for [SettingsViewModel] (no DI framework - same style as
 * ui.MainViewModelFactory / SourcesViewModelFactory): the shared
 * activity-scoped [MainViewModel], the app's connected-source store and
 * registry, the never-throws repository over the process-wide Room database
 * (owned by [MeasurementsViewModelFactory] - one SQLite connection must
 * serve every caller), and fresh journal readers per refresh (the journals
 * cache their key set at construction, so a per-read construction reflects
 * what other instances persisted meanwhile).
 */
@Suppress("UNCHECKED_CAST")
class SettingsViewModelFactory(
    private val application: Application,
    private val mainViewModel: MainViewModel,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            return createInstance(application, mainViewModel) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }

    companion object {
        private const val TAG = "SettingsViewModel"

        fun createInstance(
            application: Application,
            mainViewModel: MainViewModel,
        ): SettingsViewModel {
            val filesDir = application.filesDir
            return SettingsViewModel(
                mainViewModel = mainViewModel,
                store = HealthSyncWiring.connectedSourceStore(application),
                registry = HealthSyncWiring.sourceRegistry(),
                bpRepository = OmronBpRepository(
                    dao = MeasurementsViewModelFactory.database(application).dao(),
                    onError = { e -> Log.w(TAG, "BP repository failure: ${e.message}") },
                ),
                weighInJournalCount = { UploadJournal.inFilesDir(filesDir).sentCount() },
                bpJournalCount = { BpUploadJournal.inFilesDir(filesDir).uploadedCount() },
                appVersion = BuildConfig.VERSION_NAME,
            )
        }
    }
}
