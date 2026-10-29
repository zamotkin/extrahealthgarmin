package com.zamotkin.extrahealthgarmin.devices.ble1810

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * [Ble1810MeasurementParser] tests (task tefal2garmin-app-redesign-v3-xlg.9):
 * SIG-spec frames - the A&D UA-651BLE worked example (docs/ble-1810-protocol.md
 * 3.7), kPa-vs-mmHg normalization (the unit bug catcher), timestamp
 * present/absent (the wrong-record-time catcher), status flags, the 0x07FF
 * sentinel, and malformed frames. All frames are hand-built from the GSS
 * Tables 3.52-3.55 layout.
 */
class Ble1810MeasurementParserTest {

    /** Little-endian medfloat16 word from mantissa and (possibly negative) exponent. */
    private fun sfloat(mantissa: Int, exponent: Int): ByteArray {
        val e = if (exponent < 0) exponent + 16 else exponent
        val word = ((e and 0x0F) shl 12) or (mantissa and 0x0FFF)
        return byteArrayOf((word and 0xFF).toByte(), (word shr 8).toByte())
    }

    private fun mmhg(value: Int) = sfloat(value, 0)

    private fun nan() = byteArrayOf(0xFF.toByte(), 0x07) // 0x07FF little-endian

    private fun timestamp(
        year: Int, month: Int, day: Int, hours: Int, minutes: Int, seconds: Int
    ): ByteArray = byteArrayOf(
        (year and 0xFF).toByte(), (year shr 8).toByte(),
        month.toByte(), day.toByte(), hours.toByte(), minutes.toByte(), seconds.toByte()
    )

    private fun frame(
        kPa: Boolean = false,
        systolic: ByteArray = mmhg(125),
        diastolic: ByteArray = mmhg(80),
        map: ByteArray = mmhg(102),
        ts: ByteArray? = null,
        pulse: ByteArray? = null,
        userId: Int? = null,
        status: ByteArray? = null
    ): ByteArray {
        val flags = (if (kPa) 0x01 else 0) +
            (if (ts != null) 0x02 else 0) +
            (if (pulse != null) 0x04 else 0) +
            (if (userId != null) 0x08 else 0) +
            (if (status != null) 0x10 else 0)
        return byteArrayOf(flags.toByte()) + systolic + diastolic + map +
            (ts ?: ByteArray(0)) + (pulse ?: ByteArray(0)) +
            (userId?.let { byteArrayOf(it.toByte()) } ?: ByteArray(0)) + (status ?: ByteArray(0))
    }

    @Test
    fun `A-and-D worked example decodes field by field`() {
        // docs/ble-1810-protocol.md 3.7: 18 bytes, community-verified on a
        // real UA-651BLE - mmHg, Time Stamp + Pulse Rate + Measurement
        // Status present, no User ID.
        val payload = byteArrayOf(
            0x16, 0x7D, 0x00, 0x50, 0x00, 0x66, 0x00,
            0xE1.toByte(), 0x07, 0x01, 0x10, 0x0C, 0x27, 0x36,
            0x40, 0x00, 0x00, 0x00
        )

        val result = Ble1810MeasurementParser.parse(payload)

        assertTrue(result is Ble1810ParseResult.Parsed)
        val measurement = (result as Ble1810ParseResult.Parsed).measurement
        assertEquals(125, measurement.systolicMmHg)
        assertEquals(80, measurement.diastolicMmHg)
        assertEquals(102, measurement.mapMmHg)
        assertEquals(64, measurement.pulseBpm)
        assertEquals(
            LocalDateTime.of(2017, 1, 16, 12, 39, 54).toEpochSecond(ZoneOffset.UTC),
            measurement.timestampEpochSec
        )
        assertTrue(measurement.timestampFromDeviceClock)
        assertNull(measurement.userId)
        assertEquals(0, measurement.status?.raw)
        assertFalse(measurement.status?.bodyMovement ?: true)
    }

    @Test
    fun `kPa frames normalize every pressure to mmHg`() {
        // 16.0 kPa -> 120 mmHg, 10.7 kPa -> 80 mmHg, 14.4 kPa -> 108 mmHg
        // (mantissa x 10^-1; 1 kPa = 7.50062 mmHg). A parser that forgets
        // the units bit reports 16/10/14 and fails here.
        val result = Ble1810MeasurementParser.parse(
            frame(
                kPa = true,
                systolic = sfloat(160, -1),
                diastolic = sfloat(107, -1),
                map = sfloat(144, -1)
            )
        )

        val measurement = (result as Ble1810ParseResult.Parsed).measurement
        assertEquals(120, measurement.systolicMmHg)
        assertEquals(80, measurement.diastolicMmHg)
        assertEquals(108, measurement.mapMmHg)
    }

    @Test
    fun `mmHg frames with a fractional mantissa round to whole mmHg`() {
        // mantissa 1205 x 10^-1 = 120.5 -> 121
        val result = Ble1810MeasurementParser.parse(frame(systolic = sfloat(1205, -1)))
        assertEquals(121, (result as Ble1810ParseResult.Parsed).measurement.systolicMmHg)
    }

