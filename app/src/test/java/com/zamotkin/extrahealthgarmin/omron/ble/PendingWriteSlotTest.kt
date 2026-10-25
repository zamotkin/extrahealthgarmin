package com.zamotkin.extrahealthgarmin.omron.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the single-slot write registry extracted from OmronGattConnection
 * (task tefal2garmin-983, M1). The headline case: external cancellation of the
 * awaiting coroutine - what RetryCommandRunner's 1 s attempt timeout does to a
 * hung write - must clear the slot so the retry attempt is not rejected as a
 * concurrent write.
 */
class PendingWriteSlotTest {

    @Test
    fun `external cancellation clears the slot so the next write can begin`() = runTest {
        val slot = PendingWriteSlot(timeoutMs = 60_000)   // retry timeout wins, not the slot's own
        val hungWrite = async(start = CoroutineStart.UNDISPATCHED) {
            slot.awaitStatus("write test") { /* GATT accepted; callback never arrives */ }
        }

        hungWrite.cancelAndJoin()   // RetryCommandRunner-style cancellation mid-await

        // the retry attempt must NOT hit "concurrent GATT writes - serialization violated"
        val retried = async {
            slot.awaitStatus("write retry") { slot.complete(0) }
        }
        assertEquals(0, retried.await())
    }

    @Test
    fun `slot timeout clears the slot and throws GattTimeoutException`() = runTest {
        val slot = PendingWriteSlot(timeoutMs = 100)
        try {
            slot.awaitStatus("write test") { /* callback never arrives */ }
            fail("expected GattTimeoutException")
        } catch (expected: GattTimeoutException) {
            assertEquals("write test", expected.state)
        }
        // slot is free again
        assertEquals(0, slot.awaitStatus("write test") { slot.complete(0) })
    }

    @Test
    fun `invoke throw clears the slot and rethrows`() = runTest {
        val slot = PendingWriteSlot()
        try {
            slot.awaitStatus("write test") { throw OmronBleException("writeCharacteristic rejected") }
            fail("expected OmronBleException")
        } catch (expected: OmronBleException) {
            // the GATT call itself refused to start
        }
        assertEquals(0, slot.awaitStatus("write test") { slot.complete(0) })
    }

    @Test
    fun `successful callback returns the status and frees the slot`() = runTest {
        val slot = PendingWriteSlot()
        val awaiter = async(start = CoroutineStart.UNDISPATCHED) {
            slot.awaitStatus("write test") { slot.complete(3) }   // non-success status
        }
        assertEquals(3, awaiter.await())
        assertEquals(0, slot.awaitStatus("write test") { slot.complete(0) })
    }

    @Test
    fun `double begin is rejected as a serialization violation`() {
        val slot = PendingWriteSlot()
        slot.begin()
        try {
            slot.begin()
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("concurrent GATT writes"))
        }
    }

    @Test
    fun `fail completes the awaiting coroutine exceptionally and later calls are no-ops`() = runTest {
        val slot = PendingWriteSlot()
        supervisorScope {
            val awaiter = async(start = CoroutineStart.UNDISPATCHED) {
                slot.awaitStatus("write test") { /* accepted; will be failed */ }
            }
            slot.fail(GattDisconnectedException("close", "connection closed"))
            try {
                awaiter.await()
                fail("expected GattDisconnectedException")
            } catch (expected: GattDisconnectedException) {
                // the disconnect reached the awaiting write
            }
        }
        // late complete/fail after the slot was cleared must not throw
        slot.complete(0)
        slot.fail(GattDisconnectedException("close", "closed"))
        // and the slot accepts a fresh write afterwards
        assertEquals(0, slot.awaitStatus("write test") { slot.complete(0) })
    }
}
