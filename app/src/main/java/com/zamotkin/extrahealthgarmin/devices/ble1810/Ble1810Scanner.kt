package com.zamotkin.extrahealthgarmin.devices.ble1810

import com.zamotkin.extrahealthgarmin.devices.api.DiscoveredBleDevice
import java.util.UUID

/**
 * One BLE sighting as the pure scanner sees it — an advertisement or a
 * bonded device — with the service UUIDs needed to decide membership.
 *
 * @property name advertised or cached device name; null when neither is known
 * @property address MAC address (the persisted handle to the device)
 * @property rssi signal strength, dBm; null when there is no live
 *   advertisement (a bonded device surfaced from the system list)
 * @property serviceUuids the service UUIDs of the sighting (advertisement
 *   service list, or the bond record's cached UUIDs)
 */
data class Ble1810Advertisement(
    val name: String?,
    val address: String,
    val rssi: Int?,
    val serviceUuids: List<UUID>
)

/**
 * Platform seam of the 0x1810 scanner — the glue (in
 * [Ble1810GattConnection.kt], the only file touching `android.bluetooth`)
 * bridges the Android scanner and bonded-device list onto it, so the
 * filtering/dedup logic is plain JVM and unit-testable.
 */
interface Ble1810ScanTransport {

    /** System-bonded devices with their cached service UUIDs. */
    fun bondedAdvertisements(): List<Ble1810Advertisement>

    /**
     * Starts the service-UUID-filtered LE scan; every failure — including
     * "could not start" — is reported through [onScanFailed], never thrown.
     *
     * @param onAdvertisement every scan result, already unfiltered — the
     *   pure [Ble1810Scanner] owns membership and dedup
     * @param onScanFailed the scan died or could not start; the reason is
     *   human-readable ("BLE scan failed with error 2")
     */
    fun startScan(
        onAdvertisement: (Ble1810Advertisement) -> Unit,
        onScanFailed: (reason: String) -> Unit
    )

    /** Stops the scan; safe to call when none is running. */
    fun stopScan()
}

/**
 * The 0x1810 discovery scanner (docs/ble-1810-protocol.md §4.1 step 1):
 * bonded monitors surface immediately (their cached UUIDs carry 0x1810),
 * then live advertisements filter by the Blood Pressure service UUID —
 * never by name, the same rule as the Omron scan. Each address is
 * surfaced exactly once (first sighting wins), so a bonded device's scan
 * echo never duplicates it.
 *
 * The results are [DiscoveredBleDevice]s — the add-source flow's currency,
 * accepted by [com.zamotkin.extrahealthgarmin.devices.api.BleBpSourceType.createFromDevice].
 * A sighting without a name keeps the address as the name (still selectable);
 * a bonded device without an advertisement reports rssi 0.
 */
class Ble1810Scanner(private val transport: Ble1810ScanTransport) : AutoCloseable {

    private val surfacedAddresses = HashSet<String>()

    /**
     * Starts discovery: bonded matches first, then the live scan. Bonded
     * matches surface synchronously, before the scan even starts, so a
     * paired monitor is selectable without waking it up. Call [close] to
     * stop; starting twice on one instance surfaces nothing new.
     */
    fun start(
        onDevice: (DiscoveredBleDevice) -> Unit,
        onScanFailed: (reason: String) -> Unit
    ) {
        for (advertisement in transport.bondedAdvertisements()) {
            offer(advertisement, onDevice)
        }
        transport.startScan(
            onAdvertisement = { offer(it, onDevice) },
            onScanFailed = onScanFailed
        )
    }

    /** Membership + once-per-address; surfaces the device or drops silently. */
    private fun offer(advertisement: Ble1810Advertisement, onDevice: (DiscoveredBleDevice) -> Unit) {
        if (Ble1810Protocol.BLOOD_PRESSURE_SERVICE_UUID !in advertisement.serviceUuids) return
        if (!surfacedAddresses.add(advertisement.address)) return
        onDevice(
            DiscoveredBleDevice(
                name = advertisement.name ?: advertisement.address,
                address = advertisement.address,
                rssi = advertisement.rssi ?: 0
            )
        )
    }

    /** Stops the scan; the surfaced-address memory stays (one scanner per flow). */
    override fun close() {
        transport.stopScan()
    }
}
