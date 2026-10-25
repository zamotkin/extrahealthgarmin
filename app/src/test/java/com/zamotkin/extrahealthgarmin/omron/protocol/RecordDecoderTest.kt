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

    /**
     * Packs `value` into bits [first..last] in omblepy numbering (bit k = integer
     * bit 127-k of the 128-bit little-endian record; bit 127 = LSB of byte 0).
     * Mirrors the production formula — the literal real-capture vectors are the
     * only anchor against a shared off-by-one; keep them alongside this helper.
     */
    private fun setBits(record: ByteArray, first: Int, last: Int, value: Int) {
        for (k in 0..(last - first)) {
            if (value shr k and 1 == 1) {
                val intBit = record.size * 8 - 1 - last + k
                record[intBit / 8] = (record[intBit / 8].toInt() or (1 shl (intBit % 8))).toByte()
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
    fun `decodes_real_captured_hem7361t_alru_records`() {
        // Raw EEPROM records from a btsnoop capture of a real HEM-7361T-ALRU
        // (epic tefal2garmin-ipo, 2026-10-24). Both were confirmed against the
        // M7 display AND the standard 2A35 indication of the same measurements
        // — the strongest possible ground truth for the bit layout.
        val fresh = decoder.decode(
            "70423c1a162bb5120000f10000000100".hexToBytes(), userSlot = 1,
        )!!
        assertEquals(137, fresh.systolic)
        assertEquals(66, fresh.diastolic)
        assertEquals(60, fresh.pulse)
        org.junit.Assert.assertFalse(fresh.mov)
        org.junit.Assert.assertFalse(fresh.ihb)
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 22, 10, 53).toEpochSecond(ZoneOffset.UTC),
            fresh.timestampEpochSec,
        )

        val prior = decoder.decode(
            "6f58451a102b1a1b0001f00000008700".hexToBytes(), userSlot = 1,
        )!!
        assertEquals(136, prior.systolic)
        assertEquals(88, prior.diastolic)
        assertEquals(69, prior.pulse)
        org.junit.Assert.assertFalse(prior.mov)
        org.junit.Assert.assertFalse(prior.ihb)
        assertEquals(1, prior.userSlot)
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 16, 44, 26).toEpochSecond(ZoneOffset.UTC),
            prior.timestampEpochSec,
        )
    }

    /**
     * Hand-mutated bytes of the real fresh record — literal vectors that anchor
     * the numbering WITHOUT the [setBits] packing helper (which mirrors the
     * production formula and would pass a shared off-by-one).
     */
    @Test
    fun `mutated_capture_vectors_pin_flags_and_second_clamping`() {
        // byte 5 |= 0xC0: mov (bit 7) and ihb (bit 6) set, all other fields unchanged
        val withFlags = decoder.decode(
            "70423c1a16ebb5120000f10000000100".hexToBytes(), userSlot = 2,
        )!!
        assertEquals(137, withFlags.systolic)
        assertEquals(66, withFlags.diastolic)
        assertTrue(withFlags.mov)
        assertTrue(withFlags.ihb)
        assertEquals(2, withFlags.userSlot)
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 22, 10, 53).toEpochSecond(ZoneOffset.UTC),
            withFlags.timestampEpochSec,
        )

        // byte 6 low 6 bits = 0b111111: raw second 63, clamped to 59 (spec §6);
        // minute (byte 6 bits 6-7 + byte 7 bits 0-3) stays 10
        val second63 = decoder.decode(
            "70423c1a162bbf120000f10000000100".hexToBytes(), userSlot = 1,
        )!!
        assertEquals(
            LocalDateTime.of(2026, 10, 24, 22, 10, 59).toEpochSecond(ZoneOffset.UTC),
            second63.timestampEpochSec,
        )
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i ->
            ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
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
