package com.zamotkin.extrahealthgarmin.omron.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [SessionFrameCodec] — vectors from docs/omron-protocol.md §4 (commit bbef2fe)
 * and the omblepy reference (pinned in the spec's Source Baseline). The CRC is a plain XOR
 * with a whole-frame == 0 invariant.
 */
class SessionFrameCodecTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `session_start_frame_matches_spec_vector_byte_for_byte`() {
        // docs/omron-protocol.md §4.1, omblepy startTransmission
        assertArrayEquals(
            hex("08 00 00 00 00 10 00 18"),
            SessionFrameCodec.SESSION_START_FRAME,
        )
    }

    @Test
    fun `session_end_frame_matches_spec_vector_byte_for_byte`() {
        // docs/omron-protocol.md §4.1, omblepy endTransmission
        assertArrayEquals(
            hex("08 0f 00 00 00 00 00 07"),
            SessionFrameCodec.SESSION_END_FRAME,
        )
    }

    @Test
    fun `read_command_for_0260_size_26_matches_omblepy_example`() {
        // omblepy _readBlockEeprom example: read 0x26 bytes from address 0x0260
        val frame = SessionFrameCodec.buildReadCommand(address = 0x0260, size = 0x26)
        assertArrayEquals(hex("08 01 00 02 60 26 00 4d"), frame)
    }

    @Test
    fun `write_command_roundtrip_parse_recovers_type_address_and_data`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val frame = SessionFrameCodec.buildWriteCommand(0x0054, payload)
        val parsed = SessionFrameCodec.parseFrame(frame)

        assertEquals(OmronProtocol.TYPE_WRITE, parsed.type)
        assertEquals(0x0054, parsed.address)
        assertEquals(5, parsed.dataLength)
        assertArrayEquals(payload, parsed.data)
    }

    @Test
    fun `whole_frame_xor_invariant_holds_for_random_roundtrips`() {
        // 100 seeded pseudo-random frames; XOR of all bytes must be exactly 0.
        var seed = 42L
        fun next(): Int {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return (seed ushr 33).toInt()
        }

        repeat(100) {
            val size = 1 + (next() and 0x1F)            // 1..32 data bytes
            val address = next() and 0xFFFF
            val data = ByteArray(size) { next().toByte() }
            val frame = SessionFrameCodec.buildWriteCommand(address, data)
            val xor = frame.fold(0) { acc, b -> acc xor b.toInt() }
            assertEquals("XOR invariant broken at iteration $it", 0, xor)
            val parsed = SessionFrameCodec.parseFrame(frame)
            assertEquals(size, parsed.dataLength)
            assertArrayEquals(data, parsed.data)
        }
    }

    @Test
    fun `crc_corruption_is_detected`() {
        val frame = SessionFrameCodec.SESSION_START_FRAME.copyOf()
        frame[3] = (frame[3].toInt() xor 0x01).toByte()   // flip one bit outside the CRC byte
        try {
            SessionFrameCodec.parseFrame(frame)
            fail("expected FrameCrcException for corrupted frame")
        } catch (e: FrameCrcException) {
            // expected — BLE layer maps this onto a retry
        }
    }

    @Test
    fun `session_end_response_exposes_status_byte`() {
        // N = 6 + 1 + 2 = 9: 09 8f 00 00 00 01 <status 00> 00 <crc>
        // xor of 09^8f^01^00 = 0x87
        val response = hex("09 8f 00 00 00 01 00 00 87")
        val parsed = SessionFrameCodec.parseFrame(response)
        assertEquals(OmronProtocol.RESP_SESSION_END, parsed.type)
        assertEquals(0, parsed.status)
    }

    @Test
    fun `nonzero_session_end_status_is_preserved`() {
        // status 0x05: xor of 09^8f^01^05 = 0x82
        val response = hex("09 8f 00 00 00 01 05 00 82")
        val parsed = SessionFrameCodec.parseFrame(response)
        assertEquals(5, parsed.status)
    }

    @Test
    fun `oversized_data_throws`() {
        try {
            SessionFrameCodec.buildWriteCommand(0x0000, ByteArray(0xF8))
            fail("expected IllegalArgumentException for data > 0xEF bytes")
        } catch (e: IllegalArgumentException) {
            // expected — size byte is 8 bits: 6 header + data + 2 crc
        }
    }
}
