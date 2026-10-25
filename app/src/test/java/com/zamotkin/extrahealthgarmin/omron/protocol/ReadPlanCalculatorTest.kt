package com.zamotkin.extrahealthgarmin.omron.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [ReadPlanCalculator] — ring-buffer geometry per
 * docs/omron-protocol.md §5.1 (omblepy calcRingBufferRecordReadLocations and
 * resetUnreadRecordsCounter). Vectors hand-computed.
 */
class ReadPlanCalculatorTest {

    private val calc = ReadPlanCalculator()

    /**
     * Builds the 8-byte unread-info section: per user i, lastWrittenSlot in the FIRST byte
     * of field [2i:2i+2], unread count in the FIRST byte of field [2i+4:2i+6]
     * (omblepy bit numbering, spec §5.1 — settled by the 2026-10-25 capture).
     */
    private fun unreadInfo(user1Slot: Int, user1Unread: Int, user2Slot: Int, user2Unread: Int): ByteArray {
        val b = ByteArray(8)
        b[0] = user1Slot.toByte()
        b[4] = user1Unread.toByte()
        b[2] = user2Slot.toByte()    // field [2:4] first byte = index 2
        b[6] = user2Unread.toByte()  // field [6:8] first byte = index 6
        return b
    }

    /**
     * Regression on REAL wire bytes (2026-10-25 capture, HEM-7361T-ALRU): after one
     * new USER2 measurement the unread-info section read back 00400040 00800100 —
     * USER2 unread = 01 in the FIRST byte. The plan must read exactly one record.
     * The 0x8000-reset state (00400040 00800080) must plan NOTHING — reading the
     * SECOND byte instead used to produce the bogus unread=128 mega-read.
     */
    @Test
    fun `real_capture_counter_bytes_plan_exactly_one_read_and_nothing_after_reset`() {
        val beforeReset = "0040004000800100".hexToBytes()
        val plan = calc.planForUser(beforeReset, userIdx = 1)
        // lastWritten=0 < unread=1 -> wrap branch: empty head + one tail read at slot 99
        assertEquals(2, plan.size)
        assertEquals(0, plan[0].size)
        assertEquals(0x06D8 + 99 * 0x10, plan[1].address)   // slot 99 held the fresh record
        assertEquals(0x10, plan[1].size)

        val afterReset = "0040004000800080".hexToBytes()
        assertEquals(0, calc.planForUser(afterReset, userIdx = 1).size)
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i ->
            ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
        }

    @Test
    fun `non_wrap_plan_is_single_read`() {
        // slot=50, unread=2 -> one read: base + 48*16 = 0x0098 + 0x300 = 0x0398, size 32
        val plan = calc.planForUser(unreadInfo(50, 2, 0, 0), userIdx = 0)
        assertEquals(1, plan.size)
        assertEquals(0x0098 + 48 * 0x10, plan[0].address)
        assertEquals(2 * 0x10, plan[0].size)
    }

    @Test
    fun `wrap_plan_is_two_reads`() {
        // slot=3, unread=5 (ring of 100): newest 3 at buffer start, oldest 2 at the tail.
        // read 1: base, size 3*16=48; read 2: base + (100 + 3 - 5)*16 = base + 98*16, size 2*16=32
        val plan = calc.planForUser(unreadInfo(3, 5, 0, 0), userIdx = 0)
        assertEquals(2, plan.size)
        assertEquals(0x0098, plan[0].address)
        assertEquals(48, plan[0].size)
        assertEquals(0x0098 + 98 * 0x10, plan[1].address)
        assertEquals(32, plan[1].size)
    }

    @Test
    fun `user2_plan_uses_user2_base`() {
        // USER2 base 0x06D8, slot=10, unread=4, no wrap
        val plan = calc.planForUser(unreadInfo(0, 0, 10, 4), userIdx = 1)
        assertEquals(1, plan.size)
        assertEquals(0x06D8 + 6 * 0x10, plan[0].address)
        assertEquals(4 * 0x10, plan[0].size)
    }

    @Test
    fun `empty_ring_yields_no_reads`() {
        assertEquals(0, calc.planForUser(unreadInfo(7, 0, 9, 0), userIdx = 0).size)
    }

    @Test
    fun `reset_writes_8000_into_unread_fields_and_keeps_last_written_slots`() {
        // omblepy resetUnreadRecordsCounter: bytes [4:8] = LE(0x8000) x2; bytes [0:4] untouched
        val section = byteArrayOf(0xAA.toByte(), 0x32, 0xBB.toByte(), 0x0A, 0xCC.toByte(), 0x05, 0xDD.toByte(), 0x63)
        val reset = calc.resetUnreadCounters(section)
        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), 0x32, 0xBB.toByte(), 0x0A, 0x00, 0x80.toByte(), 0x00, 0x80.toByte()),
            reset,
        )
    }

    @Test
    fun `full_ring_reads_everything`() {
        // slot=0, unread=100 -> lastWrittenSlot < unread: two reads covering all 100 slots
        val plan = calc.planForUser(unreadInfo(0, 100, 0, 0), userIdx = 0)
        assertEquals(2, plan.size)
        assertEquals(0, plan[0].size)                        // slot 0 * 16 = nothing before head
        assertEquals(0x0098, plan[1].address)
        assertEquals(100 * 0x10, plan[1].size)
    }
}
