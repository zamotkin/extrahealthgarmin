package com.zamotkin.extrahealthgarmin.ui.screens.settings

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.data.GarminUploadOrchestrator
import com.zamotkin.extrahealthgarmin.data.LoginStatus
import com.zamotkin.extrahealthgarmin.data.UploadOutcome
import com.zamotkin.extrahealthgarmin.devices.SourceRegistry
import com.zamotkin.extrahealthgarmin.devices.healthconnect.HealthConnectBpSourceType
import com.zamotkin.extrahealthgarmin.devices.omron.OmronBleSourceType
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSourceStore
import com.zamotkin.extrahealthgarmin.garmin.GarminWeighInRecord
import com.zamotkin.extrahealthgarmin.health.HcSdkStatus
import com.zamotkin.extrahealthgarmin.health.HcStatusSnapshot
import com.zamotkin.extrahealthgarmin.health.HealthPermissions
import com.zamotkin.extrahealthgarmin.health.HealthSyncFacade
import com.zamotkin.extrahealthgarmin.health.SyncResult
import com.zamotkin.extrahealthgarmin.omron.data.BloodPressureDao
import com.zamotkin.extrahealthgarmin.omron.data.BloodPressureRecordEntity
import com.zamotkin.extrahealthgarmin.omron.data.OmronBpRepository
import com.zamotkin.extrahealthgarmin.ui.MainUiState
import com.zamotkin.extrahealthgarmin.ui.MainViewModel
import com.zamotkin.extrahealthgarmin.ui.UploadSummary
import java.time.Duration
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
import org.mockito.Mockito.mock

