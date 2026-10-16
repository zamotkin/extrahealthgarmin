package com.zamotkin.extrahealthgarmin.omron.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Tests for [OmronSession] over a scripted [OmronTransport] fake.
 * All expected frames are hand-computed from docs/omron-protocol.md §3-§5.
 */
class OmronSessionTest {

    private val key = ByteArray(16) { 0x5A }

    /** Scriptable transport: each queued response is returned for the next write. */
    private class FakeTransport(
        val unlockResponses: ArrayDeque<ByteArray>,
        val commandResponses: ArrayDeque<SessionFrameCodec.ParsedFrame>,
    ) : OmronTransport {
        val unlockWrites = mutableListOf<ByteArray>()
        val commandWrites = mutableListOf<ByteArray>()

        override suspend fun writeUnlock(payload: ByteArray): ByteArray {
            unlockWrites.add(payload.copyOf())
            return unlockResponses.removeFirst()
        }

        override suspend fun writeCommand(frame: ByteArray): SessionFrameCodec.ParsedFrame {
            commandWrites.add(frame.copyOf())
            return commandResponses.removeFirst()
        }
    }

    /** Hand-computed 16-byte record: 2025-06-15 12:45:30, sys raw 120 -> 145, dia 80, pulse 70. */
    private fun validRecord(): ByteArray {
        val r = ByteArray(16)
        fun setBits(first: Int, last: Int, value: Int) {
            for (i in first..last) {
                if (value shr (i - first) and 1 == 1) r[i / 8] = (r[i / 8].toInt() or (1 shl (i % 8))).toByte()
            }
        }
        setBits(68, 73, 45); setBits(74, 79, 30); setBits(82, 85, 6); setBits(86, 90, 15)
        setBits(91, 95, 12); setBits(98, 103, 25); setBits(104, 111, 70)
        setBits(112, 119, 80); setBits(120, 127, 120)
        return r
    }

    private fun okFrame(type: Int, address: Int, data: ByteArray = ByteArray(0)) =
        SessionFrameCodec.parseFrame(
            SessionFrameCodec.buildWriteCommand(address, data).let { f ->
                // re-tag the type byte: buildWriteCommand stamps TYPE_WRITE, rewrite for responses
                val copy = f.copyOf()
                copy[1] = (type shr 8).toByte(); copy[2] = type.toByte()
                copy[copy.size - 1] = 0 // fix crc below
                var xor = 0
                for (i in 0 until copy.size - 1) xor = xor xor (copy[i].toInt() and 0xFF)
                copy[copy.size - 1] = xor.toByte()
                copy
            },
        )

    /** Settings read response: 16-byte unread section (spec §5): user1 slot=1, unread=1. */
    private fun settingsResponse(): SessionFrameCodec.ParsedFrame {
        val data = byteArrayOf(
            0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,   // counters
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,   // rest of the section
        )
        return dataResponse(OmronProtocol.RESP_READ, OmronProtocol.SETTINGS_READ_ADDRESS, data)
    }

    /** Builds a valid data-carrying response frame of the given type/address (CRC fixed). */
    private fun dataResponse(type: Int, address: Int, data: ByteArray): SessionFrameCodec.ParsedFrame {
        val frame = SessionFrameCodec.buildWriteCommand(address, data)
        val copy = frame.copyOf()
        copy[1] = (type shr 8).toByte(); copy[2] = type.toByte()
        var xor = 0
        for (i in 0 until copy.size - 1) xor = xor xor (copy[i].toInt() and 0xFF)
        copy[copy.size - 1] = xor.toByte()
        return SessionFrameCodec.parseFrame(copy)
    }

    @Test
    fun `happy_path_full_sequence_and_decoded_reading`() = runTest {
        val record = validRecord()
        val transport = FakeTransport(
            unlockResponses = ArrayDeque(listOf(byteArrayOf(0x81.toByte(), 0x00))),
            commandResponses = ArrayDeque(
                listOf(
                    okFrame(OmronProtocol.RESP_SESSION_START, 0x0010),   // start
                    settingsResponse(),                                   // settings @0x0010
                    okFrame(OmronProtocol.RESP_READ, 0x0098, record),    // 1 record read
                    okFrame(OmronProtocol.RESP_WRITE, 0x0054),           // unread reset write-back
                    okFrame(OmronProtocol.RESP_SESSION_END, 0x0000, byteArrayOf(0x00)), // end, status 0
                ),
            ),
        )

        val result = OmronSession.readOut(transport, key, userSlot = 1)

        assertEquals(1, result.readings.size)
        assertEquals(0, result.skippedRecords)
        val expected = LocalDateTime.of(2025, 6, 15, 12, 45, 30).toEpochSecond(ZoneOffset.UTC)
        assertEquals(expected, result.readings[0].timestampEpochSec)
        assertEquals(145, result.readings[0].systolic)

        // Sequence assertions: unlock, start, settings read, record read, reset write, end
        assertEquals(1, transport.unlockWrites.size)
        assertArrayEquals(UnlockCodec.unlock(key), transport.unlockWrites[0])
        assertEquals(5, transport.commandWrites.size)
        assertArrayEquals(SessionFrameCodec.SESSION_START_FRAME, transport.commandWrites[0])
        // settings read targets 0x0010
        assertEquals(OmronProtocol.TYPE_READ, frameType(transport.commandWrites[1]))
        assertEquals(0x0010, frameAddress(transport.commandWrites[1]))
        // record read targets USER1 base 0x0098 with size 0x10
        assertEquals(0x0098, frameAddress(transport.commandWrites[2]))
        assertEquals(0x10, transport.commandWrites[2][5].toInt() and 0xFF)
        // unread reset write-back targets 0x0054 + 0x00 (NOT 0x0010 — spec discrepancy #7)
        assertEquals(OmronProtocol.TYPE_WRITE, frameType(transport.commandWrites[3]))
        assertEquals(0x0054, frameAddress(transport.commandWrites[3]))
        // reset payload: full 16-byte section back, only counters [4:8] changed to LE 0x8000 x2
        val resetPayload = transport.commandWrites[3].copyOfRange(6, 22)
        assertEquals(16, resetPayload.size)
        assertEquals(0x00.toByte(), resetPayload[4])
        assertEquals(0x80.toByte(), resetPayload[5])
        assertEquals(0x00.toByte(), resetPayload[6])
        assertEquals(0x80.toByte(), resetPayload[7])
        // last-written slots preserved verbatim
        assertEquals(0x01.toByte(), resetPayload[1])
        assertArrayEquals(SessionFrameCodec.SESSION_END_FRAME, transport.commandWrites[4])
    }

