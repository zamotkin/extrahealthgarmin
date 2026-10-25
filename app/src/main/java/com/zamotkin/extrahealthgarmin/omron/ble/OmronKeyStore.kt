package com.zamotkin.extrahealthgarmin.omron.ble

import com.zamotkin.extrahealthgarmin.omron.protocol.OmronProtocol
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Encryption seam for the Omron pairing key — the byte-array counterpart of
 * garmin/TokenCrypto: splitting the interface lets unit tests run on the JVM
 * (the AndroidKeyStore does not exist there) while production uses
 * [KeystoreAesPairingKeyCrypto].
 */
interface PairingKeyCrypto {

    /** Encrypts the 16-byte key; returns a single opaque storable string. */
    fun encrypt(plaintext: ByteArray): String

    /** Reverses [encrypt]; throws [IllegalStateException] on any failure. */
    fun decrypt(stored: String): ByteArray
}

/**
 * Persistence seam for the encrypted pairing-key blob (filesDir in production,
 * in-memory in tests) — the same seam idea as JournalPersistence.
 */
interface KeyPersistence {
    fun load(): String?
    fun save(value: String)
    fun clear()
}

/** Production persistence: one file in filesDir, atomic temp+rename writes. */
class FileKeyPersistence(private val file: File) : KeyPersistence {

    override fun load(): String? = if (file.exists()) file.readText() else null

    override fun save(value: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(value)
        if (!tmp.renameTo(file)) {
            throw IllegalStateException("pairing key rename failed: ${tmp.path}")
        }
    }

    override fun clear() {
        file.delete()
    }
}

/**
 * Stores the 16-byte Omron application-level pairing key (spec §3),
 * AES-encrypted at rest via the injected [PairingKeyCrypto] seam — mirroring
 * garmin/TokenCrypto's design (interface seam + AndroidKeyStore production
 * implementation under a dedicated alias; the key never persists in
 * plaintext).
 *
 * Semantics:
 *  - [getOrCreate] returns the SAME key on every call; the first call on a
 *    fresh install generates it via [SecureRandom] and persists the blob;
 *  - a corrupt or undecryptable blob is treated as absent: a fresh key is
 *    generated (fail-safe, never crash);
 *  - [reset] wipes the stored blob so the next [getOrCreate] generates a new
 *    key — required when the user re-pairs (the M7 remembers only one host
 *    key; moving the device to another app invalidates ours).
 *
 * Pure JVM — no Android imports; unit-testable over fakes.
 */
class OmronKeyStore(
    private val crypto: PairingKeyCrypto,
    private val persistence: KeyPersistence,
    private val random: SecureRandom = SecureRandom(),
) {

    private val cached = AtomicReference<ByteArray>()

    /** The stable 16-byte pairing key, generating and persisting on first use. */
    fun getOrCreate(): ByteArray {
        cached.get()?.let { return it.copyOf() }

        val fromDisk = loadPersisted()
        if (fromDisk != null) {
            cached.set(fromDisk)
            return fromDisk.copyOf()
        }

        val generated = ByteArray(OmronProtocol.PAIRING_KEY_BYTES).also { random.nextBytes(it) }
        persistence.save(crypto.encrypt(generated))
        cached.set(generated)
        return generated.copyOf()
    }

    /** Wipes the persisted key; the next [getOrCreate] generates a fresh one. */
    fun reset() {
        cached.set(null)
        persistence.clear()
    }

    /** Returns the decrypted key, or null when absent/corrupt (fail-safe). */
    private fun loadPersisted(): ByteArray? {
        val blob = persistence.load() ?: return null
        return try {
            val key = crypto.decrypt(blob)
            if (key.size == OmronProtocol.PAIRING_KEY_BYTES) key else null
        } catch (e: Exception) {
            null // corrupt blob -> treat as absent, generate a new key
        }
    }
}
