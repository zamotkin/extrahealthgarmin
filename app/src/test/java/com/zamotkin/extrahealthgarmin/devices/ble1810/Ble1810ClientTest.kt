package com.zamotkin.extrahealthgarmin.devices.ble1810

import android.Manifest
import android.os.Build
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the pure decision functions of [Ble1810Client] (task
 * tefal2garmin-app-redesign-v3-xlg.9) - the
 * [com.zamotkin.extrahealthgarmin.omron.ble.OmronBleClientTest] pattern:
 * the pre-S location permission requirement, the typed failure mapping and
 * cancellation hygiene. The Context-taking wrappers are Android-bound and
 * covered by KDoc only.
 */
class Ble1810ClientTest {

    // ------------------------------------------------------------------ //
    //  requiredBlePermissions                                             //
    // ------------------------------------------------------------------ //

    @Test
    fun `pre-S requires ACCESS_FINE_LOCATION - a runtime permission since API 23`() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), requiredBlePermissions(26))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), requiredBlePermissions(30))
    }

    @Test
    fun `S and above require BLUETOOTH_SCAN and BLUETOOTH_CONNECT`() {
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            requiredBlePermissions(Build.VERSION_CODES.S),
        )
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            requiredBlePermissions(33),
        )
    }

    // ------------------------------------------------------------------ //
    //  Typed failure mapping                                              //
    // ------------------------------------------------------------------ //

    @Test
    fun `typed bonding exception maps to BondingRequired`() {
        val result = mapCaughtFailure(
            Ble1810BondingException("pairing did not complete within 30000ms")
        )
        assertTrue(result is Ble1810Result.BondingRequired)
        assertTrue((result as Ble1810Result.BondingRequired).reason.contains("pairing"))
    }

    @Test
    fun `timeout and disconnected keep their step`() {
        assertEquals(
            Ble1810Result.Timeout("connect"),
            mapCaughtFailure(Ble1810TimeoutException("connect", "GATT connect timed out"))
        )
        assertEquals(
            Ble1810Result.Disconnected("connect"),
            mapCaughtFailure(Ble1810DisconnectedException("connect", "link dropped (status=19)"))
        )
    }

    @Test
    fun `any other failure maps to Failed with its message`() {
        val result = mapCaughtFailure(Ble1810Exception("Blood is switched off"))

        assertTrue(result is Ble1810Result.Failed)
        assertEquals("Blood is switched off", (result as Ble1810Result.Failed).reason)
    }

    // ------------------------------------------------------------------ //
    //  Cancellation hygiene                                               //
    // ------------------------------------------------------------------ //

    @Test
    fun `CancellationException is rethrown, never mapped to Failed`() {
        try {
            mapCaughtFailure(CancellationException("scope torn down"))
            fail("expected CancellationException to be rethrown")
        } catch (expected: CancellationException) {
            // must propagate so the caller's scope stays cancelled
        }
    }

    // ------------------------------------------------------------------ //
    //  Log labels                                                         //
    // ------------------------------------------------------------------ //

    @Test
    fun `labelFor names the failure class`() {
        assertEquals("bonding required", labelFor(Ble1810BondingException("x")))
        assertEquals("disconnected (step: connect)", labelFor(Ble1810DisconnectedException("connect", "x")))
        assertEquals("timed out (step: discover)", labelFor(Ble1810TimeoutException("discover", "x")))
        assertEquals("BLE read failed", labelFor(Ble1810Exception("x")))
    }
}
