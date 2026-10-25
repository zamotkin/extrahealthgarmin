package com.zamotkin.extrahealthgarmin.devices.ble1810

import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.devices.api.BpReading
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadRequest
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
import com.zamotkin.extrahealthgarmin.health.HealthSyncWiring
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [Ble1810Source] tests (task tefal2garmin-app-redesign-v3-xlg.9): the
 * request/result mapping over faked seams (the
 * [com.zamotkin.extrahealthgarmin.devices.omron.OmronBleSourceTest]
 * pattern), the end-to-end re-read story with the real
 * [Ble1810FingerprintJournal] (HISTORY re-reads must not duplicate), and the
 * registry resolution — ble-standard-1810 plugs into
 * [HealthSyncWiring.sourceRegistry] with no core-class changes.
 *
 * Task tefal2garmin-app-redesign-v3-xlg.15 pins the deferred commit
 * protocol (the xlg.9 review fix): a successful read does NOT consume the
 * journal — only [Ble1810Source.commitDelivered] does, which the read
 * orchestrator runs after the storage step confirmed the insert. An
 * unconfirmed pass re-delivers; the worst case is one duplicate row, never
 * a lost reading.
 */
class Ble1810SourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var bleResult: Ble1810Result =
        Ble1810Result.Success(emptyList(), feature = null, deviceDescription = null, 0, 0, 0)
    private var bleReads = 0

    private fun source(): Ble1810Source = Ble1810Source(
        bleRead = {
            bleReads++
            bleResult
        },
        journal = Ble1810FingerprintJournal.inFilesDir(tmp.root, "AA:BB:CC:DD:EE:FF")
    )

    private fun measurement(
        systolic: Int,
        timestampEpochSec: Long = 1_000,
        fromDeviceClock: Boolean = false,
        mov: Boolean = false,
        ihb: Boolean = false,
        pulse: Int? = 64
    ): Ble1810Measurement = Ble1810Measurement(
        systolicMmHg = systolic,
        diastolicMmHg = 80,
        mapMmHg = 102,
        pulseBpm = pulse,
        timestampEpochSec = timestampEpochSec,
        timestampFromDeviceClock = fromDeviceClock,
        userId = null,
        status = Ble1810MeasurementStatus(
            bodyMovement = mov,
            cuffTooLoose = false,
            irregularPulse = ihb,
            improperPosition = false,
            raw = if (mov) 0x0001 else if (ihb) 0x0004 else 0
        )
    )

    // ------------------------------------------------------------------ //
    //  Identity + capabilities                                            //
    // ------------------------------------------------------------------ //

    @Test
    fun `identity is ble-standard-1810 with the type display name`() {
        val source = source()

        assertEquals("ble-standard-1810", source.sourceTypeId)
        assertEquals(R.string.bp_source_type_ble_standard_1810, source.displayNameRes)
    }

    @Test
    fun `capabilities are app-owner attribution, history only, system bonding`() {
        val capabilities = source().capabilities

        assertEquals(UserAttribution.NONE, capabilities.userAttribution)
        assertEquals(setOf(SyncMode.HISTORY), capabilities.supportedSyncModes)
        assertFalse(capabilities.requiresPairingKey)
    }

    // ------------------------------------------------------------------ //
    //  Request mapping                                                    //
    // ------------------------------------------------------------------ //

    @Test
    fun `a current request fails without touching BLE`() = runTest {
        val outcome = source().read(SourceReadRequest(SyncMode.CURRENT))

        assertTrue(outcome is SourceReadResult.Failed)
        assertEquals(0, bleReads)
    }

    // ------------------------------------------------------------------ //
    //  Result mapping                                                     //
    // ------------------------------------------------------------------ //

    @Test
    fun `successful measurements map field by field`() = runTest {
        bleResult = Ble1810Result.Success(
            measurements = listOf(
                measurement(135, timestampEpochSec = 1_760_000_000, fromDeviceClock = true, mov = true, ihb = true),
                measurement(118, timestampEpochSec = 1_760_003_600, pulse = null)
            ),
            feature = 0x0004,
            deviceDescription = "Beurer BM85",
            skippedSentinels = 1,
            malformedFrames = 0,
            duplicateFrames = 0
        )

        val outcome = source().read(SourceReadRequest(SyncMode.HISTORY))

        assertEquals(
            SourceReadResult.Success(
                listOf(
                    BpReading(
                        timestampEpochSec = 1_760_000_000,
                        systolic = 135,
                        diastolic = 80,
                        pulse = 64,
                        mov = true,
                        ihb = true,
                        userSlot = BpReading.APP_OWNER_SLOT
                    ),
                    BpReading(
                        timestampEpochSec = 1_760_003_600,
                        systolic = 118,
                        diastolic = 80,
                        pulse = 0,
                        mov = false,
                        ihb = false,
                        userSlot = BpReading.APP_OWNER_SLOT
                    )
                )
            ),
            outcome
        )
    }

    @Test
    fun `bonding required means the connection step is missing`() = runTest {
        bleResult = Ble1810Result.BondingRequired("pairing did not complete")

        assertEquals(
            SourceReadResult.ConnectionRequired("pairing did not complete"),
            source().read(SourceReadRequest(SyncMode.HISTORY))
        )
    }

    @Test
    fun `timeout disconnected and generic failure keep their variants`() = runTest {
        bleResult = Ble1810Result.Timeout("connect")
        assertEquals(
            SourceReadResult.Timeout("connect"),
            source().read(SourceReadRequest(SyncMode.HISTORY))
        )

        bleResult = Ble1810Result.Disconnected("connect")
        assertEquals(
            SourceReadResult.Disconnected("connect"),
            source().read(SourceReadRequest(SyncMode.HISTORY))
        )

        bleResult = Ble1810Result.Failed("Bluetooth is switched off")
        assertEquals(
            SourceReadResult.Failed("Bluetooth is switched off"),
            source().read(SourceReadRequest(SyncMode.HISTORY))
        )
    }

    // ------------------------------------------------------------------ //
    //  Re-read idempotency (the fingerprint journal end to end)           //
    // ------------------------------------------------------------------ //

    @Test
    fun `a successful read does not consume the journal`() = runTest {
        // Task xlg.15: the commit is deferred to the caller — read() alone
        // must leave the burst re-deliverable, because the store that has
        // to confirm it runs only after read() returns (a store failure or
        // a crash before the commit must never lose readings).
        val source = source()
        bleResult = Ble1810Result.Success(listOf(measurement(120), measurement(125)), null, null, 0, 0, 0)

        val firstPass = source.read(SourceReadRequest(SyncMode.HISTORY)) as SourceReadResult.Success
        val unconfirmedReRead = source.read(SourceReadRequest(SyncMode.HISTORY)) as SourceReadResult.Success

        assertEquals(2, firstPass.readings.size)
        assertEquals(2, unconfirmedReRead.readings.size) // nothing consumed yet
    }

    @Test
    fun `commitDelivered consumes the journal, re-reads deliver nothing new`() = runTest {
        val source = source()
        bleResult = Ble1810Result.Success(listOf(measurement(120), measurement(125)), null, null, 0, 0, 0)

        val firstPass = source.read(SourceReadRequest(SyncMode.HISTORY)) as SourceReadResult.Success
        source.commitDelivered()
        val secondPass = source.read(SourceReadRequest(SyncMode.HISTORY)) as SourceReadResult.Success

        assertEquals(2, firstPass.readings.size)
        assertTrue(secondPass.readings.isEmpty())
    }

    @Test
    fun `a repeated commitDelivered does not double-count the burst`() = runTest {
        // The commit consumes the pending set: a double confirmation must
        // not journal the burst twice — over-counting would filter out a
        // genuinely new identical reading later.
        val source = source()
        bleResult = Ble1810Result.Success(listOf(measurement(120)), null, null, 0, 0, 0)
        source.read(SourceReadRequest(SyncMode.HISTORY))
        source.commitDelivered()
        source.commitDelivered()

        // One journaled occurrence: the next burst of two identical
        // readings still delivers its second member.
        bleResult = Ble1810Result.Success(
            listOf(measurement(120), measurement(120)), null, null, 0, 0, 0
        )
        val next = source.read(SourceReadRequest(SyncMode.HISTORY)) as SourceReadResult.Success

        assertEquals(1, next.readings.size)
    }

    @Test
    fun `a re-read extended by a new record delivers only the new record`() = runTest {
        val source = source()
        bleResult = Ble1810Result.Success(
            listOf(measurement(120), measurement(125)), null, null, 0, 0, 0
        )
        source.read(SourceReadRequest(SyncMode.HISTORY))
        source.commitDelivered()

        bleResult = Ble1810Result.Success(
            listOf(measurement(120), measurement(125), measurement(130)), null, null, 0, 0, 0
        )
        val secondPass = source.read(SourceReadRequest(SyncMode.HISTORY)) as SourceReadResult.Success

        assertEquals(listOf(130), secondPass.readings.map { it.systolic })
    }

    @Test
    fun `timestamp-carrying records are delivered on every pass`() = runTest {
        val source = source()
        val deviceTimed = measurement(120, timestampEpochSec = 1_760_000_000, fromDeviceClock = true)
        bleResult = Ble1810Result.Success(listOf(deviceTimed), null, null, 0, 0, 0)

        val firstPass = source.read(SourceReadRequest(SyncMode.HISTORY)) as SourceReadResult.Success
        val secondPass = source.read(SourceReadRequest(SyncMode.HISTORY)) as SourceReadResult.Success

        assertEquals(1, firstPass.readings.size)
        assertEquals(1, secondPass.readings.size) // identity is the stable device time; storage dedups
    }

    @Test
    fun `a failed pass does not consume the journal`() = runTest {
        val source = source()
        // The first pass dies before delivering anything - a failed read must
        // leave every record retriable (anchors never advance on failure).
        bleResult = Ble1810Result.Timeout("connect")
        assertTrue(source.read(SourceReadRequest(SyncMode.HISTORY)) is SourceReadResult.Timeout)

        // The retry delivers the same burst in full.
        bleResult = Ble1810Result.Success(listOf(measurement(120)), null, null, 0, 0, 0)
        val retry = source.read(SourceReadRequest(SyncMode.HISTORY)) as SourceReadResult.Success
        assertEquals(1, retry.readings.size)

        // Only the CONFIRMED retry consumed the burst.
        source.commitDelivered()
        val reRead = source.read(SourceReadRequest(SyncMode.HISTORY)) as SourceReadResult.Success
        assertTrue(reRead.readings.isEmpty())
    }

    // ------------------------------------------------------------------ //
    //  Registry resolution                                                //
    // ------------------------------------------------------------------ //

    @Test
    fun `the app registry resolves ble-standard-1810 with no core changes`() {
        val type = HealthSyncWiring.sourceRegistry().type(Ble1810Source.TYPE_ID)

        assertSame(Ble1810BpSourceType, type)
        assertEquals("ble-standard-1810", type?.typeId)
        assertEquals(R.string.bp_source_type_ble_standard_1810, type?.displayNameRes)
        assertEquals(Ble1810Source.CAPABILITIES, type?.capabilities)
        assertTrue(HealthSyncWiring.sourceRegistry().types.contains(Ble1810BpSourceType))
    }
}