/**
 * Pins [SettingsViewModel] (task tefal2garmin-app-redesign-v3-xlg.7, epic
 * R7): one state per group over the existing seams —
 *
 *  - Garmin: the group follows the shared [MainViewModel] account machine
 *    (signed-out renders the sign-in entry, signed-in the sign-out) and
 *    maps the shared last-upload line; the sign-in entry opens the shared
 *    login dialog; sign-out delegates and keeps local sync state intact;
 *  - Health Connect: granted / denied / twice-cancelled states (the HC UX
 *    guideline's stop-re-prompting rule), result delegation, unavailable
 *    provider;
 *  - Connections: the store mapping with the distinct picked slots, and
 *    the confirmed delete flows invoking exactly the deleteForUser /
 *    deleteAll DAO calls (failure surfaced, never thrown);
 *  - About: the diagnostics rows built from the real store anchors and the
 *    journal-count seams;
 *  - the relocated backfill entry runs the shared pass.
 *
 * Same harness conventions as MainViewModelUploadTest / MeasurementsViewModelTest:
 * no Robolectric, Dispatchers.Main swapped for a StandardTestDispatcher,
 * LiveData read under InstantTaskExecutorRule, the orchestrator is a
 * recording Mockito mock, the facade a hand fake, the repository the real
 * never-throws gateway over an in-memory DAO.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    // ------------------------------------------------------------------ //
    //  Fakes                                                             //
    // ------------------------------------------------------------------ //

    private class FakeStore(var sources: List<ConnectedSource>) : ConnectedSourceStore {
        var failSave = false

        override fun load(): List<ConnectedSource> = sources

        override fun save(sources: List<ConnectedSource>): Boolean {
            if (failSave) return false
            this.sources = sources
            return true
        }
    }

    /** In-memory DAO recording the destructive calls (the surface under test). */
    private class FakeBpDao : BloodPressureDao {
        val deletedForUser = mutableListOf<Int>()
        var deleteAllCalls = 0
        var failDeletes = false

        override suspend fun insertIgnoreAll(
            records: List<BloodPressureRecordEntity>
        ): List<Long> = records.map { -1L }

        override suspend fun unsyncedForUser(userSlot: Int) = emptyList<BloodPressureRecordEntity>()
        override suspend fun unsyncedAll() = emptyList<BloodPressureRecordEntity>()
        override suspend fun markSynced(timestampEpochSec: Long, userSlot: Int) = Unit
        override suspend fun latest(userSlot: Int, limit: Int) =
            emptyList<BloodPressureRecordEntity>()
        override suspend fun allForUser(userSlot: Int) = emptyList<BloodPressureRecordEntity>()
        override suspend fun allForSource(sourceId: String, userSlot: Int?) =
            emptyList<BloodPressureRecordEntity>()
        override suspend fun unsyncedForSource(sourceId: String, userSlot: Int?) =
            emptyList<BloodPressureRecordEntity>()
        override suspend fun allForSources(sourceIds: List<String>) =
            emptyList<BloodPressureRecordEntity>()

        override suspend fun deleteAll() {
            if (failDeletes) throw IllegalStateException("DAO down")
            deleteAllCalls++
        }

        override suspend fun deleteForUser(userSlot: Int) {
            if (failDeletes) throw IllegalStateException("DAO down")
            deletedForUser += userSlot
        }
    }

    // Fake facade state (the same shape as MainViewModelSyncTest's).
    private var hcSdkStatus: HcSdkStatus = HcSdkStatus.Available
    private var hcGranted: Boolean = true
    private var hcLastSyncMs: Long? = null
    private val backfillWindows = mutableListOf<Duration>()

    private val fakeFacade = object : HealthSyncFacade {
        override suspend fun status() = HcStatusSnapshot(
            hcSdkStatus,
            hcGranted && hcSdkStatus == HcSdkStatus.Available
        )

        override suspend fun sync(): SyncResult = SyncResult.Idle

        override suspend fun backfill(window: Duration): SyncResult {
            backfillWindows += window
            return SyncResult.Idle
        }

        override fun lastSyncEpochMs(): Long? = hcLastSyncMs

        override fun permissionsToRequest() = HealthPermissions.REQUIRED_PERMISSIONS
    }

    // Recording orchestrator mock state (MainViewModelUploadTest pattern).
    private var loginStatus: LoginStatus = LoginStatus.Success
    private var uploadOutcome: UploadOutcome = UploadOutcome.NotNeeded
    private var storedToken: Boolean = false
    private val signOutCalls = mutableListOf<Int>()

    private fun buildOrchestrator(): GarminUploadOrchestrator =
        mock(GarminUploadOrchestrator::class.java) { invocation ->
            when (invocation.method.name) {
                "login" -> loginStatus
                "uploadNewRecords" -> uploadOutcome
                "hasStoredToken" -> storedToken
                "signOut" -> {
                    signOutCalls.add(1)
                    null
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }

    // ------------------------------------------------------------------ //
    //  Fixtures                                                          //
    // ------------------------------------------------------------------ //

    private val omronUser1 = ConnectedSource(
        id = "omron-1",
        sourceTypeId = "omron-ble",
        name = "OMRON M7",
        address = "AA:BB:CC:DD:EE:FF",
        userSlot = 1,
        lastReadAnchor = 1_700_000_100L,
        createdAt = 1L
    )
    private val omronUser2 = ConnectedSource(
        id = "omron-2",
        sourceTypeId = "omron-ble",
        name = "OMRON M7 IT",
        address = "11:22:33:44:55:66",
        userSlot = 2,
        createdAt = 2L
    )
    private val hcSource = ConnectedSource(
        id = "hc-1",
        sourceTypeId = "health-connect",
        name = "health-connect",
        createdAt = 3L
    )

    private val dao = FakeBpDao()
    private val repository = OmronBpRepository(dao)
    private val store = FakeStore(listOf(omronUser1, omronUser2, hcSource))
    private var weighInJournalCount = 12
    private var bpJournalCount = 34

    private lateinit var mainViewModel: MainViewModel

    private fun newViewModel(): SettingsViewModel = SettingsViewModel(
        mainViewModel = mainViewModel,
        store = store,
        registry = SourceRegistry(listOf(OmronBleSourceType, HealthConnectBpSourceType)),
        bpRepository = repository,
        weighInJournalCount = { weighInJournalCount },
        bpJournalCount = { bpJournalCount },
        appVersion = "3.0.0-test",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mainViewModel = MainViewModel(buildOrchestrator(), Application(), fakeFacade)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Advances the scheduler until [predicate] holds on the shared state. */
    private fun pumpUntil(timeoutMs: Long = 10_000, predicate: (MainUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = mainViewModel.uiState.value
            if (state != null && predicate(state)) return
            Thread.sleep(20)
        }
        fail("Timed out; last shared state: ${mainViewModel.uiState.value}")
    }

    private fun pump() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    // ------------------------------------------------------------------ //
    //  Garmin group                                                      //
    // ------------------------------------------------------------------ //

    @Test
    fun `garmin group renders signed out until the account connects`() {
        val vm = newViewModel()

        val group = vm.garmin.value
        assertNotNull(group)
        assertFalse(group!!.signedIn)
        assertNull("no last-upload line before the first pass", group.lastUpload)
    }

    @Test
    fun `garmin group follows the shared account state and maps the last upload`() {
        val vm = newViewModel()

        loginStatus = LoginStatus.Success
        mainViewModel.loginToGarmin("user@example.com", "s3cret")
        pumpUntil { it.garminConnected }
        assertTrue(vm.garmin.value!!.signedIn)

        uploadOutcome = UploadOutcome.Success(uploadedCount = 2)
        mainViewModel.startGarminUpload(
            listOf(
                GarminWeighInRecord(
                    epochSecondsUtc = 1_761_350_400L, weightKg = 79.65f,
                    percentFat = 21.9f, bmi = 24.6f
                ),
                GarminWeighInRecord(
                    epochSecondsUtc = 1_761_436_800L, weightKg = 80.25f,
                    percentFat = 21.7f, bmi = 24.8f
                ),
            )
        )
        pumpUntil { it.uploadStatus == com.zamotkin.extrahealthgarmin.ui.GarminUploadStatus.SUCCESS }

        assertEquals(
            UploadSummary(R.string.upload_success, listOf(2)),
            vm.garmin.value!!.lastUpload
        )
    }

    @Test
    fun `sign-in entry opens the shared login dialog`() {
        val vm = newViewModel()

        vm.requestGarminSignIn()

        assertTrue(mainViewModel.uiState.value!!.showLoginDialog)
    }

    @Test
    fun `sign-out delegates to the shared machine and flips both tabs`() {
        loginStatus = LoginStatus.Success
        mainViewModel.loginToGarmin("user@example.com", "s3cret")
        pumpUntil { it.garminConnected }
        val vm = newViewModel()
        assertTrue(vm.garmin.value!!.signedIn)

        vm.signOutGarmin()

        assertEquals(1, signOutCalls.size)
        assertFalse(mainViewModel.uiState.value!!.garminConnected)
        assertFalse(vm.garmin.value!!.signedIn)
    }

    // ------------------------------------------------------------------ //
    //  Health Connect group                                              //
    // ------------------------------------------------------------------ //

    @Test
    fun `hc group maps the granted snapshot`() {
        mainViewModel.refreshHcStatus()
        pumpUntil { it.hcPermissionsGranted }
        val vm = newViewModel()

        val group = vm.healthConnect.value!!
        assertFalse(group.checking)
        assertTrue(group.available)
        assertTrue(group.granted)
        assertFalse(group.directToHcSettings)
    }

    @Test
    fun `hc group maps the unavailable provider`() {
        hcSdkStatus = HcSdkStatus.NotInstalled
        mainViewModel.refreshHcStatus()
        pumpUntil { it.hcAvailable == false }
        val vm = newViewModel()

        val group = vm.healthConnect.value!!
        assertFalse(group.checking)
        assertFalse(group.available)
        assertFalse(group.granted)
    }

    @Test
    fun `one denied permission result keeps prompting`() {
        hcGranted = false
        mainViewModel.refreshHcStatus()
        pumpUntil { !it.hcPermissionsGranted && it.hcAvailable == true }
        val vm = newViewModel()

        vm.onHcPermissionsResult(emptySet())

        assertFalse(vm.healthConnect.value!!.directToHcSettings)
    }

    @Test
    fun `two denied permission results direct to Health Connect settings`() {
        hcGranted = false
        mainViewModel.refreshHcStatus()
        pumpUntil { !it.hcPermissionsGranted && it.hcAvailable == true }
        val vm = newViewModel()

        vm.onHcPermissionsResult(emptySet())
        vm.onHcPermissionsResult(emptySet())

        assertTrue(
            "after two cancellations the grant must stop re-prompting",
            vm.healthConnect.value!!.directToHcSettings
        )
    }

    @Test
    fun `a granted permission result resets the streak and feeds the shared machine`() {
        hcGranted = false
        mainViewModel.refreshHcStatus()
        pumpUntil { !it.hcPermissionsGranted && it.hcAvailable == true }
        val vm = newViewModel()
        vm.onHcPermissionsResult(emptySet())
        vm.onHcPermissionsResult(emptySet())
        assertTrue(vm.healthConnect.value!!.directToHcSettings)

        vm.onHcPermissionsResult(HealthPermissions.FOREGROUND_READ_PERMISSIONS)

        assertFalse(vm.healthConnect.value!!.directToHcSettings)
        assertTrue(
            "the result must reach the shared machine (Home's status)",
            mainViewModel.uiState.value!!.hcPermissionsGranted
        )
    }

    // ------------------------------------------------------------------ //
    //  Connections group                                                 //
    // ------------------------------------------------------------------ //

    @Test
    fun `connections group lists the sources with distinct picked slots`() {
        val vm = newViewModel()

        val group = vm.connections.value!!
        assertEquals(listOf(omronUser1, omronUser2, hcSource), group.sources)
        assertEquals(listOf(1, 2), group.userSlots)
        assertNull(group.deleteResult)
    }

    @Test
    fun `refresh re-reads the store`() {
        val vm = newViewModel()
        store.sources = listOf(hcSource)

        vm.refresh()

        assertEquals(listOf(hcSource), vm.connections.value!!.sources)
        assertEquals("no picked slots left", emptyList<Int>(), vm.connections.value!!.userSlots)
    }

    @Test
    fun `confirmed delete by user invokes exactly the deleteForUser call`() {
        val vm = newViewModel()

        vm.deleteReadingsForUser(2)
        pump()

        assertEquals(listOf(2), dao.deletedForUser)
        assertEquals(0, dao.deleteAllCalls)
        assertEquals(DeleteReadingsResult.DELETED, vm.connections.value!!.deleteResult)
    }

    @Test
    fun `confirmed delete all invokes exactly the deleteAll call`() {
        val vm = newViewModel()

        vm.deleteAllReadings()
        pump()

        assertTrue(dao.deletedForUser.isEmpty())
        assertEquals(1, dao.deleteAllCalls)
        assertEquals(DeleteReadingsResult.DELETED, vm.connections.value!!.deleteResult)
    }

    @Test
    fun `a DAO failure surfaces as the failed result instead of throwing`() {
        dao.failDeletes = true
        val vm = newViewModel()

        vm.deleteAllReadings()
        pump()

        assertEquals(DeleteReadingsResult.FAILED, vm.connections.value!!.deleteResult)
    }

    // ------------------------------------------------------------------ //
    //  About group                                                       //
    // ------------------------------------------------------------------ //

    @Test
    fun `diagnostics rows are built from the journals, sync state and store anchors`() {
        hcLastSyncMs = 1_735_689_600_000L
        mainViewModel.refreshHcStatus()
        pumpUntil { it.hcLastSyncEpochMs != null }
        val vm = newViewModel()

        val group = vm.about.value!!
        assertEquals("3.0.0-test", group.appVersion)
        assertEquals(12, group.weighInJournalCount)
        assertEquals(34, group.bpJournalCount)
        assertEquals(1_735_689_600_000L, group.lastWeighInSyncEpochMs)
        assertEquals(
            listOf(
                SourceReadAnchor("omron-ble", "OMRON M7", "AA:BB:CC:DD:EE:FF", 1_700_000_100L),
                SourceReadAnchor("omron-ble", "OMRON M7 IT", "11:22:33:44:55:66", null),
                SourceReadAnchor("health-connect", "health-connect", null, null),
            ),
            group.readAnchors
        )
    }

    @Test
    fun `diagnostics follow a refresh`() {
        val vm = newViewModel()
        weighInJournalCount = 99

        vm.refresh()

        assertEquals(99, vm.about.value!!.weighInJournalCount)
    }

    // ------------------------------------------------------------------ //
    //  Relocated backfill entry                                          //
    // ------------------------------------------------------------------ //

    @Test
    fun `backfill is reachable from the settings state and runs the shared pass`() {
        mainViewModel.refreshHcStatus()
        pumpUntil { it.hcPermissionsGranted && it.hcAvailable == true }
        val vm = newViewModel()

        vm.backfill(Duration.ofDays(7))
        pump()

        assertEquals(listOf(Duration.ofDays(7)), backfillWindows)
    }
}
