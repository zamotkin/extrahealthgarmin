package com.zamotkin.extrahealthgarmin.omron.data

import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [OmronBpRepository] tests (task tefal2garmin-4uy, epic tefal2garmin-939).
 * Plain JUnit — no Robolectric (see app/build.gradle): the repository depends
 * only on the [BloodPressureDao] interface, so an in-memory fake DAO is the
 * whole fixture. The fake honors Room's @Insert(IGNORE) contract (unique key =
 * timestampEpochSec + userSlot, ignored rows report -1) and the query filters,
 * so dedup/ordering claims are proven against DAO semantics, not faked away.
 *
 * CancellationException hygiene test added by task tefal2garmin-ji0.
 */
class OmronBpRepositoryTest {

    // ------------------------------------------------------------------ //
    //  Fake DAO (in-memory, Room @Insert(IGNORE) semantics)               //
    // ------------------------------------------------------------------ //

    /**
     * Mutable-list [BloodPressureDao]: rows get sequential ids, a conflicting
     * (timestampEpochSec, userSlot) pair returns -1L and inserts nothing,
     * queries filter and order exactly like their SQL counterparts.
     * [failure], when set, makes every call throw.
     */
    private class FakeBpDao : BloodPressureDao {
        val rows = mutableListOf<BloodPressureRecordEntity>()
        private var nextId = 1L
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

        override suspend fun unsyncedForUser(userSlot: Int): List<BloodPressureRecordEntity> {
            failure?.let { throw it }
            return rows
                .filter { it.userSlot == userSlot && !it.synced }
                .sortedBy { it.timestampEpochSec }
        }

        override suspend fun unsyncedAll(): List<BloodPressureRecordEntity> {
            failure?.let { throw it }
            return rows.filter { !it.synced }.sortedBy { it.timestampEpochSec }
        }

        override suspend fun markSynced(timestampEpochSec: Long, userSlot: Int) {
            failure?.let { throw it }
            // Mirrors the DAO SQL: every source's row with that key flips,
            // because the Garmin journal dedups per (timestamp, userSlot).
            rows.indices.forEach { index ->
                val row = rows[index]
                if (row.timestampEpochSec == timestampEpochSec && row.userSlot == userSlot) {
                    rows[index] = row.copy(synced = true)
                }
            }
        }

        override suspend fun latest(userSlot: Int, limit: Int): List<BloodPressureRecordEntity> {
            failure?.let { throw it }
            return rows
                .filter { it.userSlot == userSlot }
                .sortedByDescending { it.timestampEpochSec }
                .take(limit)
        }

        override suspend fun allForUser(userSlot: Int): List<BloodPressureRecordEntity> {
            failure?.let { throw it }
            return rows
                .filter { it.userSlot == userSlot }
                .sortedByDescending { it.timestampEpochSec }
        }

        override suspend fun allForSource(
            sourceId: String,
            userSlot: Int?,
        ): List<BloodPressureRecordEntity> {
            failure?.let { throw it }
            return rows
                .filter { it.sourceId == sourceId && (userSlot == null || it.userSlot == userSlot) }
                .sortedByDescending { it.timestampEpochSec }
        }

        override suspend fun unsyncedForSource(
            sourceId: String,
            userSlot: Int?,
        ): List<BloodPressureRecordEntity> {
            failure?.let { throw it }
            return rows
                .filter {
                    it.sourceId == sourceId &&
                        !it.synced &&
                        (userSlot == null || it.userSlot == userSlot)
                }
                .sortedBy { it.timestampEpochSec }
        }
    }

    // ------------------------------------------------------------------ //
    //  Fixtures                                                           //
    // ------------------------------------------------------------------ //

    private fun reading(
        timestampEpochSec: Long,
        userSlot: Int = 1,
        systolic: Int = 120,
        diastolic: Int = 80,
        pulse: Int = 70,
        mov: Boolean = false,
        ihb: Boolean = false
    ) = BloodPressureReading(
        timestampEpochSec = timestampEpochSec,
        systolic = systolic,
        diastolic = diastolic,
        pulse = pulse,
        mov = mov,
        ihb = ihb,
        userSlot = userSlot
    )

    private val dao = FakeBpDao()
    private val errors = mutableListOf<Throwable>()
    private val repository = OmronBpRepository(dao, onError = { errors.add(it) })

    // ------------------------------------------------------------------ //
    //  storeReadings                                                      //
    // ------------------------------------------------------------------ //

    @Test
    fun `new readings are inserted and counted`() = runTest {
        val result = repository.storeReadings(
            listOf(reading(1_000, systolic = 135), reading(2_000, userSlot = 2))
        )

        assertEquals(OmronBpRepository.StoreResult(insertedCount = 2, duplicatesCount = 0), result)
        assertEquals(2, dao.rows.size)
        assertEquals(135, dao.rows[0].systolic)
        assertEquals(2, dao.rows[1].userSlot)
        assertFalse(dao.rows[0].synced)
    }

