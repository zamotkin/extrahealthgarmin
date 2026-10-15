package com.zamotkin.extrahealthgarmin.omron.ble

import com.zamotkin.extrahealthgarmin.omron.protocol.OmronProtocol
import com.zamotkin.extrahealthgarmin.omron.protocol.SessionFrameCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [ChannelChunker] — the 16-byte channel chunking of spec §4.5.
 * All frames are built with [SessionFrameCodec] so size bytes are real.
 */
class ChannelChunkerTest {

    @Test
    fun `eight byte frame yields a single chunk`() {
        val chunks = ChannelChunker.split(SessionFrameCodec.SESSION_START_FRAME)

        assertEquals(1, chunks.size)
        assertArrayEquals(SessionFrameCodec.SESSION_START_FRAME, chunks[0])
    }

    @Test
    fun `forty byte frame yields three chunks of sixteen sixteen eight`() {
        // write command with 32 data bytes -> N = 32 + 8 = 40
        val frame = SessionFrameCodec.buildWriteCommand(0x0054, ByteArray(32))

        val chunks = ChannelChunker.split(frame)

        assertEquals(3, chunks.size)
        assertEquals(OmronProtocol.CHANNEL_CHUNK_BYTES, chunks[0].size)
        assertEquals(OmronProtocol.CHANNEL_CHUNK_BYTES, chunks[1].size)
        assertEquals(8, chunks[2].size)
        // concatenation must reproduce the original frame exactly
        assertArrayEquals(frame, chunks[0] + chunks[1] + chunks[2])
    }

    @Test
    fun `reassemble truncates padding beyond the size byte`() {
        // a 24-byte frame whose final RX chunk is padded to the 16-byte channel unit
        val frame = SessionFrameCodec.buildWriteCommand(0x0054, ByteArray(16))
        val paddedTail = frame.copyOfRange(16, frame.size) + ByteArray(8) { 0xAA.toByte() }

        val reassembled = ChannelChunker.reassemble(
            listOf(frame.copyOfRange(0, 16), paddedTail),
        )

        assertEquals(frame.size, reassembled.size)
        assertArrayEquals(frame, reassembled)
    }

    @Test
    fun `split then reassemble is the identity`() {
        val frame = SessionFrameCodec.buildWriteCommand(0x0010, ByteArray(20))

        assertArrayEquals(frame, ChannelChunker.reassemble(ChannelChunker.split(frame)))
    }

    @Test
    fun `split rejects empty input`() {
        try {
            ChannelChunker.split(ByteArray(0))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("empty"))
        }
    }

    @Test
    fun `reassemble rejects empty input`() {
        try {
            ChannelChunker.reassemble(emptyList())
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("empty"))
        }
    }

    @Test
    fun `reassemble rejects fewer bytes than the size byte claims`() {
        val frame = SessionFrameCodec.buildWriteCommand(0x0010, ByteArray(32))

        try {
            // deliver only the first chunk — 16 of 40 bytes
            ChannelChunker.reassemble(listOf(frame.copyOfRange(0, 16)))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("size byte"))
        }
    }
}
