package com.zamotkin.extrahealthgarmin.devices.omron

import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.devices.api.BpReading
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadRequest
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import com.zamotkin.extrahealthgarmin.omron.ble.OmronBleResult
import com.zamotkin.extrahealthgarmin.omron.protocol.OmronProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OmronBleSource] tests (task tefal2garmin-app-redesign-v3-xlg.2): the
 * request/result mapping over faked seams - the real omron/ble stack is not
 * touched here (real BLE runs only in the final on-device verification
 * task). Plain JUnit, no Android.
 *
 * Task tefal2garmin-app-redesign-v3-xlg.13 added the never-throw contract
 * tests: a throwing key seam folds onto Failed, cancellation propagates.
 */
class OmronBleSourceTest {

    /** What the adapter handed to the BLE seam, in call order. */
    private data class BleCall(val key: ByteArray, val userSlot: Int, val fullRead: Boolean)

    private val pairingKey = ByteArray(16) { (it + 1).toByte() }
    private val calls = mutableListOf<BleCall>()
    private var bleResult: OmronBleResult = OmronBleResult.Success(emptyList())

    private val source = OmronBleSource(
        keyProvider = { pairingKey },
        bleRead = { key, userSlot, fullRead ->
            calls += BleCall(key, userSlot, fullRead)
            bleResult
        }
    )

    // ------------------------------------------------------------------ //
    //  Identity + capabilities                                            //
    // ------------------------------------------------------------------ //

    @Test
    fun `identity is omron-ble with the type display name`() {
        assertEquals("omron-ble", source.sourceTypeId)
        assertEquals(R.string.bp_source_type_omron_ble, source.displayNameRes)
    }

    @Test
    fun `capabilities are slots with history and current and a pairing key`() {
        val capabilities = source.capabilities

        assertEquals(UserAttribution.SLOTS, capabilities.userAttribution)
        assertEquals(setOf(SyncMode.HISTORY, SyncMode.CURRENT), capabilities.supportedSyncModes)
        assertTrue(capabilities.requiresPairingKey)
        // Task xlg.17: the M7 key is supplied by the app (generated per
        // docs/omron-protocol.md §3.3/§12), so the flow asks for no input.
        assertTrue(capabilities.autoPairingKey)
        // The M7 key length travels with the capability (task xlg.14), so
        // the add-source flow validates against it without importing the
        // Omron protocol constant.
        assertEquals(OmronProtocol.PAIRING_KEY_BYTES, capabilities.pairingKeyBytes)
    }

    // ------------------------------------------------------------------ //
    //  Request mapping                                                    //
    // ------------------------------------------------------------------ //

    @Test
    fun `history request maps to a full ring read`() = runTest {
        source.read(SourceReadRequest(SyncMode.HISTORY, userSlot = 1))

        assertEquals(listOf(BleCall(pairingKey, 1, true)), calls)
    }

    @Test
    fun `current request maps to an unread window read`() = runTest {
        source.read(SourceReadRequest(SyncMode.CURRENT, userSlot = 2))

        assertEquals(listOf(BleCall(pairingKey, 2, false)), calls)
    }

    @Test
    fun `request without a user slot fails without touching BLE`() = runTest {
        val outcome = source.read(SourceReadRequest(SyncMode.CURRENT))

        assertTrue(outcome is SourceReadResult.Failed)
        assertEquals(emptyList<BleCall>(), calls)
    }

    // ------------------------------------------------------------------ //
    //  Result mapping                                                     //
    // ------------------------------------------------------------------ //

    @Test
    fun `successful readings map field by field`() = runTest {
        bleResult = OmronBleResult.Success(
            listOf(
                BloodPressureReading(
                    timestampEpochSec = 1_760_000_000,
                    systolic = 135,
                    diastolic = 85,
                    pulse = 65,
                    mov = true,
                    ihb = true,
                    userSlot = 2
                )
            )
        )

        val outcome = source.read(SourceReadRequest(SyncMode.CURRENT, userSlot = 2))

        assertEquals(
            SourceReadResult.Success(
                listOf(
                    BpReading(
                        timestampEpochSec = 1_760_000_000,
                        systolic = 135,
                        diastolic = 85,
                        pulse = 65,
                        mov = true,
                        ihb = true,
                        userSlot = 2
                    )
                )
            ),
            outcome
        )
    }

    @Test
    fun `bonding required and auth failure both mean the connection is missing`() = runTest {
        bleResult = OmronBleResult.BondingRequired("bond")
        assertEquals(
            SourceReadResult.ConnectionRequired("bond"),
            source.read(SourceReadRequest(SyncMode.CURRENT, userSlot = 1))
        )

        bleResult = OmronBleResult.AuthFailed("key rejected")
        assertEquals(
            SourceReadResult.ConnectionRequired("key rejected"),
            source.read(SourceReadRequest(SyncMode.CURRENT, userSlot = 1))
        )
    }

    @Test
    fun `timeout disconnected and generic failure keep their variants`() = runTest {
        bleResult = OmronBleResult.Timeout("unlock")
        assertEquals(
            SourceReadResult.Timeout("unlock"),
            source.read(SourceReadRequest(SyncMode.CURRENT, userSlot = 1))
        )

        bleResult = OmronBleResult.Disconnected("session")
        assertEquals(
            SourceReadResult.Disconnected("session"),
            source.read(SourceReadRequest(SyncMode.CURRENT, userSlot = 1))
        )

        bleResult = OmronBleResult.Failed("boom")
        assertEquals(
            SourceReadResult.Failed("boom"),
            source.read(SourceReadRequest(SyncMode.CURRENT, userSlot = 1))
        )
    }

    // ------------------------------------------------------------------ //
    //  Never-throw contract                                              //
    // ------------------------------------------------------------------ //

    /**
     * The production key provider (OmronKeyStore.getOrCreate) throws on a
     * Keystore error and on its first-generation file save: the failure
     * must fold onto Failed and never escape read().
     */
    @Test
    fun `a throwing key provider fails the read without touching BLE`() = runTest {
        val throwingSource = OmronBleSource(
            keyProvider = { throw IllegalStateException("Keystore unavailable") },
            bleRead = { key, userSlot, fullRead ->
                calls += BleCall(key, userSlot, fullRead)
                bleResult
            }
        )

        val outcome = throwingSource.read(SourceReadRequest(SyncMode.CURRENT, userSlot = 1))

        assertTrue(outcome is SourceReadResult.Failed)
        assertEquals(emptyList<BleCall>(), calls)
        assertTrue((outcome as SourceReadResult.Failed).reason.contains("Keystore unavailable"))
    }

    /**
     * Cancellation from the key seam must PROPAGATE — swallowing it would
     * turn a cancelled coroutine into a phantom Failed pass.
     */
    @Test
    fun `cancellation from the key provider propagates`() = runTest {
        val cancellingSource = OmronBleSource(
            keyProvider = { throw CancellationException("pass cancelled") },
            bleRead = { key, userSlot, fullRead ->
                calls += BleCall(key, userSlot, fullRead)
                bleResult
            }
        )

        try {
            cancellingSource.read(SourceReadRequest(SyncMode.CURRENT, userSlot = 1))
            fail("CancellationException must propagate out of read")
        } catch (e: CancellationException) {
            // expected — rethrown before the Failed fallback
        }
        assertEquals(emptyList<BleCall>(), calls)
    }
}