    @Test
    fun `duplicate timestamp and userSlot is ignored and counted as duplicate`() = runTest {
        dao.rows.add(
            BloodPressureRecordEntity(
                timestampEpochSec = 1_000, systolic = 120, diastolic = 80,
                pulse = 70, mov = false, ihb = false, userSlot = 1
            )
        )

        val result = repository.storeReadings(
            listOf(reading(1_000, systolic = 999), reading(2_000))
        )

        assertEquals(OmronBpRepository.StoreResult(insertedCount = 1, duplicatesCount = 1), result)
        // The pre-existing row is untouched — IGNORE keeps the first version.
        assertEquals(120, dao.rows.first { it.timestampEpochSec == 1_000L }.systolic)
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `same timestamp different userSlot is not a duplicate`() = runTest {
        val result = repository.storeReadings(
            listOf(reading(1_000, userSlot = 1), reading(1_000, userSlot = 2))
        )

        assertEquals(OmronBpRepository.StoreResult(insertedCount = 2, duplicatesCount = 0), result)
        assertEquals(2, dao.rows.size)
    }

    // ------------------------------------------------------------------ //
    //  sourceId (task tefal2garmin-app-redesign-v3-xlg.2)                 //
    // ------------------------------------------------------------------ //

    @Test
    fun `storeReadings tags rows with the given source id`() = runTest {
        repository.storeReadings(listOf(reading(1_000)), sourceId = "uuid-of-a-real-source")

        assertEquals("uuid-of-a-real-source", dao.rows.single().sourceId)
    }

    @Test
    fun `storeReadings without a source id defaults to the legacy one`() = runTest {
        repository.storeReadings(listOf(reading(1_000)))

        assertEquals(BloodPressureRecordEntity.LEGACY_SOURCE_ID, dao.rows.single().sourceId)
    }

    @Test
    fun `same timestamp and slot for different sources are distinct rows`() = runTest {
        val first = repository.storeReadings(listOf(reading(1_000)), sourceId = "source-a")
        val second = repository.storeReadings(listOf(reading(1_000)), sourceId = "source-b")
        val again = repository.storeReadings(listOf(reading(1_000)), sourceId = "source-a")

        assertEquals(OmronBpRepository.StoreResult(insertedCount = 1, duplicatesCount = 0), first)
        assertEquals(OmronBpRepository.StoreResult(insertedCount = 1, duplicatesCount = 0), second)
        assertEquals(OmronBpRepository.StoreResult(insertedCount = 0, duplicatesCount = 1), again)
        assertEquals(
            listOf("source-a", "source-b"),
            dao.rows.map { it.sourceId }
        )
    }

    @Test
    fun `markSynced marks every source row with that timestamp and slot`() = runTest {
        // Two sources hold the same measurement: once Garmin holds it (the
        // journal is keyed timestamp+slot), no copy can upload again, so the
        // mark must cover both rows.
        repository.storeReadings(listOf(reading(1_000)), sourceId = "source-a")
        repository.storeReadings(listOf(reading(1_000)), sourceId = "source-b")

        assertTrue(repository.markSynced(1_000, userSlot = 1))

        assertTrue(dao.rows.all { it.synced })
    }

    // ------------------------------------------------------------------ //
    //  unsynced                                                           //
    // ------------------------------------------------------------------ //

    @Test
    fun `unsynced filters by user slot`() = runTest {
        repository.storeReadings(
            listOf(
                reading(3_000, userSlot = 1), reading(1_000, userSlot = 1),
                reading(2_000, userSlot = 2)
            )
        )

        val slot1 = repository.unsynced(userSlot = 1)
        val all = repository.unsynced()

        assertEquals(listOf(1_000L, 3_000L), slot1.map { it.timestampEpochSec })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), all.map { it.timestampEpochSec })
    }

    @Test
    fun `unsynced skips already synced records and maps back to domain`() = runTest {
        repository.storeReadings(listOf(reading(1_000, mov = true, ihb = true)))
        repository.markSynced(1_000, userSlot = 1)

        assertEquals(emptyList<BloodPressureReading>(), repository.unsynced())

        // Domain round-trip proof on a still-unsynced record.
        repository.storeReadings(listOf(reading(2_000, pulse = 55)))
        val domain = repository.unsynced(userSlot = 1)
        assertEquals(1, domain.size)
        assertEquals(
            BloodPressureReading(
                timestampEpochSec = 2_000, systolic = 120, diastolic = 80,
                pulse = 55, mov = false, ihb = false, userSlot = 1
            ),
            domain.single()
        )
    }

    // ------------------------------------------------------------------ //
    //  markSynced                                                         //
    // ------------------------------------------------------------------ //

    @Test
    fun `markSynced flips only the matching record`() = runTest {
        repository.storeReadings(
            listOf(
                reading(1_000, userSlot = 1), reading(1_000, userSlot = 2),
                reading(2_000, userSlot = 1)
            )
        )

        assertTrue(repository.markSynced(1_000, userSlot = 1))

        assertTrue(dao.rows.single { it.timestampEpochSec == 1_000L && it.userSlot == 1 }.synced)
        assertFalse(dao.rows.single { it.timestampEpochSec == 1_000L && it.userSlot == 2 }.synced)
        assertFalse(dao.rows.single { it.timestampEpochSec == 2_000L }.synced)
    }

    // ------------------------------------------------------------------ //
    //  latest                                                             //
    // ------------------------------------------------------------------ //

    @Test
    fun `latest returns newest first limited to the requested count`() = runTest {
        repository.storeReadings(
            listOf(
                reading(1_000, userSlot = 1), reading(5_000, userSlot = 1),
                reading(3_000, userSlot = 1), reading(4_000, userSlot = 2)
            )
        )

        val top2 = repository.latest(userSlot = 1, limit = 2)
        val default20 = repository.latest(userSlot = 1)

        assertEquals(listOf(5_000L, 3_000L), top2.map { it.timestampEpochSec })
        assertEquals(3, default20.size)
        assertEquals(5_000L, default20.first().timestampEpochSec)
    }

    // ------------------------------------------------------------------ //
    //  Source-scoped queries (task tefal2garmin-app-redesign-v3-xlg.4)    //
    // ------------------------------------------------------------------ //

    /** Display filter: one source's rows only, other sources stay invisible. */
    @Test
    fun `allForSource returns only that source's rows newest first`() = runTest {
        repository.storeReadings(
            listOf(reading(1_000), reading(2_000)),
            sourceId = "source-a"
        )
        repository.storeReadings(listOf(reading(3_000)), sourceId = "source-b")

        val rows = repository.allForSource(sourceId = "source-a")

        assertEquals(listOf(2_000L, 1_000L), rows.map { it.timestampEpochSec })
    }

    /**
     * The R4 display/upload filter: rows of other user slots - read before a
     * slot was picked - stay invisible once the source has its user.
     */
    @Test
    fun `allForSource with a user slot hides the other slot's rows`() = runTest {
        repository.storeReadings(
            listOf(
                reading(1_000, userSlot = 1),
                reading(2_000, userSlot = 2),
                reading(3_000, userSlot = 1),
            ),
            sourceId = "source-a"
        )

        val selected = repository.allForSource(sourceId = "source-a", userSlot = 2)

        assertEquals(listOf(2_000L), selected.map { it.timestampEpochSec })
    }

    /** No slot filter: every slot of the source (the NONE-attribution shape). */
    @Test
    fun `allForSource without a slot returns every slot of the source`() = runTest {
        repository.storeReadings(
            listOf(reading(1_000, userSlot = 1), reading(2_000, userSlot = 2)),
            sourceId = "source-a"
        )
        repository.storeReadings(listOf(reading(3_000, userSlot = 1)), sourceId = "source-b")

        val all = repository.allForSource(sourceId = "source-a")

        assertEquals(listOf(2_000L, 1_000L), all.map { it.timestampEpochSec })
    }

    /** Upload filter: unsynced rows of one source (and slot), oldest first. */
    @Test
    fun `unsyncedForSource filters source and slot and skips synced rows`() = runTest {
        repository.storeReadings(
            listOf(
                reading(1_000, userSlot = 1),
                reading(2_000, userSlot = 2),
                reading(3_000, userSlot = 1),
            ),
            sourceId = "source-a"
        )
        repository.storeReadings(listOf(reading(4_000, userSlot = 1)), sourceId = "source-b")
        repository.markSynced(1_000, userSlot = 1)

        val uploadSlot1 = repository.unsyncedForSource(sourceId = "source-a", userSlot = 1)
        val uploadAllSlots = repository.unsyncedForSource(sourceId = "source-a")

        assertEquals(listOf(3_000L), uploadSlot1.map { it.timestampEpochSec })
        assertEquals(listOf(2_000L, 3_000L), uploadAllSlots.map { it.timestampEpochSec })
    }

    // ------------------------------------------------------------------ //
    //  Never throws                                                       //
    // ------------------------------------------------------------------ //

    @Test
    fun `allForUser returns every reading of the slot newest first without a cap`() = runTest {
        // 25 rows of slot 1 — one more than the old 20-row dashboard cap.
        repository.storeReadings(
            (1..25).map { reading(it * 100L, userSlot = 1) } + reading(2_600, userSlot = 2)
        )

        val all = repository.allForUser(userSlot = 1)

        assertEquals(25, all.size)
        assertEquals((25 downTo 1).map { it * 100L }, all.map { it.timestampEpochSec })
        // Domain round-trip proof.
        assertEquals(
            BloodPressureReading(
                timestampEpochSec = 2_500, systolic = 120, diastolic = 80,
                pulse = 70, mov = false, ihb = false, userSlot = 1
            ),
            all.first()
        )
    }

    @Test
    fun `dao exception does not throw and reports failure through onError`() = runTest {
        dao.failure = IllegalStateException("disk I/O error")

        val store = repository.storeReadings(listOf(reading(1_000)))
        val unsynced = repository.unsynced()
        val marked = repository.markSynced(1_000, userSlot = 1)
        val latest = repository.latest(userSlot = 1)
        val all = repository.allForUser(userSlot = 1)
        val allForSource = repository.allForSource(sourceId = "source-a")
        val unsyncedForSource = repository.unsyncedForSource(sourceId = "source-a")

        assertEquals(OmronBpRepository.StoreResult(insertedCount = 0, duplicatesCount = 0), store)
        assertEquals(emptyList<BloodPressureReading>(), unsynced)
        assertFalse(marked)
        assertEquals(emptyList<BloodPressureReading>(), latest)
        assertEquals(emptyList<BloodPressureReading>(), all)
        assertEquals(emptyList<BloodPressureReading>(), allForSource)
        assertEquals(emptyList<BloodPressureReading>(), unsyncedForSource)
        assertEquals(7, errors.size)
        assertTrue(errors.all { it is IllegalStateException })
    }

    /**
     * Task tefal2garmin-ji0: a CancellationException from the DAO must
     * PROPAGATE (rethrown before the runCatching fallback) — swallowing it
     * into an empty-list fallback would keep a cancelled coroutine running
     * as if nothing happened.
     */
    @Test
    fun `cancellation exception from dao propagates instead of falling back`() = runTest {
        dao.failure = CancellationException("coroutine cancelled")

        try {
            repository.unsynced()
            fail("CancellationException must propagate, not fall back to an empty list")
        } catch (e: CancellationException) {
            // expected — the gateway rethrows cancellation before the fallback
        }

        assertEquals("cancellation is not a DAO failure — onError stays silent", 0, errors.size)
    }

    /** Settings "delete all": every stored row disappears, success reported. */
    @Test
    fun `delete_all_removes_every_stored_reading`() = runTest {
        repository.storeReadings(
            listOf(
                BloodPressureReading(1L, 120, 80, 60, mov = false, ihb = false, userSlot = 1),
                BloodPressureReading(2L, 125, 85, 65, mov = false, ihb = false, userSlot = 1),
            ),
        )

        val deleted = repository.deleteAll()

        assertTrue(deleted)
        assertTrue("table is empty after deleteAll", dao.rows.isEmpty())
        assertTrue(repository.latest(userSlot = 1).isEmpty())
    }

    /** A DAO failure on deleteAll reports false and keeps onError in the loop. */
    @Test
    fun `delete_all_failure_reports_false`() = runTest {
        dao.failure = IllegalStateException("disk gone")

        val deleted = repository.deleteAll()

        assertFalse(deleted)
        assertEquals(1, errors.size)
    }

    // ------------------------------------------------------------------ //
    //  deleteForUser (task tefal2garmin-7m2)                              //
    // ------------------------------------------------------------------ //

    /** Settings "delete profile readings": only that slot's rows die. */
    @Test
    fun `delete_for_user_removes_only_that_slots_rows`() = runTest {
        repository.storeReadings(
            listOf(
                reading(1_000, userSlot = 1), reading(2_000, userSlot = 1),
                reading(3_000, userSlot = 2)
            )
        )

        val deleted = repository.deleteForUser(userSlot = 1)

        assertTrue(deleted)
        assertEquals(
            "only the other slot's row survives",
            listOf(3_000L),
            dao.rows.map { it.timestampEpochSec },
        )
        // the untouched slot stays fully queryable
        assertEquals(1, repository.unsynced(userSlot = 2).size)
        assertEquals(0, repository.unsynced(userSlot = 1).size)
    }

    /** A DAO failure on deleteForUser reports false, wipes nothing. */
    @Test
    fun `delete_for_user_failure_reports_false_and_keeps_rows`() = runTest {
        repository.storeReadings(listOf(reading(1_000, userSlot = 1), reading(2_000, userSlot = 2)))
        dao.failure = IllegalStateException("disk gone")

        val deleted = repository.deleteForUser(userSlot = 1)

        assertFalse(deleted)
        assertEquals(2, dao.rows.size)
        assertEquals(1, errors.size)
    }
}
