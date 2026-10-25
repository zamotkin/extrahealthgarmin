package com.zamotkin.extrahealthgarmin.omron.ble

import android.Manifest
import android.os.Build
import com.zamotkin.extrahealthgarmin.omron.protocol.OmronSessionException
import com.zamotkin.extrahealthgarmin.omron.protocol.UnlockRejectedException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the pure decision functions extracted from OmronBleClient
 * (task tefal2garmin-983): the pre-S location permission requirement (M3),
 * the typed unlock-rejection mapping (m5) and cancellation hygiene (m6).
 * The Context-taking wrappers are Android-bound and covered by KDoc only.
 */
class OmronBleClientTest {

    // ------------------------------------------------------------------ //
    //  M3: requiredBlePermissions                                         //
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
    //  m5: typed unlock rejection -> AuthFailed                           //
    // ------------------------------------------------------------------ //

    @Test
    fun `typed UnlockRejectedException maps to AuthFailed`() {
        val result = mapCaughtFailure(
            UnlockRejectedException("unlock rejected: Unknown — key not registered on device?"),
        )
        assertTrue(result is OmronBleResult.AuthFailed)
        assertTrue((result as OmronBleResult.AuthFailed).reason.contains("unlock rejected"))
    }

    @Test
    fun `plain session failure without unlock rejection maps to Failed`() {
        val result = mapCaughtFailure(OmronSessionException("unexpected read response: 1234"))
        assertTrue(result is OmronBleResult.Failed)
    }

    // ------------------------------------------------------------------ //
    //  m6: cancellation hygiene                                           //
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
    //  remaining mapping parity with the original catch chain             //
    // ------------------------------------------------------------------ //

    @Test
    fun `gatt exceptions keep their typed results`() {
        assertTrue(
            mapCaughtFailure(BondingRequiredException("pairing refused")) is OmronBleResult.BondingRequired,
        )
        assertTrue(
            mapCaughtFailure(GattTimeoutException("connect", "timed out")) is OmronBleResult.Timeout,
        )
        assertTrue(
            mapCaughtFailure(GattDisconnectedException("write", "link dropped")) is OmronBleResult.Disconnected,
        )
        val fallback = mapCaughtFailure(IllegalStateException("anything else"))
        assertTrue(fallback is OmronBleResult.Failed)
        assertEquals("anything else", (fallback as OmronBleResult.Failed).reason)
    }

    @Test
    fun `log labels cover the typed branches`() {
        assertEquals("unlock rejected", labelFor(UnlockRejectedException("unlock rejected")))
        assertEquals("session failed", labelFor(OmronSessionException("boom")))
        assertEquals("BLE read failed", labelFor(IllegalStateException("boom")))
    }
}
