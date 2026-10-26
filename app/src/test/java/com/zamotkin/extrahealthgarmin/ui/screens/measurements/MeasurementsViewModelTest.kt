package com.zamotkin.extrahealthgarmin.ui.screens.measurements

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.data.UploadOutcome
import com.zamotkin.extrahealthgarmin.devices.SourceReadOutcome
import com.zamotkin.extrahealthgarmin.devices.SourceRegistry
import com.zamotkin.extrahealthgarmin.devices.api.BpReading
import com.zamotkin.extrahealthgarmin.devices.api.SourceCounters
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.healthconnect.HealthConnectBpSourceType
import com.zamotkin.extrahealthgarmin.devices.omron.OmronBleSourceType
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSourceStore
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import com.zamotkin.extrahealthgarmin.omron.data.BloodPressureDao
import com.zamotkin.extrahealthgarmin.omron.data.BloodPressureRecordEntity
import com.zamotkin.extrahealthgarmin.omron.data.OmronBpRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Pins [MeasurementsViewModel] (task tefal2garmin-app-redesign-v3-xlg.6),
 * the single BP page that absorbs the old Omron dashboard: the epic-R8
 * tab gate (no sources => hidden, >=1 => visible), the day-grouped
 * cross-source list mapping (R4 slot filter, removed sources invisible,
 * newest-first order, day headers, distinct synced states, the capped
 * window with "show more"), the read-now mapping over every
 * [SourceReadResult] variant (ConnectionRequired discriminated by the
 * source's type shape, task xlg.8) plus the autosync post-read upload,
 * the per-source autosync toggle persistence, and the no-crash contract
 * (broken seam, cancelled pass).
 *
 * Same harness conventions as MainViewModelSyncTest: no Robolectric,
 * Dispatchers.Main swapped for a StandardTestDispatcher, LiveData read
 * under InstantTaskExecutorRule. The repository is the real never-throws
 * gateway over an in-memory DAO (SourceReadUseCaseTest pattern); the read
 * and upload passes are hand fakes; the store is the SourcesViewModelTest
 * fake shape. Real BLE runs only in the final on-device verification task.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementsViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    /** Fixed zone so day-grouping assertions never depend on the host. */
    private val zone = ZoneId.of("UTC")

    // ------------------------------------------------------------------ //
    //  Fakes                                                             //
    // ------------------------------------------------------------------ //

    private class FakeStore(var sources: List<ConnectedSource>) : ConnectedSourceStore {
        val saved = mutableListOf<List<ConnectedSource>>()
        var failSave = false

        override fun load(): List<ConnectedSource> = sources

        override fun save(sources: List<ConnectedSource>): Boolean {
            if (failSave) return false
            saved += sources
            this.sources = sources
            return true
        }
    }

    /**
     * In-memory [BloodPressureDao] honoring the @Insert(IGNORE) contract
     * (unique key = timestampEpochSec + userSlot + sourceId). Only the
     * insert and the unified query surface are exercised here - the rest
     * of the query surface is pinned by [OmronBpRepositoryTest].
     */
    private class FakeBpDao : BloodPressureDao {
        val rows = mutableListOf<BloodPressureRecordEntity>()
        private var nextId = 1L

        override suspend fun insertIgnoreAll(
            records: List<BloodPressureRecordEntity>
        ): List<Long> = records.map { record ->
            val duplicate = rows.any {
                it.timestampEpochSec == record.timestampEpochSec &&
                    it.userSlot == record.userSlot &&
                    it.sourceId == record.sourceId
            }
            if (duplicate) {
                -1L
            } else {
                val stored = record.copy(id = nextId++)
                rows.add(stored)
                stored.id
            }
        }

        override suspend fun allForSources(sourceIds: List<String>): List<BloodPressureRecordEntity> =
            rows.filter { it.sourceId in sourceIds }
                .sortedByDescending { it.timestampEpochSec }

        override suspend fun unsyncedForUser(userSlot: Int) = emptyList<BloodPressureRecordEntity>()
        override suspend fun unsyncedAll() = emptyList<BloodPressureRecordEntity>()

        /** Mirrors the DAO SQL: every source's row with that key flips. */
        override suspend fun markSynced(timestampEpochSec: Long, userSlot: Int) {
            rows.indices.forEach { index ->
                val row = rows[index]
                if (row.timestampEpochSec == timestampEpochSec && row.userSlot == userSlot) {
                    rows[index] = row.copy(synced = true)
                }
            }
        }
        override suspend fun latest(userSlot: Int, limit: Int) = emptyList<BloodPressureRecordEntity>()
        override suspend fun allForUser(userSlot: Int) = emptyList<BloodPressureRecordEntity>()
        override suspend fun allForSource(sourceId: String, userSlot: Int?) =
            emptyList<BloodPressureRecordEntity>()
        override suspend fun unsyncedForSource(sourceId: String, userSlot: Int?) =
            emptyList<BloodPressureRecordEntity>()
        override suspend fun deleteAll() = Unit
        override suspend fun deleteForUser(userSlot: Int) = Unit
    }

    /** Exposes the protected onCleared so tests can simulate teardown. */
    private class ClosableViewModel(
        store: ConnectedSourceStore,
        registry: SourceRegistry,
        repository: OmronBpRepository,
        readSource: suspend (ConnectedSource) -> SourceReadOutcome,
        upload: suspend (Int) -> UploadOutcome,
        zoneId: ZoneId,
    ) : MeasurementsViewModel(store, registry, repository, readSource, upload, zoneId) {
        fun simulateCleared() = onCleared()
    }

    // ------------------------------------------------------------------ //
    //  Fixtures                                                          //
    // ------------------------------------------------------------------ //

    private val omronSource = ConnectedSource(
        id = "omron-1",
        sourceTypeId = "omron-ble",
        name = "OMRON M7",
        address = "AA:BB:CC:DD:EE:FF",
        userSlot = 1,
        createdAt = 1L
    )
    private val hcSource = ConnectedSource(
        id = "hc-1",
        sourceTypeId = "health-connect",
        name = "health-connect",
        createdAt = 2L
    )

    private val dao = FakeBpDao()
    private val repository = OmronBpRepository(dao)
    private val store = FakeStore(listOf(omronSource))

    /** The read-pass seam's canned answer and its call log. */
    private var readOutcome: suspend (ConnectedSource) -> SourceReadOutcome = {
        SourceReadOutcome(SourceReadResult.Failed("unset"))
    }
    private val readCalls = mutableListOf<String>()

    /** The upload-pass seam's canned answer and its slot log. */
    private var uploadOutcome: suspend (Int) -> UploadOutcome = { UploadOutcome.NotNeeded }
    private val uploadSlots = mutableListOf<Int>()

    private fun newViewModel(): ClosableViewModel = ClosableViewModel(
        store = store,
        registry = SourceRegistry(listOf(OmronBleSourceType, HealthConnectBpSourceType)),
        repository = repository,
        readSource = { source ->
            readCalls += source.id
            readOutcome(source)
        },
        upload = { userSlot ->
            uploadSlots += userSlot
            uploadOutcome(userSlot)
        },
        zoneId = zone,
    )

    /** 12:00 UTC of [date] - a stable, in-day timestamp for fixtures. */
    private fun noonUtc(date: LocalDate): Long =
        date.atTime(12, 0).atZone(zone).toEpochSecond()

    /** A seam-shaped reading - values irrelevant to every assertion here. */
    private fun bpReading(timestampEpochSec: Long) = BpReading(
        timestampEpochSec = timestampEpochSec,
        systolic = 120,
        diastolic = 80,
        pulse = 70,
        userSlot = 1
    )

    /** One stored reading (the fake DAO never suspends - runBlocking is safe). */
    private fun seed(
        sourceId: String,
        timestampEpochSec: Long,
        userSlot: Int = 1,
    ) {
        runBlocking {
            repository.storeReadings(
                listOf(
                    BloodPressureReading(
                        timestampEpochSec = timestampEpochSec,
                        systolic = 120,
                        diastolic = 80,
                        pulse = 70,
                        mov = false,
                        ihb = false,
                        userSlot = userSlot
                    )
                ),
                sourceId = sourceId
            )
        }
    }

    private fun statusOf(vm: MeasurementsViewModel, sourceId: String): ReadNowState =
        vm.sourceCards.value.orEmpty().single { it.source.id == sourceId }.status

    /** The list state (empty default before the first load completes). */
    private fun recordsOf(vm: MeasurementsViewModel): MeasurementsListState =
        vm.records.value ?: MeasurementsListState(emptyList(), 0)

    private fun readingEntries(vm: MeasurementsViewModel): List<MeasurementsEntry.Reading> =
        recordsOf(vm).entries.filterIsInstance<MeasurementsEntry.Reading>()

    private fun pump() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ //
    //  Gating (epic R8)                                                  //
    // ------------------------------------------------------------------ //

    @Test
    fun `no connected source hides the measurements tab`() {
        store.sources = emptyList()

        val vm = newViewModel()
        pump()

        assertEquals(false, vm.tabVisible.value)
        assertTrue(vm.sourceCards.value.orEmpty().isEmpty())
    }

    @Test
    fun `a connected source shows the measurements tab and removal re-hides it`() {
        val vm = newViewModel()
        pump()

        assertEquals(true, vm.tabVisible.value)
        assertEquals(listOf("omron-1"), vm.sourceCards.value.orEmpty().map { it.source.id })

        // Removing every source (the source detail's remove action) takes
        // the tab away again on the next refresh.
        store.sources = emptyList()
        vm.refresh()
        pump()

        assertEquals(false, vm.tabVisible.value)
    }

    // ------------------------------------------------------------------ //
    //  List mapping (epic R6 / R4)                                       //
    // ------------------------------------------------------------------ //

    @Test
    fun `only the picked user slot of a slots source is listed`() {
        val day = LocalDate.of(2026, 1, 2)
        seed("omron-1", noonUtc(day), userSlot = 1)
        seed("omron-1", noonUtc(day) + 3_600, userSlot = 2) // other slot: invisible
        seed("omron-1", noonUtc(day) + 7_200, userSlot = 1)

        val vm = newViewModel()
        pump()

        assertEquals(
            "newest first, the other slot's rows filtered out (epic R4)",
            listOf(noonUtc(day) + 7_200, noonUtc(day)),
            readingEntries(vm).map { it.reading.timestampEpochSec }
        )
    }

    @Test
    fun `readings of every source merge and a removed source disappears`() {
        val day = LocalDate.of(2026, 1, 2)
        store.sources = listOf(omronSource, hcSource)
        seed("omron-1", noonUtc(day), userSlot = 1)
        seed("hc-1", noonUtc(day) + 3_600, userSlot = BpReading.APP_OWNER_SLOT)

        val vm = newViewModel()
        pump()

        val merged = readingEntries(vm)
        assertEquals(
            listOf(noonUtc(day) + 3_600, noonUtc(day)),
            merged.map { it.reading.timestampEpochSec }
        )
        assertEquals(
            "each row is tagged with the source it came through",
            listOf("hc-1", "omron-1"),
            merged.map { it.source.id }
        )

        store.sources = listOf(omronSource)
        vm.refresh()
        pump()

        assertEquals(
            "a removed source's rows leave the page with the connection",
            listOf("omron-1"),
            readingEntries(vm).map { it.source.id }
        )
    }

    @Test
    fun `day headers group the rows newest day first`() {
        val day1 = LocalDate.of(2026, 1, 2)
        val day2 = LocalDate.of(2026, 1, 3)
        seed("omron-1", noonUtc(day1))
        seed("omron-1", noonUtc(day1) + 3_600)
        seed("omron-1", noonUtc(day2))

        val vm = newViewModel()
        pump()

        val entries = recordsOf(vm).entries
        assertEquals(
            "one header per local day, newest day first, rows newest first inside it",
            listOf("DayHeader", "Reading", "DayHeader", "Reading", "Reading"),
            entries.map { it::class.simpleName }
        )
        assertEquals(
            listOf(day2.toEpochDay(), day1.toEpochDay()),
            entries.filterIsInstance<MeasurementsEntry.DayHeader>().map { it.epochDay }
        )
        assertEquals(
            listOf(noonUtc(day2), noonUtc(day1) + 3_600, noonUtc(day1)),
            entries.filterIsInstance<MeasurementsEntry.Reading>().map { it.reading.timestampEpochSec }
        )
        assertTrue(
            "every row carries its source",
            entries.filterIsInstance<MeasurementsEntry.Reading>().all { it.source.id == "omron-1" }
        )
    }

    @Test
    fun `synced and unsynced rows keep distinct states`() {
        val day = LocalDate.of(2026, 1, 2)
        seed("omron-1", noonUtc(day))
        seed("omron-1", noonUtc(day) + 3_600)
        runBlocking { repository.markSynced(noonUtc(day) + 3_600, userSlot = 1) }

        val vm = newViewModel()
        pump()

        val rows = readingEntries(vm)
        assertEquals(2, rows.size)
        assertTrue("the uploaded row is synced", rows[0].reading.synced)
        assertFalse("the pending row is not", rows[1].reading.synced)
    }

    @Test
    fun `the list opens on a capped window and show more widens it`() {
        val day = LocalDate.of(2026, 1, 2)
        // 120 readings on one day - more than two full windows.
        repeat(120) { index -> seed("omron-1", noonUtc(day) + index * 60L) }

        val vm = newViewModel()
        pump()

        assertEquals(50, readingEntries(vm).size)
        assertEquals(70, recordsOf(vm).hiddenReadingsCount)

        vm.showMore()
        assertEquals(100, readingEntries(vm).size)
        assertEquals(20, recordsOf(vm).hiddenReadingsCount)

        vm.showMore()
        assertEquals(120, readingEntries(vm).size)
        assertEquals(0, recordsOf(vm).hiddenReadingsCount)
    }

    @Test
    fun `a connected source without readings renders an empty but existing page`() {
        val vm = newViewModel()
        pump()

        assertTrue(recordsOf(vm).entries.isEmpty())
        assertEquals(0, recordsOf(vm).hiddenReadingsCount)
        // One card for the connected source - the screen's empty state.
        assertEquals(1, vm.sourceCards.value.orEmpty().size)
    }

    // ------------------------------------------------------------------ //
    //  Read now                                                          //
    // ------------------------------------------------------------------ //

    @Test
    fun `a successful read reports the stored count and triggers the autosync upload of the slot`() {
        readOutcome = {
            SourceReadOutcome(
                result = SourceReadResult.Success(listOf(bpReading(100))),
                stored = OmronBpRepository.StoreResult(insertedCount = 2, duplicatesCount = 1)
            )
        }

        val vm = newViewModel()
        pump()
        vm.readNow("omron-1")
        pump()

        val status = statusOf(vm, "omron-1") as ReadNowState.Finished
        assertEquals(R.string.measurement_read_success_new, status.messageRes)
        assertEquals(2, status.count)
        assertFalse(status.isError)
        // Autosync defaults on: the upload ran for the source's slot.
        assertEquals(listOf(1), uploadSlots)
    }

    @Test
    fun `an all-duplicates read reports no new measurements`() {
        readOutcome = {
            SourceReadOutcome(
                result = SourceReadResult.Success(listOf(bpReading(100))),
                stored = OmronBpRepository.StoreResult(insertedCount = 0, duplicatesCount = 1)
            )
        }

        val vm = newViewModel()
        pump()
        vm.readNow("omron-1")
        pump()

        val status = statusOf(vm, "omron-1") as ReadNowState.Finished
        assertEquals(R.string.measurement_read_success_none, status.messageRes)
    }

    @Test
    fun `a source that consumed its readings itself reports its counters`() {
        readOutcome = {
            SourceReadOutcome(
                result = SourceReadResult.Success(
                    readings = emptyList(),
                    counters = SourceCounters(uploaded = 2, waiting = 1, dropped = 0)
                ),
                stored = OmronBpRepository.StoreResult(insertedCount = 0, duplicatesCount = 0)
            )
        }

        val vm = newViewModel()
        pump()
        vm.readNow("omron-1")
        pump()

        val status = statusOf(vm, "omron-1") as ReadNowState.Finished
        assertEquals(R.string.measurement_read_pipeline_summary, status.messageRes)
        assertEquals(SourceCounters(uploaded = 2, waiting = 1, dropped = 0), status.counters)
    }

    @Test
    fun `every read failure variant surfaces its own status with the raw reason`() {
        val vm = newViewModel()
        pump()

        val cases = listOf(
            SourceReadResult.ConnectionRequired("bond") to R.string.ble1810_error_bonding,
            SourceReadResult.Timeout("unlock") to R.string.ble1810_error_timeout,
            SourceReadResult.Disconnected("transfer") to R.string.ble1810_error_disconnected,
            SourceReadResult.Failed("boom") to R.string.ble1810_error_connect,
        )
        for ((result, expectedRes) in cases) {
            readOutcome = { SourceReadOutcome(result) }
            vm.readNow("omron-1")
            pump()

            val status = statusOf(vm, "omron-1") as ReadNowState.Finished
            assertEquals("headline of $result", expectedRes, status.messageRes)
            assertEquals("raw reason of $result", result.reasonOfTest(), status.detail)
            assertTrue("failures render as errors", status.isError)
        }
        // No failed pass ever reaches the upload step.
        assertTrue(uploadSlots.isEmpty())
    }

    @Test
    fun `connection required of a health connect source shows access guidance, not pairing`() {
        // Task xlg.8 (the xlg.6 review carry-over): a singleton source's
        // connect step IS the permission grant, so its ConnectionRequired
        // must not read as monitor-bonding guidance. The BLE source's side
        // of the discrimination is pinned by the test above (omron-1).
        store.sources = listOf(hcSource)
        readOutcome = {
            SourceReadOutcome(SourceReadResult.ConnectionRequired("no grant"))
        }

        val vm = newViewModel()
        pump()
        vm.readNow("hc-1")
        pump()

        val status = statusOf(vm, "hc-1") as ReadNowState.Finished
        assertEquals(R.string.measurement_read_failed_hc_access, status.messageRes)
        assertEquals("no grant", status.detail)
        assertTrue(status.isError)
        assertTrue("a failed pass never reaches the upload step", uploadSlots.isEmpty())
    }

    @Test
    fun `a failed store step reports it and skips the upload`() {
        readOutcome = {
            SourceReadOutcome(
                result = SourceReadResult.Success(listOf(bpReading(100))),
                stored = OmronBpRepository.StoreResult(
                    insertedCount = 0, duplicatesCount = 0, success = false
                )
            )
        }

        val vm = newViewModel()
        pump()
        vm.readNow("omron-1")
        pump()

        val status = statusOf(vm, "omron-1") as ReadNowState.Finished
        assertEquals(R.string.measurement_read_store_failed, status.messageRes)
        assertTrue(status.isError)
        assertTrue(uploadSlots.isEmpty())
    }

    @Test
    fun `a broken read seam never crashes the ui and says so`() {
        readOutcome = { throw IllegalStateException("kaboom") }

        val vm = newViewModel()
        pump()
        vm.readNow("omron-1")
        pump()

        val status = statusOf(vm, "omron-1") as ReadNowState.Finished
        assertEquals(R.string.measurement_read_failed_unexpected, status.messageRes)
        assertEquals("kaboom", status.detail)
        assertTrue(status.isError)
    }

    @Test
    fun `a cancelled pass resets to idle and nothing crashes`() {
        val gate = CompletableDeferred<Unit>()
        readOutcome = {
            gate.await()
            SourceReadOutcome(SourceReadResult.Failed("never reached"))
        }

        val vm = newViewModel()
        pump()
        vm.readNow("omron-1")
        pump()
        assertEquals(ReadNowState.Reading, statusOf(vm, "omron-1"))

        // Teardown cancels the in-flight pass.
        vm.simulateCleared()
        pump()

        assertEquals(
            "no stuck progress line after cancellation",
            ReadNowState.Idle,
            statusOf(vm, "omron-1")
        )
    }

    @Test
    fun `a second read-now tap while a pass runs is ignored`() {
        val gate = CompletableDeferred<Unit>()
        readOutcome = {
            gate.await()
            SourceReadOutcome(SourceReadResult.Success(emptyList()))
        }

        val vm = newViewModel()
        pump()
        vm.readNow("omron-1")
        pump()
        vm.readNow("omron-1")
        pump()

        assertEquals("one pass per source at a time", listOf("omron-1"), readCalls)

        gate.complete(Unit)
        pump()
    }

    @Test
    fun `a read of a source removed mid-flight starts no pass and crashes nothing`() {
        readOutcome = { SourceReadOutcome(SourceReadResult.Success(emptyList())) }

        val vm = newViewModel()
        pump()
        store.sources = emptyList()
        vm.readNow("omron-1")
        pump()

        // The pass never reaches the source seam - and the vanished source
        // takes its card (and the tab) with it, so nothing is left to show.
        assertTrue("no pass for a source that no longer exists", readCalls.isEmpty())
        assertTrue(vm.sourceCards.value.orEmpty().isEmpty())
        assertEquals(false, vm.tabVisible.value)
    }

    // ------------------------------------------------------------------ //
    //  Autosync                                                          //
    // ------------------------------------------------------------------ //

    @Test
    fun `the autosync toggle persists and gates the post-read upload`() {
        val vm = newViewModel()
        pump()

        assertTrue(vm.setAutoSync("omron-1", false))
        pump()
        assertFalse(
            vm.sourceCards.value.orEmpty().single { it.source.id == "omron-1" }.source.autoSync
        )
        assertFalse(store.sources.single { it.id == "omron-1" }.autoSync)

        // Off: a successful read stores readings but never uploads.
        readOutcome = {
            SourceReadOutcome(
                result = SourceReadResult.Success(listOf(bpReading(100))),
                stored = OmronBpRepository.StoreResult(insertedCount = 1, duplicatesCount = 0)
            )
        }
        vm.readNow("omron-1")
        pump()
        assertTrue(uploadSlots.isEmpty())

        // On again: the next pass uploads the source's slot.
        assertTrue(vm.setAutoSync("omron-1", true))
        pump()
        vm.readNow("omron-1")
        pump()
        assertEquals(listOf(1), uploadSlots)
    }

    @Test
    fun `a source without user attribution uploads the app-owner slot`() {
        store.sources = listOf(hcSource)
        readOutcome = {
            SourceReadOutcome(
                result = SourceReadResult.Success(
                    readings = emptyList(),
                    counters = SourceCounters(uploaded = 1)
                ),
                stored = OmronBpRepository.StoreResult(insertedCount = 0, duplicatesCount = 0)
            )
        }

        val vm = newViewModel()
        pump()
        vm.readNow("hc-1")
        pump()

        assertEquals(listOf(BpReading.APP_OWNER_SLOT), uploadSlots)
    }

    @Test
    fun `a failed autosync write keeps the old flag and reports false`() {
        val vm = newViewModel()
        pump()

        store.failSave = true
        assertFalse(vm.setAutoSync("omron-1", false))
        assertTrue(store.sources.single { it.id == "omron-1" }.autoSync)
    }

    // ------------------------------------------------------------------ //
    //  Post-read upload outcomes                                         //
    // ------------------------------------------------------------------ //

    @Test
    fun `upload problems outrank the read success but never crash`() {
        val vm = newViewModel()
        pump()
        readOutcome = {
            SourceReadOutcome(
                result = SourceReadResult.Success(listOf(bpReading(100))),
                stored = OmronBpRepository.StoreResult(insertedCount = 1, duplicatesCount = 0)
            )
        }

        val cases: List<Pair<UploadOutcome, (ReadNowState.Finished) -> Boolean>> = listOf(
            UploadOutcome.LoginRequired to { s ->
                s.messageRes == R.string.login_required && s.isError
            },
            UploadOutcome.Retryable("net down") to { s ->
                s.messageRes == R.string.measurement_upload_retryable &&
                    s.detail == "net down" && s.isError
            },
            UploadOutcome.BpUploaded(
                confirmedRecords = emptyList(),
                failureMessages = listOf("rejected")
            ) to { s ->
                s.messageRes == R.string.measurement_upload_partial &&
                    s.detail == "rejected" && s.isError
            },
            UploadOutcome.BpUploaded(
                confirmedRecords = listOf(
                    BloodPressureReading(100, 120, 80, 70, false, false, 1)
                )
            ) to { s -> s.messageRes == R.string.measurement_read_success_new },
            UploadOutcome.NotNeeded to { s -> s.messageRes == R.string.measurement_read_success_new },
        )
        for ((outcome, expectation) in cases) {
            uploadOutcome = { outcome }
            vm.readNow("omron-1")
            pump()
            val status = statusOf(vm, "omron-1")
            assertTrue("status of $outcome", status is ReadNowState.Finished && expectation(status))
        }
    }
}

/** The raw reason each non-success variant carries (test-only accessor). */
private fun SourceReadResult.reasonOfTest(): String? = when (this) {
    is SourceReadResult.ConnectionRequired -> reason
    is SourceReadResult.Timeout -> reason
    is SourceReadResult.Disconnected -> reason
    is SourceReadResult.Failed -> reason
    is SourceReadResult.Success -> null
}
