package com.zamotkin.extrahealthgarmin.devices

import com.zamotkin.extrahealthgarmin.devices.api.BpReading
import com.zamotkin.extrahealthgarmin.devices.api.BpSource
import com.zamotkin.extrahealthgarmin.devices.api.BpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.SourceCapabilities
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadRequest
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
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
import org.junit.Test

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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceReadUseCaseTest {

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
    }

    private val registry = SourceRegistry(
        listOf(FakeType(SLOTS_TYPE, SLOTS_CAPABILITIES), FakeType(NONE_TYPE, NONE_CAPABILITIES))
    )
    private val dao = FakeBpDao()
    private val errors = mutableListOf<Throwable>()
    private val repository = OmronBpRepository(dao, onError = { errors.add(it) })
    private val slotsAdapter =
        RecordingSource(SLOTS_TYPE, SLOTS_CAPABILITIES, SourceReadResult.Success(emptyList()))
    private val noneAdapter =
        RecordingSource(NONE_TYPE, NONE_CAPABILITIES, SourceReadResult.Success(emptyList()))

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
    fun `a dao failure during store reports zero counts without throwing`() = runTest {
        dao.failure = IllegalStateException("disk I/O error")
        slotsAdapter.result = SourceReadResult.Success(listOf(reading(1_000, userSlot = 1)))

        val outcome = newUseCase().read(aSlotsSource(userSlot = 1))

        assertTrue(outcome.result is SourceReadResult.Success)
        assertEquals(OmronBpRepository.StoreResult(insertedCount = 0, duplicatesCount = 0), outcome.stored)
        assertEquals(1, errors.size)
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
}
