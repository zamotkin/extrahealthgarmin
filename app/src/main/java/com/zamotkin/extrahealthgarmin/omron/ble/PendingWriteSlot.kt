package com.zamotkin.extrahealthgarmin.omron.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single-slot registry for the one write-with-response GATT operation allowed
 * in flight (transport-level serialization). Extracted from
 * [OmronGattConnection] (task tefal2garmin-983) so the every-exit-path cleanup
 * is unit-testable on plain JVM.
 *
 * [awaitStatus] clears the slot on EVERY exit: invoke-throw, operation
 * timeout, non-success status (cleared by [complete] itself) and — the case
 * the review found missing — external cancellation of the awaiting coroutine
 * ([RetryCommandRunner] cancels a hung attempt after 1 s, long before this
 * class's own [timeoutMs] fires). A stale slot would make the retry's
 * [begin] throw "concurrent GATT writes" and kill the whole retry loop.
 *
 * Pure JVM — no Android types.
 */
internal class PendingWriteSlot(private val timeoutMs: Long = 5_000L) {

    private var deferred: CompletableDeferred<Int>? = null
    private val lock = Any()

    /** Registers the single in-flight write; throws IllegalStateException on double registration. */
    fun begin(): CompletableDeferred<Int> = synchronized(lock) {
        check(deferred == null) { "concurrent GATT writes - serialization violated" }
        CompletableDeferred<Int>().also { deferred = it }
    }

    /** Completes and clears the slot (GATT callback thread); no-op when idle. */
    fun complete(status: Int) {
        val pending = synchronized(lock) { deferred.also { deferred = null } }
        pending?.complete(status)
    }

    /** Fails and clears the slot; no-op when idle. */
    fun fail(t: Throwable) {
        val pending = synchronized(lock) { deferred.also { deferred = null } }
        pending?.completeExceptionally(t)
    }

    /**
     * Registers the slot, invokes [invoke] (the actual GATT call) and awaits the
     * callback's status for at most [timeoutMs].
     *
     * @throws GattTimeoutException when no callback arrives in time
     * @throws CancellationException rethrown after cleanup when the awaiting
     *   coroutine is cancelled externally (attempt retry timeout, scope teardown)
     */
    suspend fun awaitStatus(what: String, invoke: () -> Unit): Int {
        val pending = begin()
        try {
            invoke()
        } catch (t: Throwable) {
            fail(OmronBleException("$what could not be started: ${t.message}"))
            throw t
        }
        try {
            return withTimeoutOrNull(timeoutMs) { pending.await() }
                ?: run {
                    val t = GattTimeoutException(what, "$what timed out after ${timeoutMs}ms")
                    fail(t)
                    throw t
                }
        } catch (e: CancellationException) {
            // External cancellation (this class's own timeout returns null above):
            // release the slot so the next attempt's begin() is not rejected.
            fail(GattDisconnectedException(what, "$what abandoned - awaiting coroutine cancelled"))
            throw e
        }
    }
}
