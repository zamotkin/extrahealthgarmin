package com.zamotkin.extrahealthgarmin.devices.api

/**
 * How a source attributes its readings to users (epic
 * tefal2garmin-app-redesign-v3, R4).
 *
 *  - [SLOTS] — the device carries its own user profiles (Omron: slots 1|2);
 *    the user picks one at pairing and every read names it.
 *  - [NONE] — no device-side attribution (Health Connect, standard 0x1810
 *    without a User ID): every reading belongs to the phone owner and there
 *    is nothing to configure.
 */
enum class UserAttribution { SLOTS, NONE }

/**
 * What one read pass fetches (epic R5).
 *
 *  - [HISTORY] — full device memory read, idempotent (Omron's whole-ring
 *    transfer).
 *  - [CURRENT] — only the new readings since the last successful read
 *    anchor (Omron's unread window; Health Connect's changes-token pass).
 */
enum class SyncMode { HISTORY, CURRENT }

/**
 * What a source type can do — rendered by the add-source flow (user-slot
 * picker, sync-mode choice) and enforced by the source's [BpSource.read].
 *
 * @property userAttribution whether readings carry a device user slot
 * @property supportedSyncModes the modes [BpSource.read] accepts
 * @property requiresPairingKey whether connecting needs an app-level
 *   pairing key (Omron's 16-byte key; standard 0x1810 uses system bonding)
 * @property pairingKeyBytes the exact pairing-key length in bytes a
 *   [requiresPairingKey] source accepts (the M7: 16); null for keyless
 *   types - the add-source flow validates the typed key against this
 *   capability, never against a vendor constant
 * @property userSlotCount how many device user slots a
 *   [UserAttribution.SLOTS] source picks from (the M7 carries two; slots
 *   are 1-based). Ignored for [UserAttribution.NONE] sources.
 */
data class SourceCapabilities(
    val userAttribution: UserAttribution,
    val supportedSyncModes: Set<SyncMode>,
    val requiresPairingKey: Boolean,
    val pairingKeyBytes: Int? = null,
    val userSlotCount: Int = 2
) {

    /**
     * The pickable device user slots, 1-based — what the add-source flow's
     * one-time pick renders; empty for [UserAttribution.NONE] sources
     * (nothing to configure, epic R4).
     */
    val userSlots: List<Int>
        get() = if (userAttribution == UserAttribution.SLOTS) {
            (1..userSlotCount).toList()
        } else {
            emptyList()
        }
}

/**
 * One vendor-neutral blood-pressure reading — the common shape behind
 * [com.zamotkin.extrahealthgarmin.omron.BloodPressureReading] and future
 * sources. Pure JVM; no Android or BLE types.
 *
 * @property timestampEpochSec measurement time, seconds since Unix epoch (UTC)
 * @property systolic systolic pressure, mmHg
 * @property diastolic diastolic pressure, mmHg
 * @property pulse heart rate, beats per minute (0 = not measured)
 * @property mov movement-during-measurement flag
 * @property ihb irregular-heartbeat flag
 * @property userSlot the device user slot for [UserAttribution.SLOTS]
 *   sources; [APP_OWNER_SLOT] for [UserAttribution.NONE] sources
 */
data class BpReading(
    val timestampEpochSec: Long,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    val mov: Boolean = false,
    val ihb: Boolean = false,
    val userSlot: Int = APP_OWNER_SLOT
) {
    companion object {
        /** Slot of sources without user attribution: every reading is the app owner's. */
        const val APP_OWNER_SLOT = 0
    }
}

/**
 * One read pass. [syncMode] selects what to fetch (see [SyncMode]);
 * [userSlot] names the device user for [UserAttribution.SLOTS] sources and
 * is ignored by [UserAttribution.NONE] sources.
 */
data class SourceReadRequest(
    val syncMode: SyncMode,
    val userSlot: Int? = null
)

/**
 * Per-pass counters a source reports alongside its readings. Reading-only
 * sources (BLE) leave them at zero — their upload happens outside the
 * source; sources whose pipeline uploads internally (Health Connect) fill
 * them in.
 */
data class SourceCounters(
    /** Readings uploaded to Garmin during this pass. */
    val uploaded: Int = 0,
    /** Readings held back pending a precondition (e.g. the Garmin watch's pulse sample). */
    val waiting: Int = 0,
    /** Readings the pass gave up on (precondition never met, out of range). */
    val dropped: Int = 0
)

/**
 * Terminal outcome of one [BpSource.read] pass. Sources fold their stack's
 * failures onto these variants instead of throwing, so callers can render
 * every state without exception handling.
 */
sealed class SourceReadResult {

    /**
     * The pass read the source. [readings] are the new readings (BLE
     * sources); a source whose pipeline consumed them internally (Health
     * Connect uploads to Garmin itself) reports none and tells the story in
     * [counters].
     */
    data class Success(
        val readings: List<BpReading>,
        val counters: SourceCounters = SourceCounters()
    ) : SourceReadResult()

    /**
     * The connect step is missing and only a user action fixes it: BLE
     * bonding, a rejected app pairing key, or (Health Connect) the
     * permission grant that IS its connect step.
     */
    data class ConnectionRequired(val reason: String) : SourceReadResult()

    /** A read step exceeded its deadline; [reason] names the step. */
    data class Timeout(val reason: String) : SourceReadResult()

    /** The source dropped mid-read; [reason] names the step it died in. */
    data class Disconnected(val reason: String) : SourceReadResult()

    /** Any other failure, including a request the source does not support. */
    data class Failed(val reason: String) : SourceReadResult()
}

/**
 * One connected blood-pressure source — the vendor-neutral seam behind the
 * BLE drivers (Omron M7 Intelli IT, standard 0x1810) and Health Connect
 * (epic tefal2garmin-app-redesign-v3-xlg.2). Instances are created by their
 * [BpSourceType]; identity and capabilities mirror the type that made them.
 *
 * Implementations map their stack's read call onto [read] and its failures
 * onto [SourceReadResult]; they never throw.
 */
interface BpSource {

    /** Registry key of the source type that created this source (e.g. "omron-ble"). */
    val sourceTypeId: String

    /** Display name of the source type (an R.string resource). */
    val displayNameRes: Int

    /** What this source can do; see [SourceCapabilities]. */
    val capabilities: SourceCapabilities

    /**
     * Runs one read pass. The sync mode and user slot come from the stored
     * source configuration; the result is terminal — never an exception.
     */
    suspend fun read(request: SourceReadRequest): SourceReadResult
}
