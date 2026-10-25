package com.zamotkin.extrahealthgarmin.devices.omron

import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.devices.api.BpReading
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadRequest
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import com.zamotkin.extrahealthgarmin.omron.ble.OmronBleResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OmronBleSource] tests (task tefal2garmin-app-redesign-v3-xlg.2): the
 * request/result mapping over faked seams - the real omron/ble stack is not
 * touched here (real BLE runs only in the final on-device verification
 * task). Plain JUnit, no Android.
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
}
