package com.zamotkin.extrahealthgarmin.omron.ble

import com.zamotkin.extrahealthgarmin.omron.protocol.OmronProtocol
import com.zamotkin.extrahealthgarmin.omron.protocol.OmronSessionException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs one BLE command attempt with the protocol retry policy (spec §4.5,
 * mirroring omblepy's `_waitForRxOrRetry`): up to [maxAttempts] tries, each
 * bounded by [timeoutMs]. An attempt "fails" when it returns null (no response,
 * or a corrupt response the caller maps to null) or when it exceeds the
 * timeout and gets cancelled. Exhausting the attempts throws
 * [OmronSessionException] for the session layer to unwind.
 *
 * Pure JVM - no Android types.
 */
class RetryCommandRunner(
    private val maxAttempts: Int = OmronProtocol.COMMAND_MAX_RETRIES,
    private val timeoutMs: Long = OmronProtocol.COMMAND_TIMEOUT_MS,
) {

    /**
     * @param operation short human-readable name used in the failure message
     * @param attempt one try; null means "no usable response this time"
     * @throws OmronSessionException after [maxAttempts] failed attempts
     */
    suspend fun <T> run(operation: String, attempt: suspend () -> T?): T {
        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            val result = withTimeoutOrNull(timeoutMs) { attempt() }
            if (result != null) return result
        }
        throw OmronSessionException(
            "$operation failed: no response after $attempts attempts " +
                "(${timeoutMs}ms timeout each)",
        )
    }
}
