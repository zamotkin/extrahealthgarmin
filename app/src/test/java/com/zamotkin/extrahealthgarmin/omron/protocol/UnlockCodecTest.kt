package com.zamotkin.extrahealthgarmin.omron.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [UnlockCodec] — unlock-characteristic payloads per
 * docs/omron-protocol.md §3 (omblepy writeNewUnlockKey / unlockWithUnlockKey).
 */
class UnlockCodecTest {

    private val key = ByteArray(16) { it.toByte() }

    @Test
    fun `program_mode_request_is_02_followed_by_16_zero_bytes`() {
        val expected = ByteArray(17)
        expected[0] = 0x02
        assertArrayEquals(expected, UnlockCodec.programModeRequest())
    }

    @Test
    fun `program_key_prefixes_key_with_00`() {
        val frame = UnlockCodec.programKey(key)
        assertEquals(17, frame.size)
        assertEquals(0x00.toByte(), frame[0])
        assertArrayEquals(key, frame.copyOfRange(1, 17))
    }

    @Test
    fun `unlock_prefixes_key_with_01`() {
        val frame = UnlockCodec.unlock(key)
        assertEquals(17, frame.size)
        assertEquals(0x01.toByte(), frame[0])
        assertArrayEquals(key, frame.copyOfRange(1, 17))
    }

    @Test
    fun `wrong_key_length_rejected_for_program_key`() {
        try {
            UnlockCodec.programKey(ByteArray(15))
            fail("expected IllegalArgumentException for 15-byte key")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `wrong_key_length_rejected_for_unlock`() {
        try {
            UnlockCodec.unlock(ByteArray(38))   // the docs/omron.md claim — must not pass
            fail("expected IllegalArgumentException for 38-byte key")
        } catch (e: IllegalArgumentException) {
            // expected — pins the 16-byte key (spec discrepancy #1)
        }
    }

    @Test
    fun `response_classification_matches_spec`() {
        assertEquals(
            UnlockCodec.Response.ProgramModeEntered,
            UnlockCodec.classify(byteArrayOf(0x82.toByte(), 0x00)),
        )
        assertEquals(
            UnlockCodec.Response.KeyProgrammed,
            UnlockCodec.classify(byteArrayOf(0x80.toByte(), 0x00, 0x11)),
        )
        assertEquals(
            UnlockCodec.Response.Unlocked,
            UnlockCodec.classify(byteArrayOf(0x81.toByte(), 0x00)),
        )
        val unexpected = UnlockCodec.classify(byteArrayOf(0x99.toByte(), 0x99.toByte()))
        assertEquals(0x9999, (unexpected as UnlockCodec.Response.Unexpected).type)
    }

    @Test
    fun `empty_response_classified_unexpected`() {
        val r = UnlockCodec.classify(ByteArray(0))
        assertEquals(-1, (r as UnlockCodec.Response.Unexpected).type)
    }
}
