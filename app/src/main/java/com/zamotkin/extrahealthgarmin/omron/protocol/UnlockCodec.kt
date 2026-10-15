package com.zamotkin.extrahealthgarmin.omron.protocol

/**
 * Builder/parser for the Omron unlock characteristic protocol
 * (docs/omron-protocol.md §3, omblepy writeNewUnlockKey / unlockWithUnlockKey).
 *
 * All payloads are 17 bytes: one opcode byte + the 16-byte pairing key.
 * Pure JVM — no Android types.
 */
object UnlockCodec {

    sealed interface Response {
        /** `8200` — key programming mode entered (pairing mode on the device). */
        data object ProgramModeEntered : Response

        /** `8000` — new key programmed into the device. */
        data object KeyProgrammed : Response

        /** `8100` — session unlocked with the stored key. */
        data object Unlocked : Response

        /** Anything else, including an empty response. [type] is the 16-bit header or -1. */
        data class Unexpected(val type: Int) : Response
    }

    /** `02 || 00 x 16` — request key programming mode. */
    fun programModeRequest(): ByteArray = ByteArray(1 + OmronProtocol.PAIRING_KEY_BYTES).also {
        it[0] = OmronProtocol.UNLOCK_OPCODE_PROGRAM_MODE.toByte()
    }

    /** `00 || key` — program a new 16-byte key (device must show "-P-"). */
    fun programKey(key: ByteArray): ByteArray = opcodePlusKey(OmronProtocol.UNLOCK_OPCODE_PROGRAM_KEY, key)

    /** `01 || key` — unlock an already-registered key (every session). */
    fun unlock(key: ByteArray): ByteArray = opcodePlusKey(OmronProtocol.UNLOCK_OPCODE_UNLOCK, key)

    /** Classifies a notify response from the unlock characteristic by its first two bytes. */
    fun classify(response: ByteArray): Response {
        val type = if (response.size >= 2) {
            (response[0].toInt() and 0xFF) shl 8 or (response[1].toInt() and 0xFF)
        } else {
            -1
        }
        return when (type) {
            OmronProtocol.UNLOCK_PROGRAM_MODE_RESPONSE -> Response.ProgramModeEntered
            OmronProtocol.UNLOCK_KEY_PROGRAMMED_RESPONSE -> Response.KeyProgrammed
            OmronProtocol.UNLOCK_OK_RESPONSE -> Response.Unlocked
            else -> Response.Unexpected(type)
        }
    }

    private fun opcodePlusKey(opcode: Int, key: ByteArray): ByteArray {
        require(key.size == OmronProtocol.PAIRING_KEY_BYTES) {
            "pairing key must be ${OmronProtocol.PAIRING_KEY_BYTES} bytes, is ${key.size}"
        }
        return ByteArray(1 + key.size).also {
            it[0] = opcode.toByte()
            key.copyInto(it, destinationOffset = 1)
        }
    }
}
