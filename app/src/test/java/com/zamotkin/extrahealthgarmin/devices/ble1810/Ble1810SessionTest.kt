package com.zamotkin.extrahealthgarmin.devices.ble1810

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * [Ble1810Session] tests (task tefal2garmin-app-redesign-v3-xlg.9) over a
 * scripted [Ble1810Transport] fake: the indication burst lands whole, the
 * idle timeout and the status-19 self-disconnect both end the session
 * normally, quirk frames (sentinel, malformed, byte-identical re-delivery)
 * are absorbed, and clock-less records get unique arrival seconds. The
 * re-read/no-duplicate half of the story lives with the journal in
 * [Ble1810SourceTest].
 */
class Ble1810SessionTest {

    /** Scripted transport: a queue of payloads, then the scripted session end. */
    private class FakeTransport(
        var feature: Int? = null,
        var deviceDescription: String? = null,
        var endWithDisconnect: Boolean = false,
        var connectError: Exception? = null
    ) : Ble1810Transport {
        val queue = ArrayDeque<ByteArray>()
        val awaitTimeouts = mutableListOf<Long>()

        override suspend fun connectAndSubscribe() {
            connectError?.let { throw it }
        }

        override suspend fun readFeature(): Int? = feature
        override suspend fun readDeviceDescription(): String? = deviceDescription

        override suspend fun awaitNextMeasurement(idleTimeoutMs: Long): Ble1810NextMeasurement {
            awaitTimeouts += idleTimeoutMs
            return when {
                queue.isNotEmpty() -> Ble1810NextMeasurement.Indication(queue.removeFirst())
                endWithDisconnect -> Ble1810NextMeasurement.DeviceDisconnected
                else -> Ble1810NextMeasurement.IdleTimeout
            }
        }

        override fun close() = Unit
    }

    // ------------------------------------------------------------------ //
    //  Frame builders (see Ble1810MeasurementParserTest for the layout)   //
    // ------------------------------------------------------------------ //

    private fun sfloat(mantissa: Int, exponent: Int): ByteArray {
        val e = if (exponent < 0) exponent + 16 else exponent
        val word = ((e and 0x0F) shl 12) or (mantissa and 0x0FFF)
        return byteArrayOf((word and 0xFF).toByte(), (word shr 8).toByte())
    }

    private fun timestampedFrame(systolic: Int, pulsePresent: Boolean = true): ByteArray {
        // Flags 0x06 (timestamp + pulse) - a clock-carrying record. The
        // 7-byte Date Time field: year u16, month, day, hours, minutes,
        // seconds (GSS Table 3.144) = 2026-10-25 08:30:00.
        val dateTime = byteArrayOf(
            0xEA.toByte(), 0x07, 10, 25, 8, 30, 0
        )
        return byteArrayOf(0x06) + sfloat(systolic, 0) + sfloat(80, 0) + sfloat(102, 0) +
            dateTime + if (pulsePresent) sfloat(64, 0) else ByteArray(0)
    }

    private fun clocklessFrame(systolic: Int): ByteArray {
        // Flags 0x00 - a record without device clock time.
        return byteArrayOf(0x00) + sfloat(systolic, 0) + sfloat(80, 0) + sfloat(102, 0)
    }

    private fun sentinelFrame(): ByteArray =
        byteArrayOf(0x00, 0xFF.toByte(), 0x07, 0xFF.toByte(), 0x07, 0xFF.toByte(), 0x07)

    private val deviceTimestampSec =
        LocalDateTime.of(2026, 10, 25, 8, 30, 0).toEpochSecond(ZoneOffset.UTC)

    // ------------------------------------------------------------------ //
    //  Burst + session ends                                               //
    // ------------------------------------------------------------------ //

    @Test
    fun `indication burst collects every record in order`() = runTest {
        val transport = FakeTransport()
        transport.queue += timestampedFrame(120)
        transport.queue += timestampedFrame(125)
        transport.queue += timestampedFrame(130)

        val result = Ble1810Session(transport, firstMeasurementTimeoutMs = 30_000, idleTimeoutMs = 10_000).read()

        assertEquals(3, result.measurements.size)
        assertEquals(listOf(120, 125, 130), result.measurements.map { it.systolicMmHg })
        assertEquals(0, result.skippedSentinels)
        assertEquals(0, result.malformedFrames)
        assertEquals(0, result.duplicateFrames)
        assertEquals(Ble1810Session.End.IDLE_TIMEOUT, result.endedBy)
    }

    @Test
    fun `idle timeout ends the session with the collected records`() = runTest {
        val transport = FakeTransport()
        transport.queue += clocklessFrame(118)

        val result = Ble1810Session(transport, idleTimeoutMs = 10_000).read()

        assertEquals(1, result.measurements.size)
        assertEquals(Ble1810Session.End.IDLE_TIMEOUT, result.endedBy)
    }

