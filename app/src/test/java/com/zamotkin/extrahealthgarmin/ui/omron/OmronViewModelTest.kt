package com.zamotkin.extrahealthgarmin.ui.omron

import android.app.Application
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.data.UploadOutcome
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import com.zamotkin.extrahealthgarmin.omron.ble.OmronBleResult
import com.zamotkin.extrahealthgarmin.omron.data.BloodPressureDao
import com.zamotkin.extrahealthgarmin.omron.data.BloodPressureRecordEntity
import com.zamotkin.extrahealthgarmin.omron.data.BpSyncCoordinator
import com.zamotkin.extrahealthgarmin.omron.data.OmronBpRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Pins the Omron blood-pressure UI state machine (task tefal2garmin-mnb):
 * the OmronBleResult -> OmronUiState mapping, the permissions gate, the
 * store -> optional-upload pass, slot switching, and the never-throws
 * contract on dependency failure.
 *
 * Same harness conventions as ui.MainViewModelSyncTest: no Robolectric,
 * Dispatchers.Main swapped for a StandardTestDispatcher, LiveData read under
 * InstantTaskExecutorRule, hand fakes for every injected dependency. The
 * repository and coordinator are the REAL omron/data classes built over an
 * in-memory [BloodPressureDao] fake and a fake upload lambda — the ViewModel
 * is thin, so pinning it against its real collaborators catches wiring bugs
 * the pure-fake variant would miss.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OmronViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: OmronViewModel

    // --- injected-dependency fakes ------------------------------------ //

    private var bleResult: OmronBleResult = OmronBleResult.Success(emptyList())
    private var bleError: Exception? = null
    private var bleCalls = 0
    private var lastBleSlot = -1
    private var lastBleFullRead: Boolean? = null
    private val bleRead: suspend (Context, ByteArray, Int, Boolean) -> OmronBleResult =
        { _, _, slot, fullRead ->
            bleCalls++
            lastBleSlot = slot
            lastBleFullRead = fullRead
            bleError?.let { throw it }
            bleResult
        }

    private val keyProvider: () -> ByteArray = { ByteArray(16) { it.toByte() } }

    private val dao = FakeBloodPressureDao()
    private val repository = OmronBpRepository(dao)

    private var uploadOutcome: UploadOutcome = UploadOutcome.NotNeeded
    private var uploadCalls = 0
    private var lastUploaded: List<BloodPressureReading>? = null
    private val coordinator = BpSyncCoordinator(
        repository = repository,
        upload = { readings ->
            uploadCalls++
            lastUploaded = readings
            uploadOutcome
        }
    )

    private var missing: List<String> = emptyList()
    private val missingPermissions: (Context) -> List<String> = { missing }

    private val settings = FakeOmronSettings()

    private var reloginCalls = 0
    private val onReloginGarmin: () -> Unit = { reloginCalls++ }

    private class FakeOmronSettings : OmronSettingsStore {
        var slot = 1
        var auto = true
        override fun userSlot() = slot
        override fun setUserSlot(slot: Int) {
            this.slot = coerceUserSlot(slot)
        }
        override fun autoSync() = auto
        override fun setAutoSync(enabled: Boolean) {
            auto = enabled
        }
    }

    /** In-memory [BloodPressureDao] mirroring the DAO's contract. */
    private class FakeBloodPressureDao : BloodPressureDao {
        val rows = mutableListOf<BloodPressureRecordEntity>()

        /** When set, deleteAll throws — the never-throws gateway must swallow it. */
        var failure: Exception? = null

        override suspend fun deleteAll() {
            failure?.let { throw it }
            rows.clear()
        }

        override suspend fun deleteForUser(userSlot: Int) {
            failure?.let { throw it }
            rows.removeAll { it.userSlot == userSlot }
        }

        override suspend fun insertIgnoreAll(
            records: List<BloodPressureRecordEntity>
        ): List<Long> = records.map { record ->
            val exists = rows.any {
                it.timestampEpochSec == record.timestampEpochSec &&
                    it.userSlot == record.userSlot
            }
            if (exists) {
                -1L
            } else {
                rows += record.copy(synced = false)
                rows.size.toLong()
            }
        }

        override suspend fun unsyncedForUser(userSlot: Int): List<BloodPressureRecordEntity> =
            rows.filter { it.userSlot == userSlot && !it.synced }
                .sortedBy { it.timestampEpochSec }

        override suspend fun unsyncedAll(): List<BloodPressureRecordEntity> =
            rows.filter { !it.synced }.sortedBy { it.timestampEpochSec }

        override suspend fun markSynced(timestampEpochSec: Long, userSlot: Int) {
            val index = rows.indexOfFirst {
                it.timestampEpochSec == timestampEpochSec && it.userSlot == userSlot
            }
            if (index >= 0) rows[index] = rows[index].copy(synced = true)
        }

        override suspend fun latest(userSlot: Int, limit: Int): List<BloodPressureRecordEntity> =
            rows.filter { it.userSlot == userSlot }
                .sortedByDescending { it.timestampEpochSec }
                .take(limit)

        override suspend fun allForUser(userSlot: Int): List<BloodPressureRecordEntity> =
            rows.filter { it.userSlot == userSlot }
                .sortedByDescending { it.timestampEpochSec }

        // Source-scoped queries (task tefal2garmin-app-redesign-v3-xlg.4):
        // the legacy view model never calls them, but the interface must be whole.

        override suspend fun allForSource(
            sourceId: String,
            userSlot: Int?,
        ): List<BloodPressureRecordEntity> =
            rows.filter { it.sourceId == sourceId && (userSlot == null || it.userSlot == userSlot) }
                .sortedByDescending { it.timestampEpochSec }

        override suspend fun unsyncedForSource(
            sourceId: String,
            userSlot: Int?,
        ): List<BloodPressureRecordEntity> =
            rows.filter {
                it.sourceId == sourceId && !it.synced &&
                    (userSlot == null || it.userSlot == userSlot)
            }
                .sortedBy { it.timestampEpochSec }
    }

    // --- helpers -------------------------------------------------------- //

    private fun reading(timestamp: Long, slot: Int = 1) = BloodPressureReading(
        timestampEpochSec = timestamp,
        systolic = 120,
        diastolic = 80,
        pulse = 65,
        mov = false,
        ihb = false,
        userSlot = slot
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        createViewModel()
    }

    private fun createViewModel() {
        viewModel = OmronViewModel(
            application = Application(),
            bleRead = bleRead,
            keyProvider = keyProvider,
            repository = repository,
            coordinator = coordinator,
            missingPermissions = missingPermissions,
            settings = settings,
            onReloginGarmin = onReloginGarmin,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Settings delete-all: wipes every stored row of BOTH slots and reloads the list. */
    @Test
    fun `delete_all_wipes_both_slots_and_reloads_the_dashboard_list`() {
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 1, systolic = 120, diastolic = 80, pulse = 65,
            mov = false, ihb = false, userSlot = 1
        )
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 2, systolic = 125, diastolic = 85, pulse = 70,
            mov = false, ihb = false, userSlot = 1
        )
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 3, systolic = 110, diastolic = 70, pulse = 60,
            mov = false, ihb = false, userSlot = 2
        )

        viewModel.deleteAllReadings()
        drain()

        assertEquals("rows of both slots wiped", 0, dao.rows.size)
        assertEquals(emptyList<BloodPressureReading>(), viewModel.latestReadings.value)
    }

    /**
     * Delete-all on a DAO failure is silent by design (never-throws gateway,
     * no dedicated error state): the rows survive and the reloaded list still
     * shows them.
     */
    @Test
    fun `delete_all_dao_failure_is_silent_and_keeps_rows`() {
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 1, systolic = 120, diastolic = 80, pulse = 65,
            mov = false, ihb = false, userSlot = 1
        )
        dao.failure = IllegalStateException("disk gone")

        viewModel.deleteAllReadings()
        drain()

        assertEquals("delete-all failure must not wipe rows", 1, dao.rows.size)
        assertEquals(1, viewModel.latestReadings.value?.size ?: 0)
    }

    /**
     * Settings per-profile delete (task tefal2garmin-7m2): wipes ONLY the
     * selected slot's rows, reloads the (now empty) list of that slot.
     */
    @Test
    fun `delete_selected_slot_wipes_only_that_slot_and_reloads_the_list`() {
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 1, systolic = 120, diastolic = 80, pulse = 65,
            mov = false, ihb = false, userSlot = 1
        )
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 2, systolic = 125, diastolic = 85, pulse = 70,
            mov = false, ihb = false, userSlot = 1
        )
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 3, systolic = 110, diastolic = 70, pulse = 60,
            mov = false, ihb = false, userSlot = 2
        )

        viewModel.deleteReadingsOfSelectedSlot() // slot 1 is selected by default
        drain()

        assertEquals("only slot 1 rows wiped; slot 2 is another person", 1, dao.rows.size)
        assertEquals(2, dao.rows.single().userSlot)
        assertEquals(emptyList<BloodPressureReading>(), viewModel.latestReadings.value)
    }

    /** The per-profile delete follows the selected slot: slot 2 wipes slot 2 only. */
    @Test
    fun `delete_selected_slot_follows_the_selection_to_slot_2`() {
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 1, systolic = 120, diastolic = 80, pulse = 65,
            mov = false, ihb = false, userSlot = 1
        )
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 3, systolic = 110, diastolic = 70, pulse = 60,
            mov = false, ihb = false, userSlot = 2
        )
        viewModel.selectUserSlot(2)
        drain()

        viewModel.deleteReadingsOfSelectedSlot()
        drain()

        assertEquals("slot 1 rows survive a slot-2 delete", 1, dao.rows.size)
        assertEquals(1, dao.rows.single().userSlot)
        // the reloaded list serves the selected (now empty) slot 2
        assertEquals(emptyList<BloodPressureReading>(), viewModel.latestReadings.value)
    }

    /**
     * Same silent-failure contract as delete-all: a DAO failure wipes nothing
     * and surfaces no error state — the reloaded list keeps the rows.
     */
    @Test
    fun `delete_selected_slot_dao_failure_is_silent_and_keeps_rows`() {
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 1, systolic = 120, diastolic = 80, pulse = 65,
            mov = false, ihb = false, userSlot = 1
        )
        dao.failure = IllegalStateException("disk gone")

        viewModel.deleteReadingsOfSelectedSlot()
        drain()

        assertEquals("slot-delete failure must not wipe rows", 1, dao.rows.size)
        assertEquals(1, viewModel.latestReadings.value?.size ?: 0)
    }

    /** Advances the scheduler until [predicate] holds on the current state. */
    private fun pumpUntil(timeoutMs: Long = 10_000, predicate: (OmronUiState?) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (predicate(viewModel.uiState.value)) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for the Omron state machine; last state: ${viewModel.uiState.value}")
    }

    /** Runs pending coroutines to completion when no state change is expected. */
    private fun drain() {
        testDispatcher.scheduler.advanceUntilIdle()
        Thread.sleep(20)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    // --- transfer + upload pass ------------------------------------------ //

    @Test
    fun happyPath_storesReadings_uploadsWhenAutoSync_endsInSuccess() {
        bleResult = OmronBleResult.Success(listOf(reading(1_000), reading(2_000)))

        viewModel.syncNow()
        pumpUntil { it is OmronUiState.Success }

        assertEquals(1, bleCalls)
        assertEquals(2, dao.rows.size)
        assertEquals(1, uploadCalls) // autoSync defaults to on + unsynced rows exist
        assertEquals(OmronUiState.Success(2), viewModel.uiState.value)
        assertEquals(2, viewModel.latestReadings.value?.size)
    }

    @Test
    fun autoSync_uploadsOnlyTheSelectedSlotsPendingRecords() {
        // A slot-1 reading parked from an earlier pass — a different person
        // on the M7 (task tefal2garmin-db5); it must not reach Garmin.
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 500, systolic = 110, diastolic = 70, pulse = 60,
            mov = false, ihb = false, userSlot = 1
        )
        viewModel.selectUserSlot(2)
        bleResult = OmronBleResult.Success(listOf(reading(1_000, slot = 2)))

        viewModel.syncNow()
        pumpUntil { it is OmronUiState.Success }

        assertEquals(1, uploadCalls)
        assertEquals(
            "only the selected slot's pending readings reach the upload",
            listOf(1_000L),
            lastUploaded?.map { it.timestampEpochSec }
        )
        assertFalse("the other slot's reading stays pending", dao.rows.single { it.userSlot == 1 }.synced)
    }

    @Test
    fun autoSyncOff_skipsUpload_stillSucceeds() {
        settings.auto = false
        createViewModel() // reads autoSync() at construction
        bleResult = OmronBleResult.Success(listOf(reading(1_000)))

        viewModel.syncNow()
        pumpUntil { it is OmronUiState.Success }

        assertEquals(0, uploadCalls)
        assertEquals(OmronUiState.Success(1), viewModel.uiState.value)
    }

    @Test
    fun duplicateReadings_neverReachUploadTwice() {
        bleResult = OmronBleResult.Success(listOf(reading(1_000)))
        viewModel.syncNow()
        pumpUntil { it is OmronUiState.Success }
        assertEquals(1, uploadCalls)

        // Re-read of the same EEPROM block: nothing new, upload not needed.
        viewModel.syncNow()
        pumpUntil { it is OmronUiState.Success && viewModel.latestReadings.value?.size == 1 }
        assertEquals(1, uploadCalls) // unsynced empty -> NotNeeded, no upload call
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun authFailed_mapsToErrorState() {
        bleResult = OmronBleResult.AuthFailed("unlock rejected")

        viewModel.syncNow()
        pumpUntil { it is OmronUiState.Error }

        val error = viewModel.uiState.value as OmronUiState.Error
        assertEquals(R.string.omron_error_auth, error.messageRes)
        assertEquals("unlock rejected", error.detail)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun bondingRequired_mapsToPairingState() {
        bleResult = OmronBleResult.BondingRequired("bond missing")

        viewModel.syncNow()
        pumpUntil { it is OmronUiState.Pairing }

        assertEquals(
            OmronUiState.Pairing(OmronPairingStep.SYSTEM_BONDING),
            viewModel.uiState.value
        )
    }

    @Test
    fun permissionsMissing_blocksPass_beforeAnyBleCall() {
        missing = listOf("android.permission.BLUETOOTH_SCAN")

        viewModel.syncNow()
        drain()

        val state = viewModel.uiState.value
        assertTrue(state is OmronUiState.PermissionsMissing)
        assertEquals(0, bleCalls)
        assertEquals(0, uploadCalls)

        // Grant lands -> gate re-opens without touching the BLE stack.
        missing = emptyList()
        viewModel.onPermissionsResult()
        assertEquals(OmronUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun missingPermissionsAtConstruction_surfaceImmediately() {
        missing = listOf("android.permission.ACCESS_FINE_LOCATION")
        createViewModel()

        assertEquals(OmronUiState.PermissionsMissing(listOf("android.permission.ACCESS_FINE_LOCATION")), viewModel.uiState.value)
    }

    @Test
    fun bleThrows_mapsToUnexpectedError_neverThrows() {
        bleError = RuntimeException("boom")

        viewModel.syncNow()
        pumpUntil { it is OmronUiState.Error }

        val error = viewModel.uiState.value as OmronUiState.Error
        assertEquals(R.string.omron_error_unexpected, error.messageRes)
        assertEquals("boom", error.detail)
    }

    @Test
    fun uploadRetryable_mapsToError_readingsStayStored() {
        bleResult = OmronBleResult.Success(listOf(reading(1_000)))
        uploadOutcome = UploadOutcome.Retryable("no network")

        viewModel.syncNow()
        pumpUntil { it is OmronUiState.Error }

        val error = viewModel.uiState.value as OmronUiState.Error
        assertEquals(R.string.omron_error_sync_retryable, error.messageRes)
        assertEquals("no network", error.detail)
        assertEquals(1, dao.rows.size) // read persisted, stays retriable
        assertEquals(1, uploadCalls)
    }

    @Test
    fun uploadLoginRequired_mapsToError() {
        bleResult = OmronBleResult.Success(listOf(reading(1_000)))
        uploadOutcome = UploadOutcome.LoginRequired

        viewModel.syncNow()
        pumpUntil { it is OmronUiState.Error }

        assertEquals(
            OmronUiState.Error(R.string.omron_error_garmin_login_required),
            viewModel.uiState.value
        )
    }

    @Test
    fun uploadBpPartial_mapsToSyncPartialError() {
        bleResult = OmronBleResult.Success(listOf(reading(1_000), reading(2_000)))
        uploadOutcome = UploadOutcome.BpUploaded(
            confirmedRecords = listOf(reading(1_000)),
            failureMessages = listOf("invalid reading")
        )

        viewModel.syncNow()
        pumpUntil { it is OmronUiState.Error }

        val error = viewModel.uiState.value as OmronUiState.Error
        assertEquals(R.string.omron_error_sync_partial, error.messageRes)
        assertEquals("invalid reading", error.detail)
    }

    @Test
    fun secondTapWhileReading_isIgnored() {
        bleResult = OmronBleResult.Success(listOf(reading(1_000)))
        viewModel.syncNow() // launched, paused on the test dispatcher
        viewModel.syncNow() // re-entry guard must swallow this
        drain()

        assertEquals(1, bleCalls)
    }

    // --- full-history pass (task tefal2garmin-8rb) ------------------------- //

    @Test
    fun syncNow_passesFullReadFalse() {
        bleResult = OmronBleResult.Success(listOf(reading(1_000)))

        viewModel.syncNow()
        pumpUntil { it is OmronUiState.Success }

        assertEquals(false, lastBleFullRead)
    }

    @Test
    fun syncFullHistory_passesFullReadTrue_andRunsTheSamePass() {
        bleResult = OmronBleResult.Success(listOf(reading(1_000)))

        viewModel.syncFullHistory()
        pumpUntil { it is OmronUiState.Success }

        assertEquals(1, bleCalls)
        assertEquals(true, lastBleFullRead)
        // the pass is the same one as syncNow: store + auto-upload + Success
        assertEquals(1, dao.rows.size)
        assertEquals(1, uploadCalls)
        assertEquals(OmronUiState.Success(1), viewModel.uiState.value)
        assertEquals(1, viewModel.latestReadings.value?.size)
    }

    @Test
    fun syncFullHistory_obeysThePermissionsGate() {
        missing = listOf("android.permission.BLUETOOTH_SCAN")

        viewModel.syncFullHistory()
        drain()

        assertTrue(viewModel.uiState.value is OmronUiState.PermissionsMissing)
        assertEquals(0, bleCalls)
    }

    /**
     * Same job slot as syncNow (task tefal2garmin-8rb): only one BLE pass is
     * ever in flight — a full-history pass blocks a plain pass, and a running
     * plain pass blocks the full-history one.
     */
    @Test
    fun syncFullHistory_sharesTheJobSlotWithSyncNow() {
        bleResult = OmronBleResult.Success(listOf(reading(1_000)))
        viewModel.syncFullHistory() // launched, paused on the test dispatcher
        viewModel.syncNow()         // must be swallowed by the shared guard
        drain()
        assertEquals(1, bleCalls)
        assertEquals(true, lastBleFullRead)

        // and the other direction: one completed plain pass (2nd call), then a
        // plain pass paused on the dispatcher blocks the full-history tap
        viewModel.syncNow()
        pumpUntil { it is OmronUiState.Success }
        assertEquals(2, bleCalls)
        viewModel.syncNow()         // launched, paused on the test dispatcher
        viewModel.syncFullHistory() // must be swallowed by the shared guard
        drain()
        assertEquals(3, bleCalls)   // the 3rd pass is the plain one, not a second full read
        assertEquals(false, lastBleFullRead)
    }

    // --- settings + list -------------------------------------------------- //

    @Test
    fun slotSwitch_reloadsLatestAndDrivesBleSlot() {
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 100, systolic = 120, diastolic = 80, pulse = 60,
            mov = false, ihb = false, userSlot = 1
        )
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 200, systolic = 130, diastolic = 85, pulse = 70,
            mov = true, ihb = false, userSlot = 2
        )
        createViewModel()
        drain()
        assertEquals(1, viewModel.latestReadings.value?.size) // slot 1 default

        viewModel.selectUserSlot(2)
        pumpUntil { viewModel.latestReadings.value?.any { it.timestampEpochSec == 200L } == true }
        assertEquals(2, viewModel.userSlot.value)

        // The next BLE read serves the newly selected slot.
        bleResult = OmronBleResult.Success(emptyList())
        viewModel.syncNow()
        drain()
        assertEquals(2, lastBleSlot)

        // Out-of-range values coerce to the default slot.
        viewModel.selectUserSlot(7)
        assertEquals(1, viewModel.userSlot.value)
    }

    @Test
    fun latestReadings_showFullStoredHistory_beyondTheOld20Cap() {
        // 25 rows of slot 1 — one more than the old 20-row dashboard cap.
        (1..25).forEach { index ->
            dao.rows += BloodPressureRecordEntity(
                timestampEpochSec = index * 100L, systolic = 120, diastolic = 80, pulse = 60,
                mov = false, ihb = false, userSlot = 1
            )
        }
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 2_600, systolic = 130, diastolic = 85, pulse = 70,
            mov = true, ihb = false, userSlot = 2
        )

        createViewModel()
        drain()

        assertEquals(25, viewModel.latestReadings.value?.size) // slot 1 only, no cap
        assertEquals(2_500L, viewModel.latestReadings.value?.first()?.timestampEpochSec)
    }

    @Test
    fun setAutoSync_persistsThroughSettingsStore() {
        viewModel.setAutoSync(false)
        assertEquals(false, settings.auto)
        assertEquals(false, viewModel.autoSyncEnabled.value)

        viewModel.setAutoSync(true)
        assertEquals(true, settings.auto)
        assertEquals(true, viewModel.autoSyncEnabled.value)
    }

    @Test
    fun reloginGarmin_delegatesToCallback() {
        viewModel.reloginGarmin()
        assertEquals(1, reloginCalls)
    }
}