    @Test
    fun `timestamp flag absent means no device clock time`() {
        val result = Ble1810MeasurementParser.parse(frame(pulse = mmhg(64)))

        val measurement = (result as Ble1810ParseResult.Parsed).measurement
        assertFalse(measurement.timestampFromDeviceClock)
        assertEquals(0L, measurement.timestampEpochSec) // placeholder until the session stamps the arrival
    }

    @Test
    fun `year zero and impossible dates degrade to read-time attribution`() {
        val unknownYear = Ble1810MeasurementParser.parse(frame(ts = timestamp(0, 1, 16, 12, 39, 54)))
        val february31 = Ble1810MeasurementParser.parse(frame(ts = timestamp(2026, 2, 31, 8, 0, 0)))

        for (result in listOf(unknownYear, february31)) {
            val measurement = (result as Ble1810ParseResult.Parsed).measurement
            assertFalse(measurement.timestampFromDeviceClock)
        }
    }

    @Test
    fun `measurement status bits map to booleans and the raw word survives`() {
        // 0x0027 = bits 0,1,2,5: body movement, cuff too loose, irregular
        // pulse, improper position.
        val status = byteArrayOf(0x27, 0x00)
        val result = Ble1810MeasurementParser.parse(frame(status = status))

        val measurement = (result as Ble1810ParseResult.Parsed).measurement
        val bits = measurement.status
        assertEquals(0x27, bits?.raw)
        assertEquals(true, bits?.bodyMovement)
        assertEquals(true, bits?.cuffTooLoose)
        assertEquals(true, bits?.irregularPulse)
        assertEquals(true, bits?.improperPosition)
    }

    @Test
    fun `user id is informational and 0xFF means unknown user`() {
        val result = Ble1810MeasurementParser.parse(frame(userId = 0xFF))

        val measurement = (result as Ble1810ParseResult.Parsed).measurement
        assertEquals(0xFF, measurement.userId)
    }

    @Test
    fun `NaN sentinel in systolic or diastolic is a skipped failed reading`() {
        val systolicNaN = Ble1810MeasurementParser.parse(frame(systolic = nan()))
        val diastolicNaN = Ble1810MeasurementParser.parse(frame(diastolic = nan()))

        assertTrue(systolicNaN is Ble1810ParseResult.SkippedSentinel)
        assertTrue(diastolicNaN is Ble1810ParseResult.SkippedSentinel)
    }

    @Test
    fun `NaN sentinel in MAP or pulse degrades the field, not the record`() {
        val result = Ble1810MeasurementParser.parse(frame(map = nan(), pulse = nan()))

        val measurement = (result as Ble1810ParseResult.Parsed).measurement
        assertNull(measurement.mapMmHg)
        assertNull(measurement.pulseBpm)
        assertEquals(125, measurement.systolicMmHg)
    }

    @Test
    fun `other special SFLOAT values in a primary field are malformed`() {
        // 0x07FE = +Inf (GSS Table 2.1)
        val positiveInfinity = byteArrayOf(0xFE.toByte(), 0x07)
        val result = Ble1810MeasurementParser.parse(frame(systolic = positiveInfinity))

        assertTrue(result is Ble1810ParseResult.Malformed)
    }

    @Test
    fun `empty and short frames are malformed, never a crash`() {
        assertTrue(Ble1810MeasurementParser.parse(ByteArray(0)) is Ble1810ParseResult.Malformed)

        val sixBytes = byteArrayOf(0x00, 0x7D, 0x00, 0x50, 0x00, 0x66)
        assertTrue(Ble1810MeasurementParser.parse(sixBytes) is Ble1810ParseResult.Malformed)
    }

    @Test
    fun `frame shorter than its flags announce is malformed`() {
        // Flags promise a timestamp (bit 1) but the payload ends after MAP.
        val cutShort = byteArrayOf(0x02, 0x7D, 0x00, 0x50, 0x00, 0x66, 0x00, 0xE1.toByte(), 0x07)
        assertTrue(Ble1810MeasurementParser.parse(cutShort) is Ble1810ParseResult.Malformed)
    }

    @Test
    fun `trailing bytes beyond the announced layout are malformed`() {
        val oneTooMany = frame(pulse = mmhg(64)) + byteArrayOf(0x00)
        assertTrue(Ble1810MeasurementParser.parse(oneTooMany) is Ble1810ParseResult.Malformed)
    }

    @Test
    fun `reserved flag bits are malformed`() {
        // Flags bit 5 set (RFU bits shall be 0, GSS Table 3.53).
        val reserved = byteArrayOf(0x20) + mmhg(125) + mmhg(80) + mmhg(102)
        val result = Ble1810MeasurementParser.parse(reserved)
        assertTrue(result is Ble1810ParseResult.Malformed)
        assertTrue((result as Ble1810ParseResult.Malformed).reason.contains("reserved"))
    }

    @Test
    fun `negative pressure is malformed`() {
        // mantissa -5, exponent 0
        val negative = sfloat(-5, 0)
        assertTrue(Ble1810MeasurementParser.parse(frame(systolic = negative)) is Ble1810ParseResult.Malformed)
    }
}
