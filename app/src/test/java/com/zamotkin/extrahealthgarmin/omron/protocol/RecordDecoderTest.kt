package com.zamotkin.extrahealthgarmin.omron.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Tests for [RecordDecoder] — vectors built by hand against the bit table in
 * docs/omron-protocol.md §6 (spec commit bbef2fe). Every medical value in these
 * vectors is hand-computed; the decoder must not drift by a single bit.
 */
class RecordDecoderTest {

    private val decoder = RecordDecoder()

    /** Packs `value` into bits [first..last] using spec bit numbering (bit 0 = LSB of byte 0). */
    private fun setBits(record: ByteArray, first: Int, last: Int, value: Int) {
        for (i in first..last) {
            if (value shr (i - first) and 1 == 1) {
                record[i / 8] = (record[i / 8].toInt() or (1 shl (i % 8))).toByte()
            }
        }
    }

    private fun record(
        minute: Int = 45, second: Int = 30, month: Int = 6, day: Int = 15,
        hour: Int = 12, year: Int = 25, sysRaw: Int = 120, dia: Int = 80, pulse: Int = 70,
        mov: Int = 0, ihb: Int = 0,
    ): ByteArray {
        val r = ByteArray(16)
        setBits(r, 68, 73, minute)
        setBits(r, 74, 79, second)
        setBits(r, 80, 80, mov)
        setBits(r, 81, 81, ihb)
        setBits(r, 82, 85, month)
        setBits(r, 86, 90, day)
        setBits(r, 91, 95, hour)
        setBits(r, 98, 103, year)
        setBits(r, 104, 111, pulse)
        setBits(r, 112, 119, dia)
        setBits(r, 120, 127, sysRaw)
        return r
    }

    @Test
    fun `full_record_decodes_all_fields`() {
        val r = record(second = 30, mov = 1, ihb = 1)
        val reading = decoder.decode(r, userSlot = 1)!!

        assertEquals(145, reading.systolic)   // 120 + 25 offset (spec §6)
        assertEquals(80, reading.diastolic)
        assertEquals(70, reading.pulse)
        assertTrue(reading.mov)
        assertTrue(reading.ihb)
        assertEquals(1, reading.userSlot)
        val expected = LocalDateTime.of(2025, 6, 15, 12, 45, 30).toEpochSecond(ZoneOffset.UTC)
        assertEquals(expected, reading.timestampEpochSec)
    }

    @Test
    fun `second_63_is_clamped_to_59`() {
        val r = record(second = 63)
        val reading = decoder.decode(r, userSlot = 2)!!
        val expected = LocalDateTime.of(2025, 6, 15, 12, 45, 59).toEpochSecond(ZoneOffset.UTC)
        assertEquals(expected, reading.timestampEpochSec)
        assertEquals(2, reading.userSlot)
    }

    @Test
    fun `all_ff_slot_is_empty_and_returns_null`() {
        assertEquals(null, decoder.decode(ByteArray(16) { 0xFF.toByte() }, userSlot = 1))
    }

    @Test
    fun `invalid_month_throws_record_format_exception`() {
        try {
            decoder.decode(record(month = 13), userSlot = 1)
            fail("expected RecordFormatException for month=13")
        } catch (e: RecordFormatException) {
            // expected — caller skips the record with a log (spec decision log #6)
        }
    }

    @Test
    fun `invalid_day_throws_record_format_exception`() {
        try {
            decoder.decode(record(day = 32), userSlot = 1)
            fail("expected RecordFormatException for day=32")
        } catch (e: RecordFormatException) {
            // expected
        }
    }

    @Test
    fun `wrong_record_size_throws`() {
        try {
            decoder.decode(ByteArray(12), userSlot = 1)
            fail("expected RecordFormatException for 12-byte record")
        } catch (e: RecordFormatException) {
            // expected
        }
    }

    @Test
    fun `sys_offset_omission_would_fail_this_vector`() {
        // Guard against dropping the +25 offset: raw 25 must decode to 50, not 25.
        val reading = decoder.decode(record(sysRaw = 25), userSlot = 1)!!
        assertEquals(50, reading.systolic)
    }

    @Test
    fun `timestamp_fields_do_not_bleed_into_neighbors`() {
        // Boundary values that would corrupt neighbors if bit ranges were off by one.
        val r = record(minute = 59, second = 59, month = 12, day = 31, hour = 23, year = 63)
        val reading = decoder.decode(r, userSlot = 1)!!
        val expected = LocalDateTime.of(2063, 12, 31, 23, 59, 59).toEpochSecond(ZoneOffset.UTC)
        assertEquals(expected, reading.timestampEpochSec)
    }
}
