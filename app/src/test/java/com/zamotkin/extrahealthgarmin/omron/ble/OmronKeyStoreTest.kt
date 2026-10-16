package com.zamotkin.extrahealthgarmin.omron.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Tests for [OmronKeyStore] over in-memory fakes (task tefal2garmin-uq6) —
 * key lifecycle mirroring garmin/TokenCrypto semantics: stable getOrCreate,
 * fresh key after reset, fail-safe on corrupt blobs, plaintext never
 * persisted.
 */
class OmronKeyStoreTest {

    /** Deterministic fake crypto: XOR-with-repeating-pad, Base64-wrapped. */
    private class FakeCrypto : PairingKeyCrypto {
        var encryptCalls = 0
        var decryptCalls = 0
        var decryptThrows = false

        override fun encrypt(plaintext: ByteArray): String {
            encryptCalls++
            return Base64.getEncoder().encodeToString(xorPad(plaintext))
        }

        override fun decrypt(stored: String): ByteArray {
            decryptCalls++
            if (decryptThrows) throw IllegalStateException("simulated keystore failure")
            return xorPad(Base64.getDecoder().decode(stored))
        }

        private fun xorPad(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { i ->
            (bytes[i].toInt() xor PAD[i % PAD.size].toInt()).toByte()
        }

        companion object { private val PAD = byteArrayOf(0x5A, 0xA5.toByte(), 0x37) }
    }

    private class InMemoryPersistence : KeyPersistence {
        var stored: String? = null
        override fun load(): String? = stored
        override fun save(value: String) { stored = value }
        override fun clear() { stored = null }
    }

    /** SecureRandom fake: a different constant sequence per instance counter. */
    private class SequentialRandom : SecureRandom() {
        private var next = 0
        override fun nextBytes(bytes: ByteArray) {
            bytes.forEachIndexed { i, _ ->
                bytes[i] = ((counter * 31 + next++) and 0xFF).toByte()
            }
        }
        companion object { private var counter = 0; @Synchronized fun fresh() = SequentialRandom().also { counter++ } }
    }

    @Test
    fun `first_call_generates_16_byte_key`() {
        val store = OmronKeyStore(FakeCrypto(), InMemoryPersistence(), SequentialRandom.fresh())
        assertEquals(16, store.getOrCreate().size)
    }

    @Test
    fun `repeated_calls_return_identical_key_without_regeneration`() {
        val crypto = FakeCrypto()
        val persistence = InMemoryPersistence()
        val store = OmronKeyStore(crypto, persistence, SequentialRandom.fresh())

        val first = store.getOrCreate()
        val second = store.getOrCreate()

        assertArrayEquals(first, second)
        assertEquals(1, crypto.encryptCalls) // persisted exactly once
    }

    @Test
    fun `new_instance_loads_persisted_key_instead_of_generating`() {
        val crypto = FakeCrypto()
        val persistence = InMemoryPersistence()
        val first = OmronKeyStore(crypto, persistence, SequentialRandom.fresh()).getOrCreate()

        // fresh store, same persistence (e.g. process restart)
        val reloaded = OmronKeyStore(FakeCrypto(), persistence, SequentialRandom.fresh()).getOrCreate()

        assertArrayEquals(first, reloaded)
    }

    @Test
    fun `reset_then_getOrCreate_returns_different_key`() {
        val persistence = InMemoryPersistence()
        val store = OmronKeyStore(FakeCrypto(), persistence, SequentialRandom.fresh())
        val original = store.getOrCreate()

        store.reset()
        val regenerated = store.getOrCreate()

        assertFalse(original.contentEquals(regenerated))
        assertEquals(16, regenerated.size)
    }

    @Test
    fun `corrupt_blob_treated_as_absent_new_key_generated`() {
        val crypto = FakeCrypto()
        val persistence = InMemoryPersistence().apply { stored = "%%%not-base64%%%" }
        val store = OmronKeyStore(crypto, persistence, SequentialRandom.fresh())

        val key = store.getOrCreate()

        assertEquals(16, key.size)
        assertEquals(1, crypto.encryptCalls) // a fresh key was persisted
    }

    @Test
    fun `decrypt_failure_treated_as_absent_no_crash`() {
        val crypto = FakeCrypto().apply { decryptThrows = true }
        val persistence = InMemoryPersistence().apply { stored = "AAAA" }
        val store = OmronKeyStore(crypto, persistence, SequentialRandom.fresh())

        assertEquals(16, store.getOrCreate().size) // fail-safe, not crash
    }

    @Test
    fun `persisted_blob_is_not_the_plaintext_key`() {
        val crypto = FakeCrypto()
        val persistence = InMemoryPersistence()
        val key = OmronKeyStore(crypto, persistence, SequentialRandom.fresh()).getOrCreate()

        val stored = persistence.stored!!
        val storedBytes = Base64.getDecoder().decode(stored)
        assertFalse("plaintext key must not appear in the persisted blob",
            storedBytes.contentEquals(key))
    }

    @Test
    fun `wrong_length_persisted_key_rejected_and_regenerated`() {
        val crypto = FakeCrypto()
        val persistence = InMemoryPersistence().apply { stored = crypto.encrypt(ByteArray(15)) }
        val store = OmronKeyStore(crypto, persistence, SequentialRandom.fresh())

        assertEquals(16, store.getOrCreate().size)
    }

    @Test
    fun `file_persistence_roundtrip_and_clear`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "omron-keystore-test-${System.nanoTime()}")
        dir.mkdirs()
        val file = File(dir, "key")
        try {
            val persistence = FileKeyPersistence(file)
            assertEquals(null, persistence.load())
            persistence.save("blob")
            assertEquals("blob", persistence.load())
            persistence.clear()
            assertEquals(null, persistence.load())
        } finally {
            dir.deleteRecursively()
        }
    }
}
