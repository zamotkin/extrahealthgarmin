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
 * Layout (bit 0 = LSB of byte 0, little-endian whole-record integer):
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
     * Extracts bits [first..last] (inclusive) using spec bit numbering:
     * the record is a little-endian 128-bit integer, bit k lives in byte k/8 at bit k%8.
     */
    private fun bits(record: ByteArray, first: Int, last: Int): Int {
        var result = 0
        for (i in last downTo first) {
            result = result shl 1
            if (record[i / 8].toInt() and (1 shl (i % 8)) != 0) result = result or 1
        }
        return result
    }
}
