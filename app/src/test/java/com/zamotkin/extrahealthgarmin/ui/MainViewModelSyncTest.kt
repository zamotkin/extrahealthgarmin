package com.zamotkin.extrahealthgarmin.ui

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.zamotkin.extrahealthgarmin.data.GarminUploadOrchestrator
import com.zamotkin.extrahealthgarmin.health.HcSdkStatus
import com.zamotkin.extrahealthgarmin.health.HcStatusSnapshot
import com.zamotkin.extrahealthgarmin.health.HealthPermissions
import com.zamotkin.extrahealthgarmin.health.HealthSyncFacade
import com.zamotkin.extrahealthgarmin.health.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import java.time.Duration

/**
 * Pins the MainViewModel Health Connect state machine (task 2b): the
 * sdkStatus/granted snapshot mapping, the syncNow result mapping (lastSync
 * and pending updates, Retryable never advancing lastSync, LoginRequired
 * surfacing the connect entry point — dialog only for manual taps) and the
 * 5-minute onResume throttle under a fake clock.
 *
 * Same harness conventions as MainViewModelUploadTest: no Robolectric,
 * Dispatchers.Main swapped for a StandardTestDispatcher, LiveData read under
 * InstantTaskExecutorRule, and the [HealthSyncFacade] is a hand fake (it is
 * already an interface — no Mockito answer dance needed for it; the
 * orchestrator mock only supplies hasStoredToken).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelSyncTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: MainViewModel

    // Fake facade state
    private var hcSdkStatus: HcSdkStatus = HcSdkStatus.Available
    private var hcGranted: Boolean = true
    private var hcBloodPressureGranted: Boolean = false
    private var hcHydrationGranted: Boolean = false
    private var hcSyncResult: SyncResult = SyncResult.Idle
    private var hcLastSyncBeforeAnySync: Long? = null
    private var hcLastSyncAfterSync: Long? = null
    private var syncCalls = 0

    /** Fake clock — tests advance it to drive the onResume throttle. */
    private var nowMs = 1_000_000L

    private val backfillWindows = mutableListOf<Duration>()

    private val fakeFacade = object : HealthSyncFacade {
        // Mirrors the production facade invariant: no granted permissions
        // without an available provider.
        override suspend fun status() =
            HcStatusSnapshot(
                hcSdkStatus,
                hcGranted && hcSdkStatus == HcSdkStatus.Available,
                bloodPressureGranted = hcBloodPressureGranted,
                hydrationGranted = hcHydrationGranted
            )

        override suspend fun sync(): SyncResult {
            syncCalls++
            return hcSyncResult
        }

        override suspend fun backfill(window: Duration): SyncResult {
            backfillWindows += window
            return hcSyncResult
        }

        override fun lastSyncEpochMs(): Long? =
            if (syncCalls > 0) hcLastSyncAfterSync else hcLastSyncBeforeAnySync

        override fun permissionsToRequest() = HealthPermissions.REQUIRED_PERMISSIONS
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val orchestrator = Mockito.mock(GarminUploadOrchestrator::class.java)
        viewModel = MainViewModel(
            garminUploader = orchestrator,
            application = Application(),
            hcFacade = fakeFacade,
            clock = { nowMs }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Advances the test scheduler until [predicate] holds on the current
     * state (same robust poll loop as MainViewModelUploadTest).
     */
    private fun pumpUntil(timeoutMs: Long = 10_000, predicate: (MainUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = viewModel.uiState.value
            if (state != null && predicate(state)) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for the sync state machine; last state: ${viewModel.uiState.value}")
    }

    /** Lets pending coroutines run to completion when no state change is expected. */
    private fun drain() {
        testDispatcher.scheduler.advanceUntilIdle()
        Thread.sleep(20)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun refreshReady() {
        viewModel.refreshHcStatus()
        pumpUntil { it.hcAvailable == true && it.hcPermissionsGranted }
    }

    // ------------------------------------------------------------------ //
    //  refreshHcStatus: sdkStatus x granted -> UI state                   //
    // ------------------------------------------------------------------ //

    @Test
    fun `refresh maps available plus granted to ready state`() {
        hcLastSyncBeforeAnySync = 42L

        refreshReady()

        val state = viewModel.uiState.value!!
        assertEquals(true, state.hcAvailable)
        assertTrue(state.hcPermissionsGranted)
        assertEquals("persisted last sync must surface on refresh", 42L, state.hcLastSyncEpochMs)
        assertFalse(state.hcSyncRunning)
    }

    @Test
    fun `refresh and permission result map the optional blood pressure and water reads`() {
        refreshReady()
        var state = viewModel.uiState.value!!
        assertFalse(state.hcBloodPressureGranted)
        assertFalse(state.hcHydrationGranted)

        hcBloodPressureGranted = true
        hcHydrationGranted = true
        refreshReady()
        pumpUntil { it.hcBloodPressureGranted && it.hcHydrationGranted }

        // A permission-screen result without them (user unticked both) clears the flags.
        hcBloodPressureGranted = false
        hcHydrationGranted = false
        viewModel.onHcPermissionsResult(HealthPermissions.REQUIRED_PERMISSIONS)
        drain()
        state = viewModel.uiState.value!!
        assertFalse(state.hcBloodPressureGranted)
        assertFalse(state.hcHydrationGranted)

        hcBloodPressureGranted = true
        viewModel.onHcPermissionsResult(
            HealthPermissions.REQUIRED_PERMISSIONS + HealthPermissions.READ_BLOOD_PRESSURE
        )
        drain()
        assertTrue(viewModel.uiState.value!!.hcBloodPressureGranted)
        assertFalse(viewModel.uiState.value!!.hcHydrationGranted)
    }

    @Test
    fun `refresh maps not installed to unavailable without permissions`() {
        hcSdkStatus = HcSdkStatus.NotInstalled
        hcGranted = false

        viewModel.refreshHcStatus()
        pumpUntil { it.hcAvailable == false }

        val state = viewModel.uiState.value!!
        assertEquals(false, state.hcAvailable)
        assertFalse(state.hcPermissionsGranted)
    }

    @Test
    fun `refresh maps needs update to unavailable`() {
        hcSdkStatus = HcSdkStatus.NeedsUpdate

        viewModel.refreshHcStatus()
        pumpUntil { it.hcAvailable == false }

        assertFalse(viewModel.uiState.value!!.hcPermissionsGranted)
    }

    @Test
    fun `refresh maps available without grant to needs permission`() {
        hcGranted = false

        viewModel.refreshHcStatus()
        pumpUntil { it.hcAvailable == true }

        val state = viewModel.uiState.value!!
        assertEquals(true, state.hcAvailable)
        assertFalse(state.hcPermissionsGranted)
    }

    @Test
    fun `permission result re-derives the granted flag from the foreground set`() {
        hcGranted = false
        viewModel.refreshHcStatus()
        pumpUntil { it.hcAvailable == true }

        // Full grant (foreground + background): granted, and the follow-up
        // status refresh (provider truth) agrees.
        hcGranted = true
        viewModel.onHcPermissionsResult(HealthPermissions.REQUIRED_PERMISSIONS)
        pumpUntil { it.hcPermissionsGranted }

        // Grant without the optional background read: still granted.
        viewModel.onHcPermissionsResult(HealthPermissions.FOREGROUND_READ_PERMISSIONS)
        drain()
        assertTrue(viewModel.uiState.value!!.hcPermissionsGranted)

        // Partial grant (provider truth back to not granted): not granted.
        hcGranted = false
        viewModel.onHcPermissionsResult(setOf(HealthPermissions.READ_WEIGHT))
        pumpUntil { !it.hcPermissionsGranted }
    }

    // ------------------------------------------------------------------ //
    //  syncNow: SyncResult -> UI state                                    //
    // ------------------------------------------------------------------ //

    @Test
    fun `syncNow success updates last sync and pending count`() {
        hcSyncResult = SyncResult.Uploaded(2)
        hcLastSyncAfterSync = nowMs
        refreshReady()

        viewModel.syncNow()
        pumpUntil { it.uploadStatus == GarminUploadStatus.SUCCESS }

        val state = viewModel.uiState.value!!
        assertEquals("last sync must come from the (advanced) state store", nowMs, state.hcLastSyncEpochMs)
        assertEquals(0, state.hcPendingCount)
        assertEquals(2, state.uploadedCount)
        assertEquals(2, state.totalRecords)
        assertFalse(state.hcSyncRunning)
        assertNull(state.hcSyncError)
    }

    @Test
    fun `backfill runs the facade backfill with the chosen window`() {
        hcSyncResult = SyncResult.Uploaded(3)
        refreshReady()

        viewModel.backfill(Duration.ofDays(7))
        pumpUntil { it.uploadStatus == GarminUploadStatus.SUCCESS }

        assertEquals(listOf(Duration.ofDays(7)), backfillWindows)
        assertEquals("backfill must not run a regular sync pass", 0, syncCalls)
        assertEquals(3, viewModel.uiState.value!!.uploadedCount)
        assertFalse(viewModel.uiState.value!!.hcSyncRunning)
    }

    @Test
    fun `syncNow idle maps to not needed and clears pending`() {
        hcSyncResult = SyncResult.Idle
        hcLastSyncAfterSync = nowMs
        refreshReady()

        viewModel.syncNow()
        pumpUntil { it.uploadStatus == GarminUploadStatus.NOT_NEEDED }

        assertEquals(0, viewModel.uiState.value!!.hcPendingCount)
    }

    @Test
    fun `syncNow partial reports pending failures`() {
        hcSyncResult = SyncResult.PartialUploaded(1, listOf("row 2 rejected by Garmin"))
        hcLastSyncAfterSync = nowMs
        refreshReady()

        viewModel.syncNow()
        pumpUntil { it.uploadStatus == GarminUploadStatus.PARTIAL }

        val state = viewModel.uiState.value!!
        assertEquals("pending count is the failed remainder", 1, state.hcPendingCount)
        assertEquals(1, state.uploadedCount)
        assertEquals("total must be uploaded plus failed", 2, state.totalRecords)
        assertEquals(listOf("row 2 rejected by Garmin"), state.uploadMessages)
    }

    @Test
    fun `syncNow retryable sets sync error WITHOUT advancing last sync`() {
        hcSyncResult = SyncResult.Retryable
        hcLastSyncBeforeAnySync = 500L
        // The engine leaves the store untouched on Retryable — the facade
        // therefore still reports the old timestamp after the failed pass.
        hcLastSyncAfterSync = 500L
        refreshReady()

        viewModel.syncNow()
        pumpUntil { it.uploadStatus == GarminUploadStatus.RETRYABLE }

        val state = viewModel.uiState.value!!
        assertNotNull("Retryable must set the sync error marker", state.hcSyncError)
        assertEquals(MainViewModel.HC_SYNC_ERROR_RETRYABLE, state.hcSyncError)
        assertEquals(
            "last sync must NOT advance past a retryable pass",
            500L,
            state.hcLastSyncEpochMs
        )
    }

    @Test
    fun `syncNow login required opens the login dialog for the manual tap`() {
        hcSyncResult = SyncResult.LoginRequired
        refreshReady()

        viewModel.syncNow()
        pumpUntil { it.uploadStatus == GarminUploadStatus.LOGIN_REQUIRED }

        val state = viewModel.uiState.value!!
        assertTrue("manual LoginRequired must open the login dialog", state.showLoginDialog)
        assertFalse("connect entry point must reappear", state.garminConnected)
        assertEquals(MainViewModel.HC_SYNC_ERROR_LOGIN_REQUIRED, state.hcSyncError)
        assertNull("nothing was persisted, last sync stays unknown", state.hcLastSyncEpochMs)
    }

    @Test
    fun `syncNow facade exception maps to sync error without advancing last sync`() {
        var throwOnSync = true
        val crashingFacade = object : HealthSyncFacade {
            override suspend fun status() = HcStatusSnapshot(HcSdkStatus.Available, true)
            override suspend fun sync(): SyncResult {
                syncCalls++
                if (throwOnSync) throw IllegalStateException("provider died")
                return SyncResult.Idle
            }
            override suspend fun backfill(window: Duration): SyncResult = sync()
            override fun lastSyncEpochMs(): Long? = null
            override fun permissionsToRequest() = HealthPermissions.REQUIRED_PERMISSIONS
        }
        val orchestrator = Mockito.mock(GarminUploadOrchestrator::class.java)
        viewModel = MainViewModel(orchestrator, Application(), crashingFacade, { nowMs })
        viewModel.refreshHcStatus()
        pumpUntil { it.hcAvailable == true }

        viewModel.syncNow()
        pumpUntil { it.hcSyncError != null }

        val state = viewModel.uiState.value!!
        assertEquals("provider died", state.hcSyncError)
        assertFalse(state.hcSyncRunning)
        assertNull(state.hcLastSyncEpochMs)
    }

    @Test
    fun `syncNow without hc availability is a no-op`() {
        hcSdkStatus = HcSdkStatus.NotInstalled
        hcGranted = false
        viewModel.refreshHcStatus()
        pumpUntil { it.hcAvailable == false }

        viewModel.syncNow()
        drain()

        assertEquals("no sync may start while HC is unavailable", 0, syncCalls)
    }

    // ------------------------------------------------------------------ //
    //  onScreenResume: auto sync + 5-minute throttle                       //
    // ------------------------------------------------------------------ //

    @Test
    fun `onScreenResume auto syncs when never synced and never pops the login dialog`() {
        hcSyncResult = SyncResult.LoginRequired
        hcLastSyncAfterSync = null

        viewModel.onScreenResume()
        pumpUntil { it.uploadStatus == GarminUploadStatus.LOGIN_REQUIRED }

        val state = viewModel.uiState.value!!
        assertEquals(1, syncCalls)
        assertFalse(
            "auto pass must never pop the login dialog — connect button instead",
            state.showLoginDialog
        )
        assertFalse(state.garminConnected)
    }

    @Test
    fun `onScreenResume throttles the second call within 5 minutes`() {
        hcSyncResult = SyncResult.Idle
        hcLastSyncAfterSync = nowMs

        viewModel.onScreenResume()
        pumpUntil { it.uploadStatus == GarminUploadStatus.NOT_NEEDED }
        assertEquals(1, syncCalls)

        nowMs += 60_000 // 1 minute later — inside the throttle window
        viewModel.onScreenResume()
        drain()

        assertEquals("second resume within 5 minutes must not sync again", 1, syncCalls)
    }

    @Test
    fun `onScreenResume syncs again after the 5-minute window`() {
        hcSyncResult = SyncResult.Idle
        hcLastSyncAfterSync = nowMs

        viewModel.onScreenResume()
        pumpUntil { it.uploadStatus == GarminUploadStatus.NOT_NEEDED }

        nowMs += MainViewModel.RESUME_SYNC_THROTTLE_MS + 1
        viewModel.onScreenResume()
        pumpUntil { syncCalls == 2 }

        assertEquals(2, syncCalls)
    }

    @Test
    fun `onScreenResume skips syncing while permissions are missing`() {
        hcGranted = false

        viewModel.onScreenResume()
        drain()

        assertEquals("no sync may start without the read permissions", 0, syncCalls)
        assertFalse(viewModel.uiState.value!!.hcPermissionsGranted)
    }

    @Test
    fun `permissionsToRequest delegates to the facade`() {
        assertEquals(
            HealthPermissions.REQUIRED_PERMISSIONS,
            viewModel.permissionsToRequest()
        )
    }
}
