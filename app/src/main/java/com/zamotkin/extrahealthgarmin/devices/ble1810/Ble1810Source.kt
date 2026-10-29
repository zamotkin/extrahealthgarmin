package com.zamotkin.extrahealthgarmin.devices.ble1810

import android.content.Context
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.devices.DeferredCommitSource
import com.zamotkin.extrahealthgarmin.devices.api.BleBpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.BpReading
import com.zamotkin.extrahealthgarmin.devices.api.BpSource
import com.zamotkin.extrahealthgarmin.devices.api.DiscoveredBleDevice
import com.zamotkin.extrahealthgarmin.devices.api.SourceCapabilities
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadRequest
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution

/**
 * [BpSource] adapter over the standard BLE Blood Pressure Service driver
 * (task tefal2garmin-app-redesign-v3-xlg.9) — the
 * [com.zamotkin.extrahealthgarmin.devices.omron.OmronBleSource] pattern:
 * the BLE read and the fingerprint journal arrive as injected seams, so the
 * adapter is plain JVM and unit-testable with fakes.
 *
 * Sync semantics: one read pass is whatever the monitor pushes after the
 * CCCD subscription — a full-history re-push for the all-stored devices, or
 * just the latest record (transfer semantics vary per model,
 * docs/ble-1810-protocol.md §4.2). That is the History mode of epic R5:
 * idempotent end-to-end — timestamped records dedup through the storage's
 * unique index and the upload journal (exactly like the Omron ring re-read),
 * and timestamp-less records dedup through the journal here, because their
 * record time is the read time and would otherwise duplicate on every pass.
 * The journal grows only on a CONFIRMED pass (task xlg.15, the xlg.9 review
 * fix): a successful read DEFERS its commit to
 * [DeferredCommitSource.commitDelivered], which the read orchestrator runs
 * only after the storage step persisted the readings — a failed read OR a
 * failed store leaves everything retriable, and the residual reverse window
 * (rows stored, the commit lost to a crash) costs one duplicate row, never
 * a lost reading.
 *
 * Attribution is NONE per epic R4: the User ID byte is informational (often
 * 0xFF) and every reading belongs to the app owner.
 */
class Ble1810Source(
    private val bleRead: suspend () -> Ble1810Result,
    private val journal: Ble1810FingerprintJournal
) : BpSource, DeferredCommitSource {

    /**
     * The delivered set of the most recent successful pass, awaiting the
     * caller's store confirmation. Replaced by every successful read and
     * consumed by [commitDelivered] — a pass the caller never confirms
     * simply gets re-delivered by the next one (the journal never advanced).
     */
    private var pendingDelivered: List<Ble1810Measurement> = emptyList()

    override val sourceTypeId: String = TYPE_ID
    override val displayNameRes: Int = DISPLAY_NAME_RES
    override val capabilities: SourceCapabilities = CAPABILITIES

    override suspend fun read(request: SourceReadRequest): SourceReadResult {
        if (request.syncMode != SyncMode.HISTORY) {
            return SourceReadResult.Failed("Standard BLE monitors support History sync only")
        }
        // A userSlot, if a caller sends one, is meaningless here (NONE
        // attribution) and safely ignored.
        return when (val result = bleRead()) {
            is Ble1810Result.Success -> {
                val delivered = journal.filterUnseen(result.measurements)
                // Task xlg.15: the journal does NOT advance here. The caller
                // stores the readings only after read() returns, and a failed
                // store must leave them re-deliverable — committing now would
                // filter them out of every later HISTORY pass (permanent
                // silent loss behind a Success outcome). commitDelivered()
                // is the deferred commit the caller runs once the store
                // confirmed.
                pendingDelivered = delivered
                SourceReadResult.Success(delivered.map { it.toBpReading() })
            }
            is Ble1810Result.BondingRequired -> SourceReadResult.ConnectionRequired(result.reason)
            is Ble1810Result.Timeout -> SourceReadResult.Timeout(result.step)
            is Ble1810Result.Disconnected -> SourceReadResult.Disconnected(result.step)
            is Ble1810Result.Failed -> SourceReadResult.Failed(result.reason)
        }
    }

    override fun commitDelivered() {
        journal.markSeen(pendingDelivered)
        pendingDelivered = emptyList()
    }

    companion object {
        const val TYPE_ID = "ble-standard-1810"

        /**
         * Getter-backed, not const: an R field is not a compile-time
         * constant in this setup, and a const companion val initialized
         * from one compiles cross-class references to a getter that is
         * never generated (NoSuchMethodError at runtime).
         */
        val DISPLAY_NAME_RES: Int get() = R.string.bp_source_type_ble_standard_1810

        /**
         * The push decides what arrives (no RACP on consumer units), so
         * every pass is the idempotent full read; no device user profiles;
         * connection is system bonding, not an app pairing key.
         */
        val CAPABILITIES = SourceCapabilities(
            userAttribution = UserAttribution.NONE,
            supportedSyncModes = setOf(SyncMode.HISTORY),
            requiresPairingKey = false
        )
    }
}

/**
 * [BleBpSourceType] of the standard 0x1810 monitors: wires the adapter onto
 * the real GATT client for the discovered [device]'s address and the
 * per-device fingerprint journal. The address is the persisted handle
 * ([com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource.address]);
 * removal of a source should also drop the system bond
 * ([Ble1810Bonds.removeBond]) — the add-source flow (task xlg.3) owns that
 * step.
 */
object Ble1810BpSourceType : BleBpSourceType {

    override val typeId: String = Ble1810Source.TYPE_ID
    override val displayNameRes: Int = Ble1810Source.DISPLAY_NAME_RES
    override val capabilities: SourceCapabilities = Ble1810Source.CAPABILITIES

    override fun createFromDevice(context: Context, device: DiscoveredBleDevice): BpSource =
        Ble1810Source(
            bleRead = { Ble1810Client.readMeasurements(context, device.address) },
            journal = Ble1810FingerprintJournal.inFilesDir(context.filesDir, device.address)
        )
}

/** Driver record -> seam record: a plain field-by-field copy. */
private fun Ble1810Measurement.toBpReading() = BpReading(
    timestampEpochSec = timestampEpochSec,
    systolic = systolicMmHg,
    diastolic = diastolicMmHg,
    pulse = pulseBpm ?: 0,
    mov = status?.bodyMovement ?: false,
    ihb = status?.irregularPulse ?: false,
    userSlot = BpReading.APP_OWNER_SLOT
)
