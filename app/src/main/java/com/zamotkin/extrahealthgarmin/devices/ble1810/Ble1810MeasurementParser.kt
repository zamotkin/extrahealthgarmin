package com.zamotkin.extrahealthgarmin.devices.ble1810

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Measurement Status field of a Blood Pressure Measurement (spec §3.5,
 * GSS Table 3.55). The booleans mirror the Omron MOV/IHB badge style;
 * [raw] keeps the full 16-bit word as sent — it is part of the
 * fingerprint identity (docs/ble-1810-protocol.md §4.2).
 *
 * Pure JVM — no Android types.
 */
data class Ble1810MeasurementStatus(
    /** Bit 0 — "body movement detected during measurement". Maps to BpReading.mov. */
    val bodyMovement: Boolean,
    /** Bit 1 — "cuff too loose". */
    val cuffTooLoose: Boolean,
    /** Bit 2 — "irregular pulse detected". Maps to BpReading.ihb. */
    val irregularPulse: Boolean,
    /** Bit 5 — "improper measurement position". */
    val improperPosition: Boolean,
    /** Bits 0–15 exactly as the device sent them (bits 3–4 carry the pulse-rate range). */
    val raw: Int
)

/**
 * One decoded Blood Pressure Measurement (0x2A35) — the driver-internal
 * shape before the [com.zamotkin.extrahealthgarmin.devices.api.BpReading]
 * mapping. Pressures are always normalized to mmHg (spec §3.1: Flags bit 0
 * selects mmHg/kPa on the wire; the driver stores mmHg only).
 *
 * @property systolicMmHg systolic pressure, mmHg
 * @property diastolicMmHg diastolic pressure, mmHg
 * @property mapMmHg mean arterial pressure, mmHg; null when the device sent
 *   a special value in the MAP slot (the record itself stays valid)
 * @property pulseBpm heart rate, beats per minute; null when Flags bit 2 is
 *   off or the device sent a special value in the pulse slot
 * @property timestampEpochSec record time: the device clock time when the
 *   flags carried one, the arrival second otherwise. Until the session
 *   stamps the arrival time (timestamp-less records) this is a 0 placeholder.
 * @property timestampFromDeviceClock true when [timestampEpochSec] came from
 *   the Time Stamp field; false when the device sent no usable clock time
 *   and the record must be deduplicated by fingerprint, not by time
 *   (docs/ble-1810-protocol.md §4.2)
 * @property userId User ID byte when Flags bit 3 is set — informational
 *   only; attribution is always the app owner (epic R4, spec §3.6)
 * @property status Measurement Status bits (null field when Flags bit 4 is off)
 */
data class Ble1810Measurement(
    val systolicMmHg: Int,
    val diastolicMmHg: Int,
    val mapMmHg: Int?,
    val pulseBpm: Int?,
    val timestampEpochSec: Long,
    val timestampFromDeviceClock: Boolean,
    val userId: Int?,
    val status: Ble1810MeasurementStatus?
)

/** Terminal outcome of parsing one 0x2A35 indication payload. */
sealed class Ble1810ParseResult {

    /** A usable measurement. */
    data class Parsed(val measurement: Ble1810Measurement) : Ble1810ParseResult()

    /**
     * The device reported a FAILED reading: the NaN sentinel (SFLOAT 0x07FF)
     * in Systolic or Diastolic. Skipped and counted — never stored
     * (docs/ble-1810-protocol.md §4.2; boso sends these for display errors
     * with NO status bits set, so absence of status flags proves nothing).
     */
    object SkippedSentinel : Ble1810ParseResult()

    /** The frame does not conform to the layout; [reason] names the defect. */
    data class Malformed(val reason: String) : Ble1810ParseResult()
}

/**
 * Parses Blood Pressure Measurement (0x2A35) indications per
 * docs/ble-1810-protocol.md §3 (GSS Tables 3.52–3.55, SIG-verified layout):
 *
 * `Flags(1) Systolic(2) Diastolic(2) MAP(2) [TimeStamp(7)] [PulseRate(2)]
 * [UserID(1)] [MeasurementStatus(2)]` — all multi-byte fields little-endian,
 * optional fields present exactly when their Flags bit is set.
 *
 * Never throws: the NaN sentinel in a primary field lands in
 * [Ble1810ParseResult.SkippedSentinel], every layout defect lands in
 * [Ble1810ParseResult.Malformed].
 *
 * Pure JVM — no Android types.
 */
object Ble1810MeasurementParser {

    /** One parsed SFLOAT (medfloat16) word: a number, or an unusable special value (spec §3.2). */
    private sealed class SFloat {
        data class Number(val value: Double) : SFloat()
        object NaN : SFloat()

        /** +Inf (0x07FE), -Inf (0x0802), NRes (0x0800) or RFU (0x0801). */
        data class Unavailable(val word: Int) : SFloat()
    }

    private class MalformedFrameException(reason: String) : Exception(reason)

    /**
     * Decodes one little-endian medfloat16 word: 12-bit two's-complement
     * mantissa (bits 0–11) x 10^(4-bit two's-complement exponent, bits 12–15),
     * with the five IEEE 11073-20601 special values pinned by GSS Table 2.1.
     */
    private fun decodeSFloat(lowByte: Int, highByte: Int): SFloat {
        val word = (highByte and 0xFF) shl 8 or (lowByte and 0xFF)
        return when (word) {
            Ble1810Protocol.SFLOAT_NAN_WORD -> SFloat.NaN
            0x07FE, 0x0802, 0x0800, 0x0801 -> SFloat.Unavailable(word)
            else -> {
                var mantissa = word and 0x0FFF
                var exponent = word shr 12
                if (exponent >= 0x08) exponent -= 0x10
                if (mantissa >= 0x0800) mantissa -= 0x1000
                SFloat.Number(mantissa * 10.0.pow(exponent))
            }
        }
    }

