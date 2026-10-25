package com.zamotkin.extrahealthgarmin.devices

import android.content.Context
import android.util.Log
import com.zamotkin.extrahealthgarmin.devices.api.BleBpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.BpReading
import com.zamotkin.extrahealthgarmin.devices.api.BpSource
import com.zamotkin.extrahealthgarmin.devices.api.BpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.DiscoveredBleDevice
import com.zamotkin.extrahealthgarmin.devices.api.SingletonBpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.SourceCapabilities
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadRequest
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSourceStore
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import com.zamotkin.extrahealthgarmin.omron.data.OmronBpRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Terminal outcome of one [SourceReadUseCase.read] pass: the source's own
 * [SourceReadResult] (Failed when the pass never reached the source), plus
 * the persistence counts when readings were stored.
 *
 * @property result what the pass reported - every terminal source variant
 *   (Success, ConnectionRequired, Timeout, Disconnected, Failed) passes
 *   through unchanged, so callers can render each state; a use-case-level
 *   failure (unknown type, no source instance) folds onto Failed.
 * @property stored the repository's insert/duplicate count; null when the
 *   pass never reached the store step (not a Success).
 *   [OmronBpRepository.StoreResult.success] false inside marks a store that
 *   wrote nothing — a DAO failure the pass survives with its readings
 *   re-deliverable (task xlg.15) and with none of its sync-state writes
 *   applied: no anchor advance, no fallback-flag clear (task xlg.16).
 */
data class SourceReadOutcome(
    val result: SourceReadResult,
    val stored: OmronBpRepository.StoreResult? = null,
)

/**
 * Opt-in seam for [BpSource] implementations whose successful-pass dedup
 * state must advance only AFTER the caller has persisted the readings the
 * pass delivered (task tefal2garmin-app-redesign-v3-xlg.15, the xlg.9
 * review fix): the standard 0x1810 driver's fingerprint journal, which
 * dedups clock-less records whose identity the storage's unique index
 * cannot carry (their record time IS the read time, so a re-read would
 * otherwise insert a new row every pass).
 *
 * [BpSource.read] on such a source DEFERS its commit: it returns the
 * readings and remembers them; the caller runs [commitDelivered] once its
 * store step confirmed the rows are durable, and stays silent when it did
 * not — an unpersisted pass then re-delivers everything on the next read.
 * The residual reverse window (rows stored, the commit lost to a crash)
 * costs one duplicate row, never a lost reading.
 *
 * Contract: [commitDelivered] never throws, journals exactly the delivered
 * set of the most recent successful read (a repeat call journals nothing
 * new), and callers serialize reads of one source — the per-source mutex
 * [SourceReadUseCase] already holds. The orchestrator confirms only when
 * it stored everything the pass delivered: its user-slot ingestion filter
 * never drops readings of the [UserAttribution.NONE] sources this seam
 * targets.
 */
interface DeferredCommitSource {

    /** Commits the deferred dedup state of the last successful [BpSource.read]. */
    fun commitDelivered()
}

