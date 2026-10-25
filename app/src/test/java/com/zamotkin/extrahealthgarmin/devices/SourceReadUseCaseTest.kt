package com.zamotkin.extrahealthgarmin.devices

import com.zamotkin.extrahealthgarmin.devices.api.BpReading
import com.zamotkin.extrahealthgarmin.devices.api.BpSource
import com.zamotkin.extrahealthgarmin.devices.api.BpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.SourceCapabilities
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadRequest
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
import com.zamotkin.extrahealthgarmin.devices.ble1810.Ble1810FingerprintJournal
import com.zamotkin.extrahealthgarmin.devices.ble1810.Ble1810Measurement
import com.zamotkin.extrahealthgarmin.devices.ble1810.Ble1810MeasurementStatus
import com.zamotkin.extrahealthgarmin.devices.ble1810.Ble1810Result
import com.zamotkin.extrahealthgarmin.devices.ble1810.Ble1810Source
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSourceStore
import com.zamotkin.extrahealthgarmin.omron.data.BloodPressureDao
import com.zamotkin.extrahealthgarmin.omron.data.BloodPressureRecordEntity
import com.zamotkin.extrahealthgarmin.omron.data.OmronBpRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [SourceReadUseCase] tests (task tefal2garmin-app-redesign-v3-xlg.4): the
 * read orchestrator over fakes - a recording [BpSource] per attribution
 * shape, the real [OmronBpRepository] over an in-memory DAO (the
 * never-throws gateway is part of what is proven here). Real BLE runs only
 * in the final on-device verification task; plain JUnit, no Android.
 *
 * Epic R4 pins: only the picked monitor user's readings may enter the
 * dataset (ingestion filter), a source without its slot surfaces the
 * configuration problem instead of throwing, and a failed pass stores
 * nothing and advances no state. Epic R5 (task xlg.5) pins: the anchor
 * advances ONLY on a successful pass (never regresses, never moves on an
 * empty one), and a failed Current pass flags the source so the next read
 * runs one full-ring History plan - the Omron counter-reset edge, where a
 * failure after the mid-session reset discards readings the device
 * already counted as read, must cost a slow re-read, never data.
 *
 * Task xlg.15 pins the deferred-commit protocol of the 0x1810 fingerprint
 * journal: the source's read never advances it; the use case commits it
 * only after storeReadings confirmed the rows are in Room, so a DAO
 * failure re-delivers (worst case one duplicate row, never silent loss).
 *
 * Task xlg.16 (the xlg.5 review) pins the cancellation/failure window
 * around that counter reset: the fallback flag must be durable BEFORE the
 * Current plan runs (a cancelled or process-killed pass has consumed the
 * window with nothing recorded), a DAO failure (StoreResult.success
 * false) must advance no anchor and clear no flag, and the deferred
 * journal commit and the flag/journal recovery interleave losslessly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceReadUseCaseTest {

    /** The 0x1810 fixture's fingerprint journal file lives here (per test). */
    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------ //
    //  Fakes                                                             //
    // ------------------------------------------------------------------ //

    /** Registry type shaped like Omron (SLOTS) or like Health Connect (NONE). */
    private class FakeType(
        override val typeId: String,
        override val capabilities: SourceCapabilities,
    ) : BpSourceType {
        override val displayNameRes: Int get() = 0
    }

    /** Answers a canned result and records every request it received. */
    private class RecordingSource(
        override val sourceTypeId: String,
        override val capabilities: SourceCapabilities,
        var result: SourceReadResult,
    ) : BpSource {
        val requests = mutableListOf<SourceReadRequest>()

        override val displayNameRes: Int get() = 0

        override suspend fun read(request: SourceReadRequest): SourceReadResult {
            requests += request
            return result
        }
    }

    /**
     * A source that hangs inside [read] until its gate opens, appending
     * start/end events - the probe for the per-source read serialization.
     */
    private class GatedSource(
        override val sourceTypeId: String,
        override val capabilities: SourceCapabilities,
        private val gate: CompletableDeferred<Unit>,
        private val events: MutableList<String>,
        private val label: String,
    ) : BpSource {
        override val displayNameRes: Int get() = 0

        override suspend fun read(request: SourceReadRequest): SourceReadResult {
            events += "$label:start"
            gate.await()
            events += "$label:end"
            return SourceReadResult.Success(emptyList())
        }
    }

    /**
     * Mutable-list [BloodPressureDao] honoring the @Insert(IGNORE) contract
     * (unique key = timestampEpochSec + userSlot + sourceId). The read use
     * case only writes through it; the query surface is covered by
     * [com.zamotkin.extrahealthgarmin.omron.data.OmronBpRepositoryTest].
     */
    private class FakeBpDao : BloodPressureDao {
        val rows = mutableListOf<BloodPressureRecordEntity>()
        private var nextId = 1L
        var failure: Exception? = null

        // The unified cross-source query (task xlg.6) is not exercised by
        // the read orchestrator; OmronBpRepositoryTest pins it.
        override suspend fun allForSources(sourceIds: List<String>): List<BloodPressureRecordEntity> =
            emptyList()

        override suspend fun insertIgnoreAll(
            records: List<BloodPressureRecordEntity>
        ): List<Long> {
            failure?.let { throw it }
            return records.map { record ->
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
        }

        override suspend fun unsyncedForUser(userSlot: Int): List<BloodPressureRecordEntity> =
            error("not exercised by SourceReadUseCaseTest")

        override suspend fun unsyncedAll(): List<BloodPressureRecordEntity> =
            error("not exercised by SourceReadUseCaseTest")

        override suspend fun markSynced(timestampEpochSec: Long, userSlot: Int) =
            error("not exercised by SourceReadUseCaseTest")

        override suspend fun latest(userSlot: Int, limit: Int): List<BloodPressureRecordEntity> =
            error("not exercised by SourceReadUseCaseTest")

        override suspend fun allForUser(userSlot: Int): List<BloodPressureRecordEntity> =
            error("not exercised by SourceReadUseCaseTest")

        override suspend fun allForSource(
            sourceId: String,
            userSlot: Int?,
        ): List<BloodPressureRecordEntity> = error("not exercised by SourceReadUseCaseTest")

        override suspend fun unsyncedForSource(
            sourceId: String,
            userSlot: Int?,
        ): List<BloodPressureRecordEntity> = error("not exercised by SourceReadUseCaseTest")

        override suspend fun deleteAll() = error("not exercised by SourceReadUseCaseTest")

        override suspend fun deleteForUser(userSlot: Int) =
            error("not exercised by SourceReadUseCaseTest")
    }

    /**
     * Hand store fake (the SourcesViewModelTest shape): the rows the use
     * case reloads at pass start, every saved list, and a save-failure
     * switch. Inherits the interface's default update - the production
     * atomicity lives in FileConnectedSourceStore and is covered by
     * ConnectedSourceStoreTest.
     */
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

    // ------------------------------------------------------------------ //
    //  Fixtures                                                          //
    // ------------------------------------------------------------------ //

    private companion object {
        const val SLOTS_TYPE = "fake-slots"
        const val NONE_TYPE = "fake-none"
        const val NONE_DUAL_TYPE = "fake-none-dual"
        const val BLE1810_LIKE_TYPE = "fake-1810"

        val SLOTS_CAPABILITIES = SourceCapabilities(
            userAttribution = UserAttribution.SLOTS,
            supportedSyncModes = setOf(SyncMode.HISTORY, SyncMode.CURRENT),
            requiresPairingKey = true
        )

        val NONE_CAPABILITIES = SourceCapabilities(
            userAttribution = UserAttribution.NONE,
            supportedSyncModes = setOf(SyncMode.CURRENT),
            requiresPairingKey = false
        )

        /**
         * App-owner attribution with BOTH modes - the hypothetical
         * Current-capable deferred-commit source the xlg.16 interleaving
         * tests need (today's real one, the 0x1810 driver, is
         * History-only).
         */
        val NONE_DUAL_CAPABILITIES = SourceCapabilities(
            userAttribution = UserAttribution.NONE,
            supportedSyncModes = setOf(SyncMode.HISTORY, SyncMode.CURRENT),
            requiresPairingKey = false
        )

        /** The standard 0x1810 shape: app-owner attribution, History only. */
        val BLE1810_LIKE_CAPABILITIES = SourceCapabilities(
            userAttribution = UserAttribution.NONE,
            supportedSyncModes = setOf(SyncMode.HISTORY),
            requiresPairingKey = false
        )
    }

    private val registry = SourceRegistry(
        listOf(
            FakeType(SLOTS_TYPE, SLOTS_CAPABILITIES),
            FakeType(NONE_TYPE, NONE_CAPABILITIES),
            FakeType(NONE_DUAL_TYPE, NONE_DUAL_CAPABILITIES),
            FakeType(BLE1810_LIKE_TYPE, BLE1810_LIKE_CAPABILITIES),
        )
    )
    private val dao = FakeBpDao()
    private val errors = mutableListOf<Throwable>()
    private val repository = OmronBpRepository(dao, onError = { errors.add(it) })
    private val slotsAdapter =
        RecordingSource(SLOTS_TYPE, SLOTS_CAPABILITIES, SourceReadResult.Success(emptyList()))
    private val noneAdapter =
        RecordingSource(NONE_TYPE, NONE_CAPABILITIES, SourceReadResult.Success(emptyList()))

    /** What the faked 0x1810 read pushes on every pass (History re-push model). */
    private var bleMeasurements: List<Ble1810Measurement> = emptyList()

    private fun newUseCase(
        sourceFor: (ConnectedSource) -> BpSource? = { source ->
            when (source.sourceTypeId) {
                SLOTS_TYPE -> slotsAdapter
                NONE_TYPE -> noneAdapter
                else -> null
            }
        },
        store: ConnectedSourceStore = FakeStore(emptyList()),
    ): SourceReadUseCase = SourceReadUseCase(
        registry = registry,
        sourceFor = sourceFor,
        repository = repository,
        store = store,
    )

    private fun aSlotsSource(userSlot: Int?) = ConnectedSource(
        id = "source-1",
        sourceTypeId = SLOTS_TYPE,
        name = "OMRON M7",
        address = "AA:BB:CC:DD:EE:01",
        pairingKeyRef = "omron-pairing-key",
        userSlot = userSlot,
        syncMode = SyncMode.HISTORY,
        createdAt = 1L,
    )

    private fun aNoneSource() = ConnectedSource(
        id = "source-2",
        sourceTypeId = NONE_TYPE,
        name = NONE_TYPE,
        syncMode = SyncMode.CURRENT,
        createdAt = 1L,
    )

    private fun reading(timestampEpochSec: Long, userSlot: Int) = BpReading(
        timestampEpochSec = timestampEpochSec,
        systolic = 120,
        diastolic = 80,
        pulse = 70,
        userSlot = userSlot,
    )

    /**
     * The real 0x1810 adapter over a faked BLE read and the REAL per-device
     * fingerprint journal — exactly what [SourceReadUseCase.create] builds
     * per pass (a fresh source + journal instance over the one file), so
     * the deferred-commit protocol is proven against production wiring.
     */
    private fun ble1810Source(): Ble1810Source = Ble1810Source(
        bleRead = {
            Ble1810Result.Success(bleMeasurements, feature = null, deviceDescription = null, 0, 0, 0)
        },
        journal = Ble1810FingerprintJournal.inFilesDir(tmp.root, "AA:BB:CC:DD:EE:10")
    )

    private fun newBle1810UseCase(store: ConnectedSourceStore): SourceReadUseCase =
        newUseCase(sourceFor = { ble1810Source() }, store = store)

    private fun an1810Source() = ConnectedSource(
        id = "source-1810",
        sourceTypeId = BLE1810_LIKE_TYPE,
        name = "Standard BLE",
        syncMode = SyncMode.HISTORY,
        createdAt = 1L,
    )

    /** A clock-less 0x1810 record: no device clock, so [readTime] IS the record time. */
    private fun clocklessMeasurement(systolic: Int, readTime: Long) = Ble1810Measurement(
        systolicMmHg = systolic,
        diastolicMmHg = 80,
        mapMmHg = 102,
        pulseBpm = 64,
        timestampEpochSec = readTime,
        timestampFromDeviceClock = false,
        userId = null,
        status = Ble1810MeasurementStatus(
            bodyMovement = false,
            cuffTooLoose = false,
            irregularPulse = false,
            improperPosition = false,
            raw = 0
        )
    )

    // ------------------------------------------------------------------ //
    //  Ingestion filter (epic R4)                                        //
    // ------------------------------------------------------------------ //

    @Test
    fun `readings of other user slots are dropped before storage`() = runTest {
        // A misbehaving device serving both slots' windows: only the picked
        // user's rows may enter the dataset, tagged with the source id.
        slotsAdapter.result = SourceReadResult.Success(
            listOf(
                reading(1_000, userSlot = 1),
                reading(2_000, userSlot = 2),
                reading(3_000, userSlot = 1),
            )
        )

        val outcome = newUseCase().read(aSlotsSource(userSlot = 1))

        assertTrue(outcome.result is SourceReadResult.Success)
        assertEquals(OmronBpRepository.StoreResult(insertedCount = 2, duplicatesCount = 0), outcome.stored)
        assertEquals(listOf(1_000L, 3_000L), dao.rows.map { it.timestampEpochSec })
        assertTrue(dao.rows.all { it.userSlot == 1 && it.sourceId == "source-1" })
    }

    @Test
    fun `the request carries the stored sync mode and user slot`() = runTest {
        slotsAdapter.result = SourceReadResult.Success(emptyList())

        newUseCase().read(aSlotsSource(userSlot = 2))

        assertEquals(
            listOf(SourceReadRequest(syncMode = SyncMode.HISTORY, userSlot = 2)),
            slotsAdapter.requests,
        )
    }

    @Test
    fun `a slots source without a picked slot sends null and surfaces the failure`() = runTest {
        // The configuration problem (slot never picked) is the SOURCE's to
        // report - the real Omron adapter fails exactly like this - and the
        // use case surfaces it instead of throwing or storing anything.
        slotsAdapter.result = SourceReadResult.Failed("Omron reads need a monitor user slot")

        val outcome = newUseCase().read(aSlotsSource(userSlot = null))

        assertEquals(
            listOf(SourceReadRequest(syncMode = SyncMode.HISTORY, userSlot = null)),
            slotsAdapter.requests,
        )
        assertTrue(outcome.result is SourceReadResult.Failed)
        assertNull(outcome.stored)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `none-attribution sources keep every reading without a slot filter`() = runTest {
        // Health Connect shape: readings carry the app-owner slot, the stored
        // source has no user slot, and none may be filtered away.
        noneAdapter.result = SourceReadResult.Success(
            listOf(
                reading(1_000, userSlot = BpReading.APP_OWNER_SLOT),
                reading(2_000, userSlot = BpReading.APP_OWNER_SLOT),
            )
        )

        val outcome = newUseCase().read(aNoneSource())

        assertEquals(OmronBpRepository.StoreResult(insertedCount = 2, duplicatesCount = 0), outcome.stored)
        assertEquals(2, dao.rows.size)
        assertNull(noneAdapter.requests.single().userSlot)
    }

    @Test
    fun `re-reads ride the dedup index and count as duplicates`() = runTest {
        slotsAdapter.result = SourceReadResult.Success(listOf(reading(1_000, userSlot = 1)))

        val useCase = newUseCase()
        useCase.read(aSlotsSource(userSlot = 1))
        val second = useCase.read(aSlotsSource(userSlot = 1))

        assertEquals(OmronBpRepository.StoreResult(insertedCount = 0, duplicatesCount = 1), second.stored)
        assertEquals(1, dao.rows.size)
    }

    // ------------------------------------------------------------------ //
    //  Failed passes                                                     //
    // ------------------------------------------------------------------ //

    @Test
    fun `failed passes store nothing and touch no state`() = runTest {
        // Nothing is journaled on a failed pass: the readings stay retriable
        // and no source state is advanced (task xlg.5 adds the read anchor;
        // this use case has no write path to it at all yet).
        val terminals = listOf(
            SourceReadResult.ConnectionRequired("bonding required"),
            SourceReadResult.Timeout("unlock"),
            SourceReadResult.Disconnected("session"),
            SourceReadResult.Failed("boom"),
        )

        for (terminal in terminals) {
            slotsAdapter.result = terminal
            val outcome = newUseCase().read(aSlotsSource(userSlot = 1))
            assertEquals(terminal, outcome.result)
            assertNull(outcome.stored)
        }

        assertTrue(dao.rows.isEmpty())
        assertEquals(4, slotsAdapter.requests.size)
    }

    @Test
    fun `a dao failure during store reports zero counts and failure without throwing`() = runTest {
        dao.failure = IllegalStateException("disk I/O error")
        slotsAdapter.result = SourceReadResult.Success(listOf(reading(1_000, userSlot = 1)))

        val outcome = newUseCase().read(aSlotsSource(userSlot = 1))

        // Task xlg.15: success false is what tells a failed store from an
        // all-duplicates one — the signal the deferred journal commit keys on.
        assertTrue(outcome.result is SourceReadResult.Success)
        assertEquals(
            OmronBpRepository.StoreResult(insertedCount = 0, duplicatesCount = 0, success = false),
            outcome.stored
        )
        assertEquals(1, errors.size)
    }

    // ------------------------------------------------------------------ //
    //  Deferred journal commit (task xlg.15, xlg.9 review fix)           //
    // ------------------------------------------------------------------ //

    /**
     * The xlg.9 review's data-loss regression: the 0x1810 fingerprint
     * journal must NOT advance inside the source's read — the use case
     * stores the readings only afterwards, and a swallowed DAO failure
     * would otherwise filter the clock-less records out of every later
     * HISTORY pass, losing them permanently behind a Success outcome (no
     * code ever deletes the journal file, so there is no recovery path).
     */
    @Test
    fun `a dao failure during store leaves the journal uncommitted and the next pass re-delivers`() =
        runTest {
            val store = FakeStore(listOf(an1810Source()))
            val useCase = newBle1810UseCase(store)
            bleMeasurements = listOf(
                clocklessMeasurement(120, readTime = 1_000),
                clocklessMeasurement(125, readTime = 1_001),
            )
            dao.failure = IllegalStateException("disk I/O error")

            val failed = useCase.read(an1810Source())

            assertTrue(failed.result is SourceReadResult.Success)
            assertEquals(
                OmronBpRepository.StoreResult(insertedCount = 0, duplicatesCount = 0, success = false),
                failed.stored
            )
            assertTrue(dao.rows.isEmpty()) // the failure wrote nothing

            dao.failure = null
            val retry = useCase.read(an1810Source())

            // The journal never advanced: the same History re-push
            // re-delivers everything, and this time the rows land in Room.
            assertEquals(
                OmronBpRepository.StoreResult(insertedCount = 2, duplicatesCount = 0),
                retry.stored
            )
            assertEquals(2, dao.rows.size)
        }

    @Test
    fun `a confirmed store commits the fingerprint journal`() = runTest {
        val useCase = newBle1810UseCase(FakeStore(listOf(an1810Source())))
        bleMeasurements = listOf(
            clocklessMeasurement(120, readTime = 1_000),
            clocklessMeasurement(125, readTime = 1_001),
        )

        val first = useCase.read(an1810Source())

        assertEquals(
            OmronBpRepository.StoreResult(insertedCount = 2, duplicatesCount = 0),
            first.stored
        )

        // The journal advanced with the store: the same re-push delivers
        // nothing new and inserts no duplicate rows.
        val second = useCase.read(an1810Source())
        assertTrue(second.result is SourceReadResult.Success)
        assertTrue((second.result as SourceReadResult.Success).readings.isEmpty())
        assertEquals(OmronBpRepository.StoreResult(insertedCount = 0, duplicatesCount = 0), second.stored)
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `an all-duplicates store still commits the fingerprint journal`() = runTest {
        // Room already holds the rows this pass delivers — the reverse
        // window of a previous pass that stored them but crashed before its
        // journal commit. Zero inserted is NOT a failure: the rows ARE
        // durable, so the journal must advance (this is why StoreResult
        // carries an explicit success signal instead of "inserted > 0").
        dao.rows += listOf(
            BloodPressureRecordEntity(
                timestampEpochSec = 1_000, systolic = 120, diastolic = 80, pulse = 64,
                mov = false, ihb = false, userSlot = BpReading.APP_OWNER_SLOT, sourceId = "source-1810"
            ),
            BloodPressureRecordEntity(
                timestampEpochSec = 1_001, systolic = 125, diastolic = 80, pulse = 64,
                mov = false, ihb = false, userSlot = BpReading.APP_OWNER_SLOT, sourceId = "source-1810"
            ),
        )
        val useCase = newBle1810UseCase(FakeStore(listOf(an1810Source())))
        bleMeasurements = listOf(
            clocklessMeasurement(120, readTime = 1_000),
            clocklessMeasurement(125, readTime = 1_001),
        )

        val outcome = useCase.read(an1810Source())

        assertEquals(
            OmronBpRepository.StoreResult(insertedCount = 0, duplicatesCount = 2, success = true),
            outcome.stored
        )

        // Committed: a re-push delivers nothing new.
        val second = useCase.read(an1810Source())
        assertTrue((second.result as SourceReadResult.Success).readings.isEmpty())
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `a crash between store and commit costs one duplicate row, never a loss`() = runTest {
        // The accepted worst case: the dying pass DID store its row, but its
        // journal commit never ran — the next process finds Room populated
        // and a journal that knows nothing. The re-push (clock-less records
        // carry the NEW read time) inserts one duplicate row for the same
        // physical measurement; a reading is never lost.
        dao.rows += BloodPressureRecordEntity(
            timestampEpochSec = 5_000, systolic = 120, diastolic = 80, pulse = 64,
            mov = false, ihb = false, userSlot = BpReading.APP_OWNER_SLOT, sourceId = "source-1810"
        )
        val useCase = newBle1810UseCase(FakeStore(listOf(an1810Source())))
        bleMeasurements = listOf(clocklessMeasurement(120, readTime = 9_000))

        val outcome = useCase.read(an1810Source())

        assertEquals(
            OmronBpRepository.StoreResult(insertedCount = 1, duplicatesCount = 0),
            outcome.stored
        )
        assertEquals(2, dao.rows.size) // the old row + the re-delivered one

        // And this pass's commit holds: a further re-push delivers nothing.
        val third = useCase.read(an1810Source())
        assertTrue((third.result as SourceReadResult.Success).readings.isEmpty())
        assertEquals(2, dao.rows.size)
    }

    // ------------------------------------------------------------------ //
    //  Never-throw contract                                              //
    // ------------------------------------------------------------------ //

    @Test
    fun `an unknown source type fails the pass without reading`() = runTest {
        val ghost = aSlotsSource(userSlot = 1).copy(sourceTypeId = "ghost")

        val outcome = newUseCase().read(ghost)

        assertTrue(outcome.result is SourceReadResult.Failed)
        assertTrue(slotsAdapter.requests.isEmpty())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a source factory that returns null fails the pass`() = runTest {
        val outcome = newUseCase(sourceFor = { null }).read(aSlotsSource(userSlot = 1))

        assertTrue(outcome.result is SourceReadResult.Failed)
        assertTrue((outcome.result as SourceReadResult.Failed).reason.contains("not available"))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a throwing source factory folds onto failed`() = runTest {
        val outcome = newUseCase(sourceFor = { throw IllegalStateException("wiring exploded") })
            .read(aSlotsSource(userSlot = 1))

        assertTrue(outcome.result is SourceReadResult.Failed)
        assertTrue((outcome.result as SourceReadResult.Failed).reason.contains("wiring exploded"))
    }

    @Test
    fun `a source that throws despite the contract folds onto failed`() = runTest {
        val throwing = object : BpSource {
            override val sourceTypeId: String = SLOTS_TYPE
            override val displayNameRes: Int get() = 0
            override val capabilities: SourceCapabilities = SLOTS_CAPABILITIES
            override suspend fun read(request: SourceReadRequest): SourceReadResult =
                throw IllegalStateException("broken driver")
        }

        val outcome = newUseCase(sourceFor = { throwing }).read(aSlotsSource(userSlot = 1))

        assertTrue(outcome.result is SourceReadResult.Failed)
        assertTrue((outcome.result as SourceReadResult.Failed).reason.contains("broken driver"))
    }

    @Test
    fun `cancellation from the factory propagates`() = runTest {
        val cancelling = newUseCase(
            sourceFor = { throw CancellationException("pass cancelled") }
        )

        try {
            cancelling.read(aSlotsSource(userSlot = 1))
            fail("CancellationException must propagate out of read")
        } catch (e: CancellationException) {
            // expected — rethrown before the Failed fallback
        }
        assertTrue(dao.rows.isEmpty())
    }

    // ------------------------------------------------------------------ //
    //  Per-source serialization                                          //
    // ------------------------------------------------------------------ //

    @Test
    fun `concurrent reads of one source serialize`() = runTest {
        val events = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val gated = GatedSource(SLOTS_TYPE, SLOTS_CAPABILITIES, gate, events, label = "read")
        val useCase = newUseCase(sourceFor = { gated })
        val source = aSlotsSource(userSlot = 1)

        val first = launch { useCase.read(source) }
        advanceUntilIdle()
        val second = launch { useCase.read(source) }
        advanceUntilIdle()

        // The second pass waits on the source's lock: a physical monitor
        // serves one read at a time.
        assertEquals(listOf("read:start"), events)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("read:start", "read:end", "read:start", "read:end"), events)
        first.join()
        second.join()
    }

    @Test
    fun `reads of different sources run in parallel`() = runTest {
        val events = mutableListOf<String>()
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        val monitorA = GatedSource(SLOTS_TYPE, SLOTS_CAPABILITIES, gateA, events, label = "A")
        val monitorB = GatedSource(SLOTS_TYPE, SLOTS_CAPABILITIES, gateB, events, label = "B")
        val useCase = newUseCase(sourceFor = { source ->
            if (source.id == "source-1") monitorA else monitorB
        })

        val first = launch { useCase.read(aSlotsSource(userSlot = 1)) }
        advanceUntilIdle()
        val second = launch { useCase.read(aSlotsSource(userSlot = 1).copy(id = "source-9")) }
        advanceUntilIdle()

        // Two different monitors read concurrently - the lock is per source,
        // not global.
        assertEquals(setOf("A:start", "B:start"), events.toSet())

        gateA.complete(Unit)
        gateB.complete(Unit)
        advanceUntilIdle()

        assertEquals(4, events.size)
        first.join()
        second.join()
    }

    // ------------------------------------------------------------------ //
    //  Sync modes and read anchors (epic R5, task xlg.5)                //
    // ------------------------------------------------------------------ //

    @Test
    fun `a successful current read advances the anchor to the newest ingested reading`() = runTest {
        val source = aSlotsSource(userSlot = 1).copy(syncMode = SyncMode.CURRENT)
        val store = FakeStore(listOf(source))
        slotsAdapter.result = SourceReadResult.Success(
            listOf(reading(1_000, userSlot = 1), reading(5_000, userSlot = 1))
        )

        val outcome = newUseCase(store = store).read(source)

        assertTrue(outcome.result is SourceReadResult.Success)
        val row = store.sources.single()
        assertEquals(5_000L, row.lastReadAnchor)
        assertFalse(row.fullReadFallbackPending)
    }

    @Test
    fun `a current read that returns nothing leaves the anchor untouched`() = runTest {
        val source = aSlotsSource(userSlot = 1)
            .copy(syncMode = SyncMode.CURRENT, lastReadAnchor = 5_000L)
        val store = FakeStore(listOf(source))
        slotsAdapter.result = SourceReadResult.Success(emptyList())

        val outcome = newUseCase(store = store).read(source)

        assertTrue(outcome.result is SourceReadResult.Success)
        assertEquals(5_000L, store.sources.single().lastReadAnchor)
    }

    @Test
    fun `only the new reading of the next current read is stored and the anchor follows it`() = runTest {
        val source = aSlotsSource(userSlot = 1).copy(syncMode = SyncMode.CURRENT)
        val store = FakeStore(listOf(source))
        val useCase = newUseCase(store = store)
        slotsAdapter.result = SourceReadResult.Success(listOf(reading(5_000, userSlot = 1)))
        useCase.read(source)
        // The device serves only the measurement newer than the anchor.
        slotsAdapter.result = SourceReadResult.Success(listOf(reading(9_000, userSlot = 1)))

        val outcome = useCase.read(source)

        assertEquals(OmronBpRepository.StoreResult(insertedCount = 1, duplicatesCount = 0), outcome.stored)
        assertEquals(9_000L, store.sources.single().lastReadAnchor)
        assertEquals(SyncMode.CURRENT, slotsAdapter.requests.last().syncMode)
    }

    @Test
    fun `a failed current read keeps the anchor and flags the full-ring fallback`() = runTest {
        val source = aSlotsSource(userSlot = 1).copy(syncMode = SyncMode.CURRENT)
        val store = FakeStore(listOf(source))
        slotsAdapter.result = SourceReadResult.Disconnected("session end")

        val outcome = newUseCase(store = store).read(source)

        assertTrue(outcome.result is SourceReadResult.Disconnected)
        val row = store.sources.single()
        assertEquals(null, row.lastReadAnchor)
        assertTrue(row.fullReadFallbackPending)
    }

    @Test
    fun `the read after a failed current pass runs the full-ring history plan`() = runTest {
        val source = aSlotsSource(userSlot = 1).copy(syncMode = SyncMode.CURRENT)
        val store = FakeStore(listOf(source))
        val useCase = newUseCase(store = store)
        slotsAdapter.result = SourceReadResult.Failed("link dropped after the counter reset")
        useCase.read(source)
        // The recovery pass re-reads the whole device memory: everything
        // comes back and is stored (nothing was persisted by the failed
        // pass, so no duplicates).
        slotsAdapter.result = SourceReadResult.Success(
            listOf(reading(1_000, userSlot = 1), reading(5_000, userSlot = 1))
        )

        val outcome = useCase.read(source)

        assertEquals(
            SourceReadRequest(syncMode = SyncMode.HISTORY, userSlot = 1),
            slotsAdapter.requests.last()
        )
        assertEquals(OmronBpRepository.StoreResult(insertedCount = 2, duplicatesCount = 0), outcome.stored)
        val row = store.sources.single()
        assertEquals(5_000L, row.lastReadAnchor)
        assertFalse(row.fullReadFallbackPending)
    }

    @Test
    fun `the fallback plan repeats until one full-ring pass succeeds`() = runTest {
        val source = aSlotsSource(userSlot = 1).copy(syncMode = SyncMode.CURRENT)
        val store = FakeStore(listOf(source))
        val useCase = newUseCase(store = store)
        slotsAdapter.result = SourceReadResult.Failed("first attempt")
        useCase.read(source)
        slotsAdapter.result = SourceReadResult.Timeout("second attempt")
        useCase.read(source)

        // A failed full-ring attempt leaves the device counters untouched,
        // so the flag stays until a full-ring pass actually succeeds.
        assertTrue(store.sources.single().fullReadFallbackPending)
        assertEquals(SyncMode.HISTORY, slotsAdapter.requests.last().syncMode)

        // The third pass is the full-ring plan that finally succeeds - it
        // clears the flag; the mode itself never changed in the store.
        slotsAdapter.result = SourceReadResult.Success(emptyList())
        useCase.read(source)

        assertFalse(store.sources.single().fullReadFallbackPending)
        assertEquals(SyncMode.HISTORY, slotsAdapter.requests.last().syncMode)

        // With the flag cleared, the pass after it is Current again.
        useCase.read(source)

        assertEquals(SyncMode.CURRENT, slotsAdapter.requests.last().syncMode)
    }

    @Test
    fun `current-only sources never fall back after a failed read`() = runTest {
        val source = aNoneSource()
        val store = FakeStore(listOf(source))
        noneAdapter.result = SourceReadResult.Failed("provider ipc died")

        val outcome = newUseCase(store = store).read(source)

        assertTrue(outcome.result is SourceReadResult.Failed)
        assertFalse(store.sources.single().fullReadFallbackPending)
    }

    @Test
    fun `a current read that throws flags the fallback too`() = runTest {
        val source = aSlotsSource(userSlot = 1).copy(syncMode = SyncMode.CURRENT)
        val store = FakeStore(listOf(source))
        val throwing = object : BpSource {
            override val sourceTypeId: String = SLOTS_TYPE
            override val displayNameRes: Int get() = 0
            override val capabilities: SourceCapabilities = SLOTS_CAPABILITIES
            override suspend fun read(request: SourceReadRequest): SourceReadResult =
                throw IllegalStateException("died mid-session")
        }

        val outcome = newUseCase(sourceFor = { throwing }, store = store).read(source)

        assertTrue(outcome.result is SourceReadResult.Failed)
        assertTrue(store.sources.single().fullReadFallbackPending)
    }

    @Test
    fun `a pass that never reached the source sets no fallback`() = runTest {
        val source = aSlotsSource(userSlot = 1).copy(syncMode = SyncMode.CURRENT)
        val store = FakeStore(listOf(source))

        val outcome = newUseCase(sourceFor = { null }, store = store).read(source)

        assertTrue(outcome.result is SourceReadResult.Failed)
        assertFalse(store.sources.single().fullReadFallbackPending)
    }

    @Test
    fun `history re-reads return everything and stay idempotent`() = runTest {
        val source = aSlotsSource(userSlot = 1)
        val store = FakeStore(listOf(source))
        slotsAdapter.result = SourceReadResult.Success(
            listOf(reading(1_000, userSlot = 1), reading(2_000, userSlot = 1))
        )
        val useCase = newUseCase(store = store)

        val first = useCase.read(source)
        val second = useCase.read(source)

        assertEquals(OmronBpRepository.StoreResult(insertedCount = 2, duplicatesCount = 0), first.stored)
        assertEquals(OmronBpRepository.StoreResult(insertedCount = 0, duplicatesCount = 2), second.stored)
        assertEquals(2, dao.rows.size) // the unique index absorbs the re-read
        assertEquals(2_000L, store.sources.single().lastReadAnchor)
    }

    @Test
    fun `the anchor never regresses on older readings`() = runTest {
        val source = aSlotsSource(userSlot = 1).copy(lastReadAnchor = 5_000L)
        val store = FakeStore(listOf(source))
        // A History pass re-delivers an old row (a duplicate in Room).
        slotsAdapter.result = SourceReadResult.Success(listOf(reading(1_000, userSlot = 1)))

        newUseCase(store = store).read(source)

        assertEquals(5_000L, store.sources.single().lastReadAnchor)
    }

    @Test
    fun `the pass follows the stored row not a stale caller snapshot`() = runTest {
        val stored = aSlotsSource(userSlot = 1).copy(syncMode = SyncMode.CURRENT)
        val store = FakeStore(listOf(stored))
        val staleSnapshot = stored.copy(syncMode = SyncMode.HISTORY)
        slotsAdapter.result = SourceReadResult.Success(emptyList())

        newUseCase(store = store).read(staleSnapshot)

        assertEquals(SyncMode.CURRENT, slotsAdapter.requests.single().syncMode)
    }

    @Test
    fun `a store write failure during anchor bookkeeping does not fail the read`() = runTest {
        val source = aSlotsSource(userSlot = 1).copy(syncMode = SyncMode.CURRENT)
        val store = FakeStore(listOf(source))
        store.failSave = true
        slotsAdapter.result = SourceReadResult.Success(listOf(reading(1_000, userSlot = 1)))

        val outcome = newUseCase(store = store).read(source)

        assertTrue(outcome.result is SourceReadResult.Success)
        assertEquals(OmronBpRepository.StoreResult(insertedCount = 1, duplicatesCount = 0), outcome.stored)
    }

    // ------------------------------------------------------------------ //
    //  Cancellation and DAO-failure windows (task xlg.16, xlg.5 review)  //
    // ------------------------------------------------------------------ //

    @Test
    fun `a cancelled current pass persists the fallback flag before the cancellation escapes`() =
        runTest {
            // The xlg.5 review's major finding: a Current pass cancelled
            // after the Omron device reset its unread counters (mid-session)
            // but before Room committed wrote NO flag - the
            // CancellationException rethrow escaped unflagged. The xlg.16
            // fix flags BEFORE the plan runs, so the flag is already durable
            // when the cancellation escapes read().
            val source = aSlotsSource(userSlot = 1).copy(syncMode = SyncMode.CURRENT)
            val store = FakeStore(listOf(source))
            val cancelledMidSession = object : BpSource {
                override val sourceTypeId: String = SLOTS_TYPE
                override val displayNameRes: Int get() = 0
                override val capabilities: SourceCapabilities = SLOTS_CAPABILITIES
                override suspend fun read(request: SourceReadRequest): SourceReadResult =
                    throw CancellationException("cancelled after the counter reset")
            }

            try {
                newUseCase(sourceFor = { cancelledMidSession }, store = store).read(source)
                fail("CancellationException must propagate out of read")
            } catch (e: CancellationException) {
                // expected - and the flag must already be persisted
            }

            val row = store.sources.single()
            assertTrue(row.fullReadFallbackPending)
            assertNull(row.lastReadAnchor) // no state advanced
            assertEquals(SyncMode.CURRENT, row.syncMode) // the configured mode is never rewritten
        }

    @Test
    fun `cancellation during the store step keeps the fallback flag`() = runTest {
        // The persist segment is equally in the window: a cancellation
        // hitting storeReadings (the repository rethrows
        // CancellationException) leaves the batch in Room or not - either
        // way the pre-plan flag must survive so the next pass re-reads the
        // full ring instead of trusting the window.
        val source = aSlotsSource(userSlot = 1).copy(syncMode = SyncMode.CURRENT)
        val store = FakeStore(listOf(source))
        slotsAdapter.result = SourceReadResult.Success(listOf(reading(9_000, userSlot = 1)))
        dao.failure = CancellationException("cancelled while persisting")

        assertTrue(
            runCatching { newUseCase(store = store).read(source) }.isFailure
        )

        val row = store.sources.single()
        assertTrue(row.fullReadFallbackPending)
        assertNull(row.lastReadAnchor)
    }

    @Test
    fun `a pre-source cancellation flags the fallback and one full-ring pass resumes current`() =
        runTest {
            // The designed cost of flagging up front (the xlg.16 preferred
            // fix): even a cancellation that struck BEFORE the device
            // consumed anything sets the flag - the next pass runs ONE
            // full-ring History plan, and Current resumes after it. The
            // process-death window is the same shape: the flag write is
            // durable before the session starts, so a killed process
            // re-reads the ring on the next launch.
            val source = aSlotsSource(userSlot = 1).copy(syncMode = SyncMode.CURRENT)
            val store = FakeStore(listOf(source))
            var cancelReads = true
            val adapter = object : BpSource {
                val requests = mutableListOf<SourceReadRequest>()
                override val sourceTypeId: String = SLOTS_TYPE
                override val displayNameRes: Int get() = 0
                override val capabilities: SourceCapabilities = SLOTS_CAPABILITIES
                override suspend fun read(request: SourceReadRequest): SourceReadResult {
                    requests += request
                    if (cancelReads) throw CancellationException("cancelled before the session")
                    return SourceReadResult.Success(listOf(reading(7_000, userSlot = 1)))
                }
            }
            val useCase = newUseCase(sourceFor = { adapter }, store = store)

            assertTrue(runCatching { useCase.read(source) }.isFailure)
            assertTrue(store.sources.single().fullReadFallbackPending)

            cancelReads = false
            val recovery = useCase.read(source)

            assertEquals(SyncMode.HISTORY, adapter.requests.last().syncMode) // one full-ring plan
            assertEquals(
                OmronBpRepository.StoreResult(insertedCount = 1, duplicatesCount = 0),
                recovery.stored
            )
            val recovered = store.sources.single()
            assertFalse(recovered.fullReadFallbackPending) // cleared by the successful store
            assertEquals(7_000L, recovered.lastReadAnchor)

            useCase.read(source)

            assertEquals(SyncMode.CURRENT, adapter.requests.last().syncMode) // Current resumed
            assertEquals(SyncMode.CURRENT, store.sources.single().syncMode) // the stored mode never changed
        }

    @Test
    fun `a cancelled current pass of a history-less source sets no fallback`() = runTest {
        // Health Connect shape: no History support means no fallback - the
        // source's own anchor (the changes token) advances only on success,
        // so a cancelled pass re-reads exactly what it missed. The pre-plan
        // write must respect that boundary too.
        val source = aNoneSource()
        val store = FakeStore(listOf(source))
        val cancelled = object : BpSource {
            override val sourceTypeId: String = NONE_TYPE
            override val displayNameRes: Int get() = 0
            override val capabilities: SourceCapabilities = NONE_CAPABILITIES
            override suspend fun read(request: SourceReadRequest): SourceReadResult =
                throw CancellationException("provider ipc died")
        }

        assertTrue(
            runCatching { newUseCase(sourceFor = { cancelled }, store = store).read(source) }.isFailure
        )

        assertFalse(store.sources.single().fullReadFallbackPending)
    }

    @Test
    fun `a dao failure on a current pass keeps the fallback flag and the anchor`() = runTest {
        // The xlg.5 review's finding 2 on a plain Current pass: the device
        // consumed its window, Room holds nothing of it (success false) -
        // advancing the anchor or clearing the flag here would lose that
        // window for good.
        val source = aSlotsSource(userSlot = 1)
            .copy(syncMode = SyncMode.CURRENT, lastReadAnchor = 3_000L)
        val store = FakeStore(listOf(source))
        slotsAdapter.result = SourceReadResult.Success(listOf(reading(9_000, userSlot = 1)))
        dao.failure = IllegalStateException("disk I/O error")

        val outcome = newUseCase(store = store).read(source)

        assertTrue(outcome.result is SourceReadResult.Success)
        assertEquals(false, outcome.stored?.success)
        val row = store.sources.single()
        assertTrue(row.fullReadFallbackPending) // the pre-plan flag survives the DAO failure
        assertEquals(3_000L, row.lastReadAnchor) // nothing advanced
        assertEquals(SyncMode.CURRENT, row.syncMode)
    }

    @Test
    fun `a dao failure on the full-ring recovery pass keeps the fallback flag and the anchor`() =
        runTest {
            // Finding 2's named scenario: the recovery History pass consumed
            // the window the failed Current pass may have lost; if its store
            // then fails silently (success false, nothing written), clearing
            // the flag and advancing the anchor would discard that window.
            val source = aSlotsSource(userSlot = 1)
                .copy(syncMode = SyncMode.CURRENT, lastReadAnchor = 3_000L)
            val store = FakeStore(listOf(source))
            val useCase = newUseCase(store = store)
            slotsAdapter.result = SourceReadResult.Failed("link dropped after the counter reset")
            useCase.read(source) // the Current pass fails; the flag is set
            assertTrue(store.sources.single().fullReadFallbackPending)

            // The recovery pass re-reads the whole ring and delivers the
            // lost window - but the DAO fails: nothing was written.
            dao.failure = IllegalStateException("disk I/O error")
            slotsAdapter.result = SourceReadResult.Success(listOf(reading(9_000, userSlot = 1)))

            val outcome = useCase.read(source)

            assertEquals(SyncMode.HISTORY, slotsAdapter.requests.last().syncMode)
            assertEquals(false, outcome.stored?.success)
            val row = store.sources.single()
            assertTrue(row.fullReadFallbackPending) // stays: the next pass re-runs the full ring
            assertEquals(3_000L, row.lastReadAnchor) // unchanged: the window is not ingested
        }

    @Test
    fun `a dao failure on a current pass of a deferred-commit source defers the journal and the retry commits`() =
        runTest {
            // The xlg.15/xlg.16 interleaving on a (hypothetical
            // Current-capable) deferred-commit source: a Current pass whose
            // store fails must keep BOTH its journal uncommitted (xlg.15:
            // no commit without a confirmed store) and the fallback flag
            // set (xlg.16: no state advance without one) - the full-ring
            // retry then re-delivers, stores and commits, in that order.
            val deferred = object : BpSource, DeferredCommitSource {
                val requests = mutableListOf<SourceReadRequest>()
                var commits = 0
                var result: SourceReadResult = SourceReadResult.Success(emptyList())
                override val sourceTypeId: String = NONE_DUAL_TYPE
                override val displayNameRes: Int get() = 0
                override val capabilities: SourceCapabilities = NONE_DUAL_CAPABILITIES
                override suspend fun read(request: SourceReadRequest): SourceReadResult {
                    requests += request
                    return result
                }

                override fun commitDelivered() {
                    commits++
                }
            }
            val source = ConnectedSource(
                id = "source-dual",
                sourceTypeId = NONE_DUAL_TYPE,
                name = NONE_DUAL_TYPE,
                syncMode = SyncMode.CURRENT,
                createdAt = 1L,
            )
            val store = FakeStore(listOf(source))
            val useCase = newUseCase(sourceFor = { deferred }, store = store)
            deferred.result = SourceReadResult.Success(
                listOf(reading(9_000, userSlot = BpReading.APP_OWNER_SLOT))
            )
            dao.failure = IllegalStateException("disk I/O error")

            val failed = useCase.read(source)

            assertEquals(SyncMode.CURRENT, deferred.requests.single().syncMode)
            assertEquals(false, failed.stored?.success)
            assertEquals(0, deferred.commits) // xlg.15: no commit without a confirmed store
            assertTrue(store.sources.single().fullReadFallbackPending) // xlg.16: the flag stays

            dao.failure = null
            val retry = useCase.read(source)

            assertEquals(SyncMode.HISTORY, deferred.requests.last().syncMode) // full-ring retry
            assertEquals(
                OmronBpRepository.StoreResult(insertedCount = 1, duplicatesCount = 0),
                retry.stored
            )
            assertEquals(1, deferred.commits) // committed only after the confirmed store
            val row = store.sources.single()
            assertFalse(row.fullReadFallbackPending) // cleared by the same confirmed pass
            assertEquals(9_000L, row.lastReadAnchor)
        }

    @Test
    fun `a successful history pass of a history-mode source clears a stale fallback flag`() =
        runTest {
            // "Cleared only in advanceReadState" must not strand the flag
            // when the user's configured mode is History: any successful
            // pass clears it.
            val source = aSlotsSource(userSlot = 1)
                .copy(syncMode = SyncMode.HISTORY, fullReadFallbackPending = true)
            val store = FakeStore(listOf(source))
            slotsAdapter.result = SourceReadResult.Success(listOf(reading(5_000, userSlot = 1)))

            val outcome = newUseCase(store = store).read(source)

            assertEquals(SyncMode.HISTORY, slotsAdapter.requests.single().syncMode) // the user's mode
            assertTrue(outcome.result is SourceReadResult.Success)
            val row = store.sources.single()
            assertFalse(row.fullReadFallbackPending)
            assertEquals(5_000L, row.lastReadAnchor)
        }
}
