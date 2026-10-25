package com.zamotkin.extrahealthgarmin.devices.omron

import android.content.Context
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.devices.api.BleBpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.BpReading
import com.zamotkin.extrahealthgarmin.devices.api.BpSource
import com.zamotkin.extrahealthgarmin.devices.api.DiscoveredBleDevice
import com.zamotkin.extrahealthgarmin.devices.api.SourceCapabilities
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadRequest
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import com.zamotkin.extrahealthgarmin.omron.ble.FileKeyPersistence
import com.zamotkin.extrahealthgarmin.omron.ble.KeystoreAesPairingKeyCrypto
import com.zamotkin.extrahealthgarmin.omron.ble.OmronBleClient
import com.zamotkin.extrahealthgarmin.omron.ble.OmronBleResult
import com.zamotkin.extrahealthgarmin.omron.ble.OmronKeyStore
import java.io.File

/**
 * [BpSource] adapter over the existing Omron M7 BLE stack (task
 * tefal2garmin-app-redesign-v3-xlg.2) - the omron/ble code is untouched;
 * this class only maps the seam's request/result onto it.
 *
 * The BLE read and the pairing key arrive as injected seams (pre-bound with
 * the [Context] by [OmronBleSourceType]), so the adapter is plain JVM and
 * unit-testable with fakes. [read] maps the sync mode onto the stack's
 * full-read flag (HISTORY = whole ring, CURRENT = unread window), requires
 * the monitor user slot, and folds [OmronBleResult] onto
 * [SourceReadResult] without touching the BLE machinery. Full mode
 * semantics (read anchors) land in task xlg.5.
 */
class OmronBleSource(
    private val keyProvider: () -> ByteArray,
    private val bleRead: suspend (ByteArray, Int, Boolean) -> OmronBleResult
) : BpSource {

    override val sourceTypeId: String = TYPE_ID
    override val displayNameRes: Int = DISPLAY_NAME_RES
    override val capabilities: SourceCapabilities = CAPABILITIES

    override suspend fun read(request: SourceReadRequest): SourceReadResult {
        val userSlot = request.userSlot
            ?: return SourceReadResult.Failed("Omron reads need a monitor user slot")
        val fullRead = when (request.syncMode) {
            SyncMode.HISTORY -> true
            SyncMode.CURRENT -> false
        }
        return when (val result = bleRead(keyProvider(), userSlot, fullRead)) {
            is OmronBleResult.Success ->
                SourceReadResult.Success(result.readings.map { it.toBpReading() })
            is OmronBleResult.BondingRequired ->
                SourceReadResult.ConnectionRequired(result.reason)
            is OmronBleResult.AuthFailed ->
                SourceReadResult.ConnectionRequired(result.reason)
            is OmronBleResult.Timeout -> SourceReadResult.Timeout(result.state)
            is OmronBleResult.Disconnected -> SourceReadResult.Disconnected(result.state)
            is OmronBleResult.Failed -> SourceReadResult.Failed(result.reason)
        }
    }

    companion object {
        const val TYPE_ID = "omron-ble"

        /**
         * Getter-backed, not const: an R field is not a compile-time
         * constant in this setup, and a const companion val initialized
         * from one compiles cross-class references to a getter that is
         * never generated (NoSuchMethodError at runtime).
         */
        val DISPLAY_NAME_RES: Int get() = R.string.bp_source_type_omron_ble

        /** The M7 carries two device user slots and reads one at a time. */
        val CAPABILITIES = SourceCapabilities(
            userAttribution = UserAttribution.SLOTS,
            supportedSyncModes = setOf(SyncMode.HISTORY, SyncMode.CURRENT),
            requiresPairingKey = true
        )
    }
}

/**
 * [BleBpSourceType] of the Omron M7 Intelli IT: wires the adapter onto the
 * real omron/ble stack - the same pairing-key file and read entry point the
 * pre-sources ui/omron pipeline uses, so a monitor paired before the
 * redesign keeps working through the seam. The discovered [device] is what
 * the add-source flow selected; the current Omron stack finds the monitor
 * itself (service-UUID scan), so the device is not yet used to target a
 * specific one - the standard 0x1810 driver (task xlg.9) will.
 */
object OmronBleSourceType : BleBpSourceType {

    override val typeId: String = OmronBleSource.TYPE_ID
    override val displayNameRes: Int = OmronBleSource.DISPLAY_NAME_RES
    override val capabilities: SourceCapabilities = OmronBleSource.CAPABILITIES

    override fun createFromDevice(context: Context, device: DiscoveredBleDevice): BpSource {
        val keyStore = OmronKeyStore(
            crypto = KeystoreAesPairingKeyCrypto(),
            persistence = FileKeyPersistence(File(context.filesDir, PAIRING_KEY_FILE_NAME))
        )
        return OmronBleSource(
            keyProvider = { keyStore.getOrCreate() },
            bleRead = { key, userSlot, fullRead ->
                OmronBleClient.readNewRecords(context, key, userSlot, fullRead)
            }
        )
    }

    /**
     * Persists a user-entered 16-byte pairing key (task
     * tefal2garmin-app-redesign-v3-xlg.3): writes the encrypted key file
     * [createFromDevice] reads via [OmronKeyStore.getOrCreate], so the
     * first read of a source added through the add-source flow unlocks the
     * monitor with exactly this key. Returns the stored-key identifier
     * kept in ConnectedSource.pairingKeyRef.
     */
    fun storePairingKey(context: Context, key: ByteArray): String {
        FileKeyPersistence(File(context.filesDir, PAIRING_KEY_FILE_NAME))
            .save(KeystoreAesPairingKeyCrypto().encrypt(key))
        return PAIRING_KEY_FILE_NAME
    }

    /**
     * Pairing-key file in filesDir - the same name the pre-sources
     * OmronViewModelFactory wiring uses, so the stored key is shared.
     */
    private const val PAIRING_KEY_FILE_NAME = "omron-pairing-key"
}

/** Domain record -> seam record: a plain field-by-field copy. */
private fun BloodPressureReading.toBpReading() = BpReading(
    timestampEpochSec = timestampEpochSec,
    systolic = systolic,
    diastolic = diastolic,
    pulse = pulse,
    mov = mov,
    ihb = ihb,
    userSlot = userSlot
)