/**
 * Runs one read pass of a connected blood-pressure source into the app's
 * dataset (task tefal2garmin-app-redesign-v3-xlg.4) — the production caller
 * of the [BpSource] seam:
 *
 *  ConnectedSource -> [SourceRegistry] resolves the type ->
 *  BpSource.read([SourceReadRequest]) with the stored sync mode and user
 *  slot -> the epic-R4 ingestion filter drops readings of other user slots
 *  -> [OmronBpRepository.storeReadings] tags the survivors with the source
 *  id (insert-ignore makes re-reads idempotent) -> a source with deferred
 *  dedup state ([DeferredCommitSource], the 0x1810 fingerprint journal)
 *  commits it, but only after that store confirmed the rows are in Room
 *  (task xlg.15: a store failure leaves the pass fully re-deliverable).
 *
 * Sync modes and read anchors (task xlg.5, epic R5): the mode comes from
 * [ConnectedSource.syncMode] — HISTORY reads the whole device memory
 * (idempotent through the repository's insert-ignore index), CURRENT reads
 * only the unread window since the last successful read. A successful pass
 * advances the app-side [ConnectedSource.lastReadAnchor] to the newest
 * ingested reading; a failed pass advances nothing. The self-healing
 * fallback covers the Omron counter-reset edge — every Current plan is
 * flagged BEFORE it runs (one row write under the per-source read lock,
 * task xlg.16: the monitor resets its unread counters mid-session, and a
 * pass that dies from that point on — a CancellationException escaping
 * [BpSource.read], a process death mid-pass — has consumed a window
 * nothing recorded), so the NEXT pass runs one full-ring History plan to
 * recover. The flag clears only when a pass's store step succeeded
 * ([OmronBpRepository.StoreResult.success], see [advanceReadState]) —
 * never on a failed read or a DAO failure — so no failure sequence can
 * lose readings a History re-read could still deliver; the price is one
 * extra full-ring read after a cancellation that struck before the device
 * consumed anything. The source's own anchors (Omron's device counters,
 * Health Connect's changes token) remain the real Current anchor;
 * lastReadAnchor is app-side bookkeeping and the model for sources without
 * device counters.
 *
 * Deliberately slim and free of UI knowledge: task xlg.6 wires the
 * read-now button and the autosync to it.
 *
 * Concurrency: reads of one source serialize on a per-source mutex — a
 * physical monitor serves one pass at a time — while different sources read
 * in parallel. Sync-state writes go through [ConnectedSourceStore.update],
 * which the file store makes atomic against the mode toggle in the UI.
 *
 * Never throws: [BpSource.read] guarantees a terminal result, and the
 * use case's own I/O (source construction, the store call) gets the same
 * guard treatment — failures fold onto [SourceReadResult.Failed];
 * cancellation propagates.
 */
