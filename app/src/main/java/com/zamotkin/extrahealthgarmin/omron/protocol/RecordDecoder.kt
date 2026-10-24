package com.zamotkin.extrahealthgarmin.omron.protocol

import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Thrown when a 16-byte EEPROM record cannot be decoded into a valid reading
 * (bad size, impossible date). Callers skip such records with a log —
 * docs/omron-protocol.md decision log #6.
 */
class RecordFormatException(message: String) : Exception(message)

/**
 * Decodes one 16-byte EEPROM record into a [BloodPressureReading]
 * per docs/omron-protocol.md §6.
 *
 * Layout (omblepy bit numbering — bit k = integer bit 127-k of the 128-bit
 * little-endian record, so systolic 120-127 IS byte 0):
 * minute 68-73, second 74-79 (clamped to 59 — the device may report up to 63),
 * mov 80, ihb 81, month 82-85, day 86-90, hour 91-95, year 98-103 (+2000),
 * pulse 104-111, diastolic 112-119, systolic 120-127 (+25).
 *
 * Pure JVM — no Android types. Stateless, thread-safe.
 */
class RecordDecoder {

    fun decode(record: ByteArray, userSlot: Int): BloodPressureReading? {
        if (record.size != OmronProtocol.RECORD_BYTE_SIZE) {
            throw RecordFormatException("record size ${record.size}, expected 16")
        }
        if (record.all { it == 0xFF.toByte() }) return null   // empty ring-buffer slot

        val minute = bits(record, OmronProtocol.BIT_MINUTE_FIRST, OmronProtocol.BIT_MINUTE_LAST)
        val second = minOf(
            bits(record, OmronProtocol.BIT_SECOND_FIRST, OmronProtocol.BIT_SECOND_LAST),
            OmronProtocol.SECOND_MAX_VALID,
        )
        val mov = bits(record, OmronProtocol.BIT_MOV, OmronProtocol.BIT_MOV) == 1
        val ihb = bits(record, OmronProtocol.BIT_IHB, OmronProtocol.BIT_IHB) == 1
        val month = bits(record, OmronProtocol.BIT_MONTH_FIRST, OmronProtocol.BIT_MONTH_LAST)
        val day = bits(record, OmronProtocol.BIT_DAY_FIRST, OmronProtocol.BIT_DAY_LAST)
        val hour = bits(record, OmronProtocol.BIT_HOUR_FIRST, OmronProtocol.BIT_HOUR_LAST)
        val year = bits(record, OmronProtocol.BIT_YEAR_FIRST, OmronProtocol.BIT_YEAR_LAST) +
            OmronProtocol.YEAR_OFFSET
        val pulse = bits(record, OmronProtocol.BIT_PULSE_FIRST, OmronProtocol.BIT_PULSE_LAST)
        val diastolic = bits(record, OmronProtocol.BIT_DIASTOLIC_FIRST, OmronProtocol.BIT_DIASTOLIC_LAST)
        val systolic = bits(record, OmronProtocol.BIT_SYSTOLIC_FIRST, OmronProtocol.BIT_SYSTOLIC_LAST) +
            OmronProtocol.SYSTOLIC_OFFSET

        val timestamp = try {
            LocalDateTime.of(year, month, day, hour, minute, second)
                .toEpochSecond(ZoneOffset.UTC)
        } catch (e: DateTimeException) {
            throw RecordFormatException(
                "invalid record date ${"%04d".format(year)}-${"%02d".format(month)}-" +
                    "${"%02d".format(day)} ${"%02d".format(hour)}:${"%02d".format(minute)}:" +
                    "${"%02d".format(second)}",
            )
        }

        return BloodPressureReading(
            timestampEpochSec = timestamp,
            systolic = systolic,
            diastolic = diastolic,
            pulse = pulse,
            mov = mov,
            ihb = ihb,
            userSlot = userSlot,
        )
    }

    /**
     * Extracts bits [first..last] (inclusive) in omblepy numbering: the record
     * is a 128-bit little-endian integer and omblepy's bit k is integer bit
     * (127 - k) — bit 127 is the LSB of byte 0, bit 0 the MSB of byte 15.
     * The v0.9 spec transcribed this as plain LSB-first numbering, which put
     * every field in the wrong byte; corrected against real HEM-7361T-ALRU
     * captures (epic tefal2garmin-ipo, 2026-10-24).
     */
    private fun bits(record: ByteArray, first: Int, last: Int): Int {
        val width = last - first + 1
        var result = 0
        for (k in 0 until width) {
            val intBit = record.size * 8 - 1 - last + k
            if (record[intBit / 8].toInt() and (1 shl (intBit % 8)) != 0) {
                result = result or (1 shl k)
            }
        }
        return result
    }
}
