package com.zamotkin.extrahealthgarmin.omron.ble

import com.zamotkin.extrahealthgarmin.omron.protocol.OmronProtocol
import com.zamotkin.extrahealthgarmin.omron.protocol.OmronSessionException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [RetryCommandRunner] using runTest virtual time — the 1 s per-attempt
 * timeout is simulated by the scheduler, so five timeouts complete instantly.
 */
class RetryCommandRunnerTest {

    @Test
    fun `five null attempts throw after exactly five tries`() = runTest {
        var attempts = 0

        try {
            RetryCommandRunner().run("op") { attempts++; null }
            fail("expected OmronSessionException")
        } catch (expected: OmronSessionException) {
            assertEquals(5, attempts)
            assertTrue(expected.message!!.contains("op"))
            assertTrue(expected.message!!.contains("5"))
        }
    }

    @Test
    fun `success on third attempt returns the value and stops retrying`() = runTest {
        var attempts = 0

        val result = RetryCommandRunner().run("op") {
            attempts++
            if (attempts < 3) null else "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `slow attempt is cut at the timeout and counts as a failure`() = runTest {
        var attempts = 0
        var completed = 0

        try {
            RetryCommandRunner().run("op") {
                attempts++
                delay(2_000)   // twice the 1 s timeout — must never finish
                completed++
                "ok"
            }
            fail("expected OmronSessionException")
        } catch (expected: OmronSessionException) {
            assertEquals(OmronProtocol.COMMAND_MAX_RETRIES, attempts)
            assertEquals(0, completed)   // every attempt was cancelled at 1 s
            // virtual time: 5 attempts x 1000 ms timeout, nothing more
            assertEquals(
                OmronProtocol.COMMAND_MAX_RETRIES * OmronProtocol.COMMAND_TIMEOUT_MS,
                testScheduler.currentTime,
            )
        }
    }

    @Test
    fun `custom attempt count is honored`() = runTest {
        var attempts = 0
        val runner = RetryCommandRunner(maxAttempts = 2)

        try {
            runner.run("op") { attempts++; null }
            fail("expected OmronSessionException")
        } catch (expected: OmronSessionException) {
            assertEquals(2, attempts)
        }
    }
}