    @Test
    fun `status-19 self-disconnect is the normal session end`() = runTest {
        val transport = FakeTransport(endWithDisconnect = true)
        transport.queue += timestampedFrame(122)

        val result = Ble1810Session(transport).read()

        assertEquals(1, result.measurements.size)
        assertEquals(Ble1810Session.End.DEVICE_DISCONNECTED, result.endedBy)
    }

    @Test
    fun `the first-indication window is wider than the inter-indication window`() = runTest {
        val transport = FakeTransport()
        transport.queue += timestampedFrame(120)
        transport.queue += timestampedFrame(121)

        Ble1810Session(
            transport,
            firstMeasurementTimeoutMs = 30_000,
            idleTimeoutMs = 10_000
        ).read()

        // await #1 (nothing received yet) uses the button-press window,
        // awaits #2 and #3 use the tight idle window.
        assertEquals(listOf(30_000L, 10_000L, 10_000L), transport.awaitTimeouts)
    }

    // ------------------------------------------------------------------ //
    //  Quirk frames                                                       //
    // ------------------------------------------------------------------ //

    @Test
    fun `sentinel frames are skipped and counted, never stored`() = runTest {
        val transport = FakeTransport()
        transport.queue += sentinelFrame()
        transport.queue += timestampedFrame(120)
        transport.queue += sentinelFrame()

        val result = Ble1810Session(transport).read()

        assertEquals(1, result.measurements.size)
        assertEquals(2, result.skippedSentinels)
    }

    @Test
    fun `malformed frames are counted and do not end the session`() = runTest {
        val transport = FakeTransport()
        transport.queue += byteArrayOf(0x00, 0x7D) // cut short mid-SFLOAT
        transport.queue += timestampedFrame(120)
        transport.queue += byteArrayOf(0x20, 0, 0, 0, 0, 0, 0) // reserved flag bit

        val result = Ble1810Session(transport).read()

        assertEquals(1, result.measurements.size)
        assertEquals(2, result.malformedFrames)
        assertEquals(Ble1810Session.End.IDLE_TIMEOUT, result.endedBy)
    }

    @Test
    fun `byte-identical consecutive re-delivery is stored once`() = runTest {
        val transport = FakeTransport()
        val frame = clocklessFrame(120)
        transport.queue += frame
        transport.queue += frame.copyOf()
        transport.queue += clocklessFrame(125)

        val result = Ble1810Session(transport).read()

        assertEquals(listOf(120, 125), result.measurements.map { it.systolicMmHg })
        assertEquals(1, result.duplicateFrames)
    }

    // ------------------------------------------------------------------ //
    //  Arrival-time attribution                                           //
    // ------------------------------------------------------------------ //

    @Test
    fun `clock-less records get unique monotonic arrival seconds`() = runTest {
        val transport = FakeTransport()
        transport.queue += clocklessFrame(120)
        transport.queue += clocklessFrame(125)
        transport.queue += clocklessFrame(130)

        // The clock stands still at second 1000 - the session must still
        // give every record its own second (the storage unique index).
        val result = Ble1810Session(transport, clock = { 1000L }).read()

        assertEquals(listOf(1000L, 1001L, 1002L), result.measurements.map { it.timestampEpochSec })
        assertTrue(result.measurements.none { it.timestampFromDeviceClock })
    }

    @Test
    fun `device-clock records keep their device timestamp`() = runTest {
        val transport = FakeTransport()
        transport.queue += timestampedFrame(120)

        val result = Ble1810Session(transport, clock = { 1000L }).read()

        assertEquals(deviceTimestampSec, result.measurements[0].timestampEpochSec)
        assertTrue(result.measurements[0].timestampFromDeviceClock)
    }

    // ------------------------------------------------------------------ //
    //  Diagnostics + failures                                             //
    // ------------------------------------------------------------------ //

    @Test
    fun `feature and device description come through as diagnostics`() = runTest {
        val transport = FakeTransport(feature = 0x0004, deviceDescription = "boso medicus CE6674")
        transport.queue += timestampedFrame(120)

        val result = Ble1810Session(transport).read()

        assertEquals(0x0004, result.feature)
        assertEquals("boso medicus CE6674", result.deviceDescription)
    }

    @Test
    fun `a connect failure propagates to the caller`() = runTest {
        val transport = FakeTransport(connectError = Ble1810BondingException("pairing did not complete"))

        var thrown: Exception? = null
        try {
            Ble1810Session(transport).read()
        } catch (e: Exception) {
            thrown = e
        }

        assertTrue(thrown is Ble1810BondingException)
    }
}
