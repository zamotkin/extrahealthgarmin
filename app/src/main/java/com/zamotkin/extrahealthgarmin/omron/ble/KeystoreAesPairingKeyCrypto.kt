package com.zamotkin.extrahealthgarmin.omron.ble

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM encryption for the Omron pairing key, backed by the AndroidKeyStore
 * under a dedicated alias — the byte-array counterpart of
 * garmin/KeystoreAesCrypto (which deliberately keeps its own
 * `extrahealthgarmin_di_tokens` alias; one alias per domain). The key never
 * leaves secure hardware; the persisted blob is `Base64(iv || ciphertext+tag)`.
 *
 * AndroidKeyStore AES keys require API 23+; minSdk is 26, so every operation
 * succeeds in practice — failures still throw [IllegalStateException] and the
 * [OmronKeyStore] fail-safes to generating a fresh key on the next call.
 */
class KeystoreAesPairingKeyCrypto : PairingKeyCrypto {

    override fun encrypt(plaintext: ByteArray): String = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw IllegalStateException(
                "AndroidKeyStore AES keys require API 23+; running on API ${Build.VERSION.SDK_INT}"
            )
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: Exception) {
        throw IllegalStateException("pairing key encryption failed: ${e.message}", e)
    }

    override fun decrypt(stored: String): ByteArray = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw IllegalStateException(
                "AndroidKeyStore AES keys require API 23+; running on API ${Build.VERSION.SDK_INT}"
            )
        }
        val blob = Base64.decode(stored, Base64.NO_WRAP)
        if (blob.size <= GCM_IV_LENGTH_BYTES) {
            throw IllegalStateException("encrypted pairing key blob too short (${blob.size} bytes)")
        }
        val iv = blob.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = blob.copyOfRange(GCM_IV_LENGTH_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ciphertext)
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: Exception) {
        throw IllegalStateException("pairing key decryption failed: ${e.message}", e)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        Log.d(TAG, "Generating AndroidKeyStore AES key '$KEY_ALIAS'")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "KeystoreAesPairingKey"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "extrahealthgarmin_omron_pairing_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
