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
    private val bleRead: suspend (Context, ByteArray, Int) -> OmronBleResult =
        { _, _, slot ->
            bleCalls++
            lastBleSlot = slot
            bleError?.let { throw it }
            bleResult
        }

    private val keyProvider: () -> ByteArray = { ByteArray(16) { it.toByte() } }

    private val dao = FakeBloodPressureDao()
    private val repository = OmronBpRepository(dao)

    private var uploadOutcome: UploadOutcome = UploadOutcome.NotNeeded
    private var uploadCalls = 0
    private val coordinator = BpSyncCoordinator(
        repository = repository,
        upload = { readings ->
            uploadCalls++
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
