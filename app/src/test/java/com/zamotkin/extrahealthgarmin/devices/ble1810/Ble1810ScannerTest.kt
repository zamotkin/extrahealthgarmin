package com.zamotkin.extrahealthgarmin.devices.ble1810

import com.zamotkin.extrahealthgarmin.devices.api.DiscoveredBleDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * [Ble1810Scanner] tests (task tefal2garmin-app-redesign-v3-xlg.9) over a
 * faked [Ble1810ScanTransport]: non-0x1810 advertisements are filtered out,
 * bonded monitors surface immediately (before the scan starts), each
 * address surfaces exactly once, and scan failures propagate to the caller.
 */
class Ble1810ScannerTest {

    /**
     * Scriptable transport recording every lifecycle step into [events],
     * shared with the test so the ordering of bonded surfacing vs. the scan
     * start is provable.
     */
    private class FakeScanTransport(
        var bonded: List<Ble1810Advertisement> = emptyList(),
        private val events: MutableList<String> = mutableListOf()
    ) : Ble1810ScanTransport {

        var advertisementSink: ((Ble1810Advertisement) -> Unit)? = null
        var scanFailedSink: ((String) -> Unit)? = null
        var scanRunning = false

        override fun bondedAdvertisements(): List<Ble1810Advertisement> {
            events += "bonded"
            return bonded
        }

        override fun startScan(
            onAdvertisement: (Ble1810Advertisement) -> Unit,
            onScanFailed: (reason: String) -> Unit
        ) {
            events += "scan-start"
            scanRunning = true
            advertisementSink = onAdvertisement
            scanFailedSink = onScanFailed
        }

        override fun stopScan() {
            events += "scan-stop"
            scanRunning = false
        }
    }

    private val service = Ble1810Protocol.BLOOD_PRESSURE_SERVICE_UUID
    private val otherService: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb") // Heart Rate

    private fun advertisement(
        name: String?,
        address: String,
        rssi: Int?,
        vararg uuids: UUID
    ) = Ble1810Advertisement(name, address, rssi, uuids.toList())

    // ------------------------------------------------------------------ //
    //  Membership filtering                                               //
    // ------------------------------------------------------------------ //

    @Test
    fun `non-1810 advertisements are filtered out`() {
        val transport = FakeScanTransport()
        val scanner = Ble1810Scanner(transport)
        val surfaced = mutableListOf<DiscoveredBleDevice>()
        scanner.start(onDevice = { surfaced += it }, onScanFailed = { })

        transport.advertisementSink?.invoke(advertisement("Heart Belt", "AA:01", -60, otherService))
        transport.advertisementSink?.invoke(advertisement("No Services", "AA:02", -55))

        assertTrue(surfaced.isEmpty())
    }

    @Test
    fun `1810 advertisements surface as discovered devices`() {
        val transport = FakeScanTransport()
        val scanner = Ble1810Scanner(transport)
        val surfaced = mutableListOf<DiscoveredBleDevice>()
        scanner.start(onDevice = { surfaced += it }, onScanFailed = { })

        transport.advertisementSink?.invoke(advertisement("BM 59", "AA:03", -71, service))

        assertEquals(listOf(DiscoveredBleDevice("BM 59", "AA:03", -71)), surfaced)
    }

    // ------------------------------------------------------------------ //
    //  Bonded devices + once-per-address                                  //
    // ------------------------------------------------------------------ //

    @Test
    fun `bonded 1810 devices surface immediately, before the scan starts`() {
        val events = mutableListOf<String>()
        val transport = FakeScanTransport(
            bonded = listOf(
                advertisement("BM 59", "AA:04", null, service),
                advertisement("Old Scale", "AA:05", null, otherService)
            ),
            events = events
        )
        val scanner = Ble1810Scanner(transport)

        scanner.start(onDevice = { events += "device:${it.address}" }, onScanFailed = { })

        // The bonded monitor surfaced before startScan was even called; the
        // non-0x1810 bonded device did not surface at all.
        assertEquals(listOf("bonded", "device:AA:04", "scan-start"), events)
    }

    @Test
    fun `each address surfaces exactly once`() {
        val transport = FakeScanTransport(
            bonded = listOf(advertisement("BM 59", "AA:06", null, service))
        )
        val scanner = Ble1810Scanner(transport)
        val surfaced = mutableListOf<DiscoveredBleDevice>()
        scanner.start(onDevice = { surfaced += it }, onScanFailed = { })

        // The scan echoes the bonded device and repeats a live sighting.
        transport.advertisementSink?.invoke(advertisement("BM 59", "AA:06", -80, service))
        transport.advertisementSink?.invoke(advertisement("BM 59", "AA:06", -75, service))
        transport.advertisementSink?.invoke(advertisement("X4 Smart", "AA:07", -60, service))

        assertEquals(listOf("AA:06", "AA:07"), surfaced.map { it.address })
    }

    @Test
    fun `a nameless sighting keeps the address as name and bonded rssi is zero`() {
        val transport = FakeScanTransport(
            bonded = listOf(advertisement(null, "AA:08", null, service))
        )
        val scanner = Ble1810Scanner(transport)
        val surfaced = mutableListOf<DiscoveredBleDevice>()
        scanner.start(onDevice = { surfaced += it }, onScanFailed = { })

        assertEquals(DiscoveredBleDevice("AA:08", "AA:08", 0), surfaced.single())
    }

    // ------------------------------------------------------------------ //
    //  Failure + lifecycle                                                 //
    // ------------------------------------------------------------------ //

    @Test
    fun `scan failures surface through onScanFailed`() {
        val transport = FakeScanTransport()
        val scanner = Ble1810Scanner(transport)
        val failures = mutableListOf<String>()
        scanner.start(onDevice = { }, onScanFailed = { failures += it })

        transport.scanFailedSink?.invoke("BLE scan failed with error 2")

        assertEquals(listOf("BLE scan failed with error 2"), failures)
    }

    @Test
    fun `close stops the scan`() {
        val events = mutableListOf<String>()
        val transport = FakeScanTransport(events = events)
        val scanner = Ble1810Scanner(transport)
        scanner.start(onDevice = { }, onScanFailed = { })
        assertTrue(transport.scanRunning)

        scanner.close()

        assertFalse(transport.scanRunning)
        assertEquals("scan-stop", events.last())
    }
}
