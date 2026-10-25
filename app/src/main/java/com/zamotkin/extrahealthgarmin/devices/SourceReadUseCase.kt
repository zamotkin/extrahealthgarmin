package com.zamotkin.extrahealthgarmin.devices

import android.content.Context
import com.zamotkin.extrahealthgarmin.devices.api.BleBpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.BpReading
import com.zamotkin.extrahealthgarmin.devices.api.BpSource
import com.zamotkin.extrahealthgarmin.devices.api.DiscoveredBleDevice
import com.zamotkin.extrahealthgarmin.devices.api.SingletonBpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.SourceCapabilities
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadRequest
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource
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
 * @property stored the repository's insert/duplicate count; null when
 *   nothing was stored (not a Success, or a pass that read nothing).
 */
data class SourceReadOutcome(
    val result: SourceReadResult,
    val stored: OmronBpRepository.StoreResult? = null,
)

/**
 * Runs one read pass of a connected blood-pressure source into the app's
 * dataset (task tefal2garmin-app-redesign-v3-xlg.4) — the production caller
 * of the [BpSource] seam:
 *
 *  ConnectedSource -> [SourceRegistry] resolves the type ->
 *  BpSource.read([SourceReadRequest]) with the stored sync mode and user
 *  slot -> the epic-R4 ingestion filter drops readings of other user slots
 *  -> [OmronBpRepository.storeReadings] tags the survivors with the source
 *  id (insert-ignore makes re-reads idempotent).
 *
 * Deliberately slim and free of UI knowledge: task xlg.5 adds the
 * read-mode/anchor handling on top of [read], task xlg.6 wires the
 * read-now button and the autosync to it.
 *
 * Concurrency: reads of one source serialize on a per-source mutex — a
 * physical monitor serves one pass at a time — while different sources read
 * in parallel.
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

    private suspend fun readLocked(source: ConnectedSource): SourceReadOutcome {
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
        // read() is terminal by contract; the guard keeps a broken
        // implementation from crashing the caller (same hygiene as the
        // xlg.13 remediation).
        val result = try {
            bpSource.read(
                SourceReadRequest(syncMode = source.syncMode, userSlot = source.userSlot)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return sourceFailed(source, "read failed unexpectedly", e)
        }
        if (result !is SourceReadResult.Success) {
            // Failed passes stay retryable: nothing stored, no state
            // advanced (the read anchor of task xlg.5 must not move).
            return SourceReadOutcome(result)
        }
        val kept = filterByUserSlot(result.readings, type.capabilities, source.userSlot)
        val stored = if (kept.isEmpty()) {
            OmronBpRepository.StoreResult(insertedCount = 0, duplicatesCount = 0)
        } else {
            repository.storeReadings(kept.map { it.toDomainReading() }, source.id)
        }
        return SourceReadOutcome(result = result, stored = stored)
    }

    private fun sourceFailed(source: ConnectedSource, step: String, e: Exception) =
        SourceReadOutcome(
            SourceReadResult.Failed(
                "Source '${source.name}' $step: ${e.message ?: e.javaClass.simpleName}"
            )
        )

    companion object {

        /**
         * Production wiring (manual, no DI framework): resolves the stored
         * source through the registry — BLE types rebuild their
         * [DiscoveredBleDevice] from the stored name and address (RSSI is
         * scan-time data, meaningless at read time), singleton types create
         * their one instance. Task xlg.6 calls this from the read UI and
         * the autosync wiring.
         */
        fun create(
            context: Context,
            registry: SourceRegistry,
            repository: OmronBpRepository,
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