    @Test
    fun `unlock_rejection_aborts_before_session_start`() = runTest {
        val transport = FakeTransport(
            unlockResponses = ArrayDeque(listOf(byteArrayOf(0x99.toByte(), 0x99.toByte()))),
            commandResponses = ArrayDeque(),
        )
        try {
            OmronSession.readOut(transport, key, userSlot = 1)
            fail("expected UnlockRejectedException for rejected unlock")
        } catch (e: UnlockRejectedException) {
            // typed signal (task tefal2garmin-983): the BLE layer maps this onto AuthFailed
            assertTrue(e.message!!.startsWith("unlock rejected"))
            assertTrue(e is OmronSessionException)
        }
        assertEquals(0, transport.commandWrites.size)
    }

    @Test
    fun `invalid_user_slot_rejected`() = runTest {
        val transport = FakeTransport(ArrayDeque(), ArrayDeque())
        for (slot in listOf(0, 3)) {
            try {
                OmronSession.readOut(transport, key, userSlot = slot)
                fail("expected IllegalArgumentException for userSlot=$slot")
            } catch (e: IllegalArgumentException) {
                // expected — prevents cross-user medical data contamination
            }
        }
        assertTrue(transport.commandWrites.isEmpty())
    }

    @Test
    fun `mid_read_failure_still_attempts_session_end`() = runTest {
        val transport = object : OmronTransport {
            val commandWrites = mutableListOf<ByteArray>()
            var writes = 0
            override suspend fun writeUnlock(payload: ByteArray): ByteArray =
                byteArrayOf(0x81.toByte(), 0x00)
            override suspend fun writeCommand(frame: ByteArray): SessionFrameCodec.ParsedFrame {
                commandWrites.add(frame.copyOf())
                writes++
                return when (writes) {
                    1 -> okFrame(OmronProtocol.RESP_SESSION_START, 0x0010)
                    2 -> settingsResponse()
                    3 -> throw FrameCrcException("simulated mid-read corruption")
                    else -> okFrame(OmronProtocol.RESP_SESSION_END, 0x0000, byteArrayOf(0x00))
                }
            }
        }
        try {
            OmronSession.readOut(transport, key, userSlot = 1)
            fail("expected OmronSessionException for mid-read failure")
        } catch (e: OmronSessionException) {
            // expected
        }
        // finally: session end attempted even after the mid-read failure
        assertArrayEquals(SessionFrameCodec.SESSION_END_FRAME, transport.commandWrites.last())
    }

    @Test
    fun `nonzero_end_status_throws`() = runTest {
        val record = validRecord()
        val transport = FakeTransport(
            unlockResponses = ArrayDeque(listOf(byteArrayOf(0x81.toByte(), 0x00))),
            commandResponses = ArrayDeque(
                listOf(
                    okFrame(OmronProtocol.RESP_SESSION_START, 0x0010),
                    settingsResponse(),
                    okFrame(OmronProtocol.RESP_READ, 0x0098, record),
                    okFrame(OmronProtocol.RESP_WRITE, 0x0054),
                    okFrame(OmronProtocol.RESP_SESSION_END, 0x0000, byteArrayOf(0x05)),
                ),
            ),
        )
        try {
            OmronSession.readOut(transport, key, userSlot = 1)
            fail("expected OmronSessionException for device end-status 5")
        } catch (e: OmronSessionException) {
            // expected
        }
    }

    @Test
    fun `empty_ring_reads_no_records_but_resets_counter`() = runTest {
        val emptySettings = dataResponse(
            OmronProtocol.RESP_READ, OmronProtocol.SETTINGS_READ_ADDRESS, ByteArray(16),
        )
        val transport = FakeTransport(
            unlockResponses = ArrayDeque(listOf(byteArrayOf(0x81.toByte(), 0x00))),
            commandResponses = ArrayDeque(
                listOf(
                    okFrame(OmronProtocol.RESP_SESSION_START, 0x0010),
                    emptySettings,
                    okFrame(OmronProtocol.RESP_WRITE, 0x0054),
                    okFrame(OmronProtocol.RESP_SESSION_END, 0x0000, byteArrayOf(0x00)),
                ),
            ),
        )
        val result = OmronSession.readOut(transport, key, userSlot = 1)
        assertEquals(0, result.readings.size)
        // start, settings, reset, end — no record reads
        assertEquals(4, transport.commandWrites.size)
    }

    private fun frameType(frame: ByteArray): Int =
        (frame[1].toInt() and 0xFF) shl 8 or (frame[2].toInt() and 0xFF)

    private fun frameAddress(frame: ByteArray): Int =
        (frame[3].toInt() and 0xFF) shl 8 or (frame[4].toInt() and 0xFF)
}
