package com.zamotkin.extrahealthgarmin.devices.healthconnect

import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.devices.api.SourceCounters
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadRequest
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
import com.zamotkin.extrahealthgarmin.health.BloodPressureResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HcBpSource] tests (task tefal2garmin-app-redesign-v3-xlg.2): the
 * request/result mapping over a faked sync pass - the real
 * BloodPressureSync pipeline is not touched here. Plain JUnit, no Android.
 */
class HcBpSourceTest {

    private var passes = 0
    private var passResult: BloodPressureResult =
        BloodPressureResult(enabled = true, uploaded = 2, waiting = 3, dropped = 1)

    private val source = HcBpSource(syncPass = {
        passes++
        passResult
    })

    // ------------------------------------------------------------------ //
    //  Identity + capabilities                                            //
    // ------------------------------------------------------------------ //

    @Test
    fun `identity is health-connect with the type display name`() {
        assertEquals("health-connect", source.sourceTypeId)
        assertEquals(R.string.bp_source_type_health_connect, source.displayNameRes)
    }

    @Test
    fun `capabilities are no user attribution and current only`() {
        val capabilities = source.capabilities

        assertEquals(UserAttribution.NONE, capabilities.userAttribution)
        assertEquals(setOf(SyncMode.CURRENT), capabilities.supportedSyncModes)
        assertTrue(SyncMode.HISTORY !in capabilities.supportedSyncModes)
        assertTrue(!capabilities.requiresPairingKey)
    }

    // ------------------------------------------------------------------ //
    //  Request/result mapping                                             //
    // ------------------------------------------------------------------ //

    @Test
    fun `current request runs exactly one sync pass`() = runTest {
        source.read(SourceReadRequest(SyncMode.CURRENT))

        assertEquals(1, passes)
    }

    @Test
    fun `an enabled pass maps onto success with the pipeline counters`() = runTest {
        val outcome = source.read(SourceReadRequest(SyncMode.CURRENT))

        assertEquals(
            SourceReadResult.Success(
                readings = emptyList(),
                counters = SourceCounters(uploaded = 2, waiting = 3, dropped = 1)
            ),
            outcome
        )
    }

    @Test
    fun `a disabled pass means the permission grant is missing`() = runTest {
        passResult = BloodPressureResult.NOT_ENABLED

        val outcome = source.read(SourceReadRequest(SyncMode.CURRENT))

        assertTrue(outcome is SourceReadResult.ConnectionRequired)
    }

    @Test
    fun `history request fails without running a pass`() = runTest {
        val outcome = source.read(SourceReadRequest(SyncMode.HISTORY))

        assertTrue(outcome is SourceReadResult.Failed)
        assertEquals(0, passes)
    }

    @Test
    fun `a user slot in the request is meaningless and ignored`() = runTest {
        val outcome = source.read(SourceReadRequest(SyncMode.CURRENT, userSlot = 1))

        assertEquals(1, passes)
        assertTrue(outcome is SourceReadResult.Success)
    }
}