    /** Wire pressure to mmHg, normalizing kPa when the units bit is set. */
    private fun toMmHg(value: Double, kPa: Boolean): Int {
        val mmHg = if (kPa) value * Ble1810Protocol.MMHG_PER_KPA else value
        if (mmHg < 0) throw MalformedFrameException("negative pressure: $mmHg")
        return mmHg.roundToInt()
    }

    /** Parses the 7-byte Date Time field (spec §3.4, GSS Table 3.144); null when the content is unusable. */
    private fun decodeTimestamp(payload: ByteArray, offset: Int): Long? {
        val year = (payload[offset + 1].toInt() and 0xFF) shl 8 or (payload[offset].toInt() and 0xFF)
        val month = payload[offset + 2].toInt() and 0xFF
        val day = payload[offset + 3].toInt() and 0xFF
        val hours = payload[offset + 4].toInt() and 0xFF
        val minutes = payload[offset + 5].toInt() and 0xFF
        val seconds = payload[offset + 6].toInt() and 0xFF
        if (year < 1582 || year > 9999) return null // 0 and out-of-range mean "not known"
        if (month !in 1..12 || day !in 1..31) return null
        if (hours > 23 || minutes > 59 || seconds > 59) return null
        // The device sends wall-clock digits without a zone; UTC keeps them
        // stable — the same convention the Omron record decoder uses.
        return try {
            LocalDateTime.of(year, month, day, hours, minutes, seconds).toEpochSecond(ZoneOffset.UTC)
        } catch (e: java.time.DateTimeException) {
            null // e.g. February 31 — a broken device clock, not a broken record
        }
    }

    /** Parses one indication payload; never throws. */
    fun parse(payload: ByteArray): Ble1810ParseResult = try {
        parseOrThrow(payload)
    } catch (e: MalformedFrameException) {
        Ble1810ParseResult.Malformed(e.message ?: "malformed frame")
    }

    private fun parseOrThrow(payload: ByteArray): Ble1810ParseResult {
        if (payload.isEmpty()) throw MalformedFrameException("empty frame")
        val flags = payload[0].toInt() and 0xFF
        if (flags and 0xE0 != 0) {
            throw MalformedFrameException("reserved Flags bits 5-7 set: 0x${flags.toString(16)}")
        }
        val kPa = flags and 0x01 != 0
        val hasTimestamp = flags and 0x02 != 0
        val hasPulse = flags and 0x04 != 0
        val hasUserId = flags and 0x08 != 0
        val hasStatus = flags and 0x10 != 0

        // 1 (flags) + 3 mandatory SFLOATs; every flagged field adds its exact size.
        val expectedSize = 7 + (if (hasTimestamp) 7 else 0) + (if (hasPulse) 2 else 0) +
            (if (hasUserId) 1 else 0) + (if (hasStatus) 2 else 0)
        if (payload.size != expectedSize) {
            throw MalformedFrameException(
                "frame is ${payload.size} bytes, flags 0x${flags.toString(16)} announce $expectedSize"
            )
        }

        // Primary fields (spec §4.2): NaN = the device's own "failed reading"
        // marker — skip the record; any other special value is a broken frame.
        val systolic = decodeSFloat(payload[1].toInt(), payload[2].toInt())
        val diastolic = decodeSFloat(payload[3].toInt(), payload[4].toInt())
        if (systolic is SFloat.NaN || diastolic is SFloat.NaN) return Ble1810ParseResult.SkippedSentinel
        if (systolic !is SFloat.Number || diastolic !is SFloat.Number) {
            val word = (systolic as? SFloat.Unavailable)?.word ?: (diastolic as SFloat.Unavailable).word
            throw MalformedFrameException("primary field carries the unusable SFLOAT 0x${word.toString(16)}")
        }

        // Diagnostic fields: a special value costs only the field, never the record.
        val map = when (val field = decodeSFloat(payload[5].toInt(), payload[6].toInt())) {
            is SFloat.Number -> toMmHg(field.value, kPa)
            else -> null
        }

        var offset = 7
        val timestamp = if (hasTimestamp) decodeTimestamp(payload, offset).also { offset += 7 } else null
        val pulse = if (hasPulse) {
            (decodeSFloat(payload[offset].toInt(), payload[offset + 1].toInt()) as? SFloat.Number)
                ?.let { it.value.roundToInt() }
                .also { offset += 2 }
        } else {
            null
        }
        val userId = if (hasUserId) (payload[offset].toInt() and 0xFF).also { offset += 1 } else null
        val status = if (hasStatus) {
            val raw = (payload[offset + 1].toInt() and 0xFF) shl 8 or (payload[offset].toInt() and 0xFF)
            Ble1810MeasurementStatus(
                bodyMovement = raw and 0x0001 != 0,
                cuffTooLoose = raw and 0x0002 != 0,
                irregularPulse = raw and 0x0004 != 0,
                improperPosition = raw and 0x0020 != 0,
                raw = raw
            ).also { offset += 2 }
        } else {
            null
        }

        return Ble1810ParseResult.Parsed(
            Ble1810Measurement(
                systolicMmHg = toMmHg(systolic.value, kPa),
                diastolicMmHg = toMmHg(diastolic.value, kPa),
                mapMmHg = map,
                pulseBpm = pulse,
                timestampEpochSec = timestamp ?: 0,
                timestampFromDeviceClock = timestamp != null,
                userId = userId,
                status = status
            )
        )
    }
}