class SourceReadUseCase(
    private val registry: SourceRegistry,
    private val sourceFor: (ConnectedSource) -> BpSource?,
    private val repository: OmronBpRepository,
    private val store: ConnectedSourceStore,
) {

    /**
     * One read lock per source id — created on first use. The map only
     * grows (a handful of sources), which is cheaper than locking every
     * read behind one global mutex.
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Runs one read pass of [source]; see the class KDoc for the shape. */
    suspend fun read(source: ConnectedSource): SourceReadOutcome {
        val lock = locks.computeIfAbsent(source.id) { Mutex() }
        return lock.withLock { readLocked(source) }
    }

    private suspend fun readLocked(snapshot: ConnectedSource): SourceReadOutcome {
        // Task xlg.5: the pass runs on the STORED row, not the caller's
        // snapshot - a stale snapshot (the previous pass's anchor, a mode
        // toggled mid-flight) must not drive the plan or regress what a
        // previous pass persisted. A snapshot without a store row (removed
        // elsewhere mid-flight) still reads; the write-back below simply
        // finds no row to update.
        val source = store.load().firstOrNull { it.id == snapshot.id } ?: snapshot
        // The type's capabilities drive the ingestion filter; an unknown
        // type id (a source type unregistered since the row was saved)
        // fails the pass instead of throwing.
        val type = registry.type(source.sourceTypeId)
            ?: return SourceReadOutcome(
                SourceReadResult.Failed("Unknown source type '${source.sourceTypeId}'")
            )
        val bpSource = try {
            sourceFor(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return sourceFailed(source, "could not be created", e)
        } ?: return SourceReadOutcome(
            SourceReadResult.Failed(
                "Source '${source.name}' of type '${source.sourceTypeId}' is not available"
            )
        )
        // The pass plan (task xlg.5): a pending full-read fallback overrides
        // the configured Current mode with one full-ring History plan.
        val fallbackPending = source.fullReadFallbackPending &&
            source.syncMode == SyncMode.CURRENT &&
            SyncMode.HISTORY in type.capabilities.supportedSyncModes
        val planMode = if (fallbackPending) SyncMode.HISTORY else source.syncMode
        // Task xlg.16 (the xlg.5 review): a Current plan is flagged BEFORE
        // it runs, not after it fails. The Omron monitor resets its unread
        // counters mid-session, so a pass that dies from that point on - a
        // CancellationException escaping read(), a process death mid-pass -
        // has consumed a window nothing recorded; the durable row write
        // closes that exit too (process death included), at the cost of one
        // extra full-ring read after a cancellation that struck before the
        // device consumed anything. Cleared only by advanceReadState, when
        // a pass's store step succeeded; the configured sync mode is never
        // rewritten.
        if (planMode == SyncMode.CURRENT) requestFullReadFallback(source, type)
        // read() is terminal by contract; the guard keeps a broken
        // implementation from crashing the caller (same hygiene as the
        // xlg.13 remediation).
        val result = try {
            bpSource.read(
                SourceReadRequest(syncMode = planMode, userSlot = source.userSlot)
            )
        } catch (e: CancellationException) {
            // The flag set above is already durable: the next pass re-reads
            // the whole ring instead of trusting the window.
            throw e
        } catch (e: Exception) {
            // The flag set above already covers the window this pass may
            // have consumed mid-session.
            return sourceFailed(source, "read failed unexpectedly", e)
        }
        if (result !is SourceReadResult.Success) {
            // Failed passes stay retryable: nothing stored, no state
            // advanced (the read anchor must not move) - the fallback flag
            // set before the plan is what makes the NEXT pass re-read the
            // window the source may have consumed.
            return SourceReadOutcome(result)
        }
        val kept = filterByUserSlot(result.readings, type.capabilities, source.userSlot)
        val stored = if (kept.isEmpty()) {
            OmronBpRepository.StoreResult(insertedCount = 0, duplicatesCount = 0)
        } else {
            repository.storeReadings(kept.map { it.toDomainReading() }, source.id)
        }
        // Task xlg.15: a source with deferred dedup state (the 0x1810
        // fingerprint journal) commits it only NOW — the store step
        // confirmed the rows are in Room. A DAO failure (success false)
        // commits nothing, so the next pass re-delivers everything (worst
        // case one duplicate row, never a lost reading); an all-duplicates
        // store confirms too, because those rows are already durable.
        if (stored.success && bpSource is DeferredCommitSource) {
            bpSource.commitDelivered()
        }
        if (!stored.success) {
            // Task xlg.16 (the xlg.5 review): the DAO wrote nothing - this
            // pass consumed its window for nothing Room holds. Advancing the
            // anchor or clearing the fallback flag here would mark that
            // window ingested while it is not; both stay, so the flagged
            // source re-reads the full ring on the next pass.
            return SourceReadOutcome(result = result, stored = stored)
        }
        advanceReadState(source, kept)
        return SourceReadOutcome(result = result, stored = stored)
    }

    /**
     * Flags [source] for one full-ring fallback read BEFORE its Current
     * plan runs (task xlg.16, the xlg.5 review fix): the Omron monitor
     * resets its unread counters mid-session, and a pass that dies from
     * that point on - a CancellationException escaping [BpSource.read]
     * (withContext exit-cancel), a process death mid-pass - has consumed a
     * window nothing recorded, so the flag must be durable BEFORE the
     * session starts; a post-failure write cannot cover those exits. The
     * NEXT pass then runs one full-ring History plan - the repository's
     * insert-ignore index absorbs everything already stored - instead of
     * trusting the window. The cost: a cancellation that struck before the
     * device consumed anything still costs one extra full-ring read;
     * over-flagging costs a slow read, under-flagging can cost readings.
     * Skipped for sources that cannot fall back (no History support) -
     * their own anchor, like Health Connect's changes token, advances only
     * on success, so a retry re-reads exactly what the failed pass missed.
     * Never rewrites the configured sync mode; cleared only by
     * [advanceReadState], when a pass's store step succeeded.
     */
    private suspend fun requestFullReadFallback(
        source: ConnectedSource,
        type: BpSourceType,
    ) {
        if (SyncMode.HISTORY !in type.capabilities.supportedSyncModes) return
        val saved = store.update(source.id) { row ->
            row.copy(fullReadFallbackPending = true)
        }
        if (!saved) {
            Log.w(TAG, "Could not flag the full-read fallback of source '${source.id}'")
        }
    }

    /**
     * Persists the sync-state outcome of a successful pass (task xlg.5):
     * [ConnectedSource.lastReadAnchor] advances to the newest INGESTED
     * reading - never regressing, never moving on a pass that ingested
     * nothing - and a pending full-read fallback clears, because a pass
     * under a pending flag always ran the full-ring History plan: the
     * window the failed Current pass may have consumed has been re-read.
     * Runs ONLY after a store step that succeeded
     * ([OmronBpRepository.StoreResult.success], task xlg.16): a DAO failure
     * wrote nothing, so the anchor stays and the fallback flag stays - the
     * next pass re-reads instead of trusting a window Room does not hold.
     * Bookkeeping only - the readings themselves are already in Room, and
     * the source's own anchors stay untouched by this write.
     */
    private suspend fun advanceReadState(source: ConnectedSource, kept: List<BpReading>) {
        val newest = kept.maxOfOrNull { it.timestampEpochSec }
        val saved = store.update(source.id) { row ->
            row.copy(
                lastReadAnchor = when (newest) {
                    null -> row.lastReadAnchor
                    else -> maxOf(row.lastReadAnchor ?: Long.MIN_VALUE, newest)
                },
                fullReadFallbackPending = false
            )
        }
        if (!saved) {
            Log.w(TAG, "Could not persist the read anchor of source '${source.id}'")
        }
    }

    private fun sourceFailed(source: ConnectedSource, step: String, e: Exception) =
        SourceReadOutcome(
            SourceReadResult.Failed(
                "Source '${source.name}' $step: ${e.message ?: e.javaClass.simpleName}"
            )
        )

    companion object {
        private const val TAG = "SourceReadUseCase"

        /**
         * Production wiring (manual, no DI framework): resolves the stored
         * source through the registry — BLE types rebuild their
         * [DiscoveredBleDevice] from the stored name and address (RSSI is
         * scan-time data, meaningless at read time), singleton types create
         * their one instance. [store] is the app's shared
         * [ConnectedSourceStore] (HealthSyncWiring.connectedSourceStore) -
         * the same instance the mode toggle writes. Task xlg.6 calls this
         * from the read UI and the autosync wiring.
         */
        fun create(
            context: Context,
            registry: SourceRegistry,
            repository: OmronBpRepository,
            store: ConnectedSourceStore,
        ): SourceReadUseCase = SourceReadUseCase(
            registry = registry,
            sourceFor = { source ->
                when (val type = registry.type(source.sourceTypeId)) {
                    is BleBpSourceType -> source.address?.let { address ->
                        type.createFromDevice(
                            context,
                            DiscoveredBleDevice(name = source.name, address = address, rssi = 0)
                        )
                    }

                    is SingletonBpSourceType -> type.createSingleton(context)

                    // Unknown id, or a type shape the wiring does not know
                    // (BpSourceType is an open interface): no source, and
                    // the read pass reports it as unavailable.
                    else -> null
                }
            },
            repository = repository,
            store = store,
        )
    }
}

/**
 * The epic-R4 ingestion filter: SLOTS sources keep only their picked
 * user's readings — the wire protocol already scopes the Omron read to one
 * slot's window, so this is the defensive second layer against a
 * misbehaving device leaking another household member's rows into the
 * dataset. NONE sources keep everything (every reading is the app owner's).
 * A SLOTS source without a picked slot keeps nothing: its reads fail at
 * the source, and if one ever succeeded anyway, storing unattributed rows
 * would be worse than storing none.
 */
private fun filterByUserSlot(
    readings: List<BpReading>,
    capabilities: SourceCapabilities,
    userSlot: Int?,
): List<BpReading> = when (capabilities.userAttribution) {
    UserAttribution.NONE -> readings
    UserAttribution.SLOTS -> readings.filter { it.userSlot == userSlot }
}

/** Seam reading -> persistence record: a plain field-by-field copy. */
private fun BpReading.toDomainReading() = BloodPressureReading(
    timestampEpochSec = timestampEpochSec,
    systolic = systolic,
    diastolic = diastolic,
    pulse = pulse,
    mov = mov,
    ihb = ihb,
    userSlot = userSlot
)
