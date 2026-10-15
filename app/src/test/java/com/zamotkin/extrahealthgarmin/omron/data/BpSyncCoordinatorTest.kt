package com.zamotkin.extrahealthgarmin.omron.data

import com.zamotkin.extrahealthgarmin.data.UploadOutcome
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BpSyncCoordinator] tests — one direct-upload pass over the real
 * [OmronBpRepository] (in-memory fake DAO, same style as
 * [OmronBpRepositoryTest]) with the Garmin upload entry point injected as a
 * staging fake. Plain JUnit, no Robolectric (see app/build.gradle).
 */
class BpSyncCoordinatorTest {

    // ------------------------------------------------------------------ //
    //  Fakes                                                              //
    // ------------------------------------------------------------------ //

    /**
     * Mutable-list [BloodPressureDao] honoring the contract the coordinator
     * relies on: unsynced ordering, per-(timestamp, userSlot) markSynced.
     * Only the queries the coordinator touches are faithfully implemented.
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
                        it.userSlot == record.userSlot
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
            val index = rows.indexOfFirst {
                it.timestampEpochSec == timestampEpochSec && it.userSlot == userSlot
            }
            if (index >= 0) {
                rows[index] = rows[index].copy(synced = true)
            }
        }

        override suspend fun latest(userSlot: Int, limit: Int): List<BloodPressureRecordEntity> {
            failure?.let { throw it }
            return rows
                .filter { it.userSlot == userSlot }
                .sortedByDescending { it.timestampEpochSec }
                .take(limit)
        }
    }

    /** Staging fake for the data-layer upload entry point. */
    private var stagedOutcome: UploadOutcome = UploadOutcome.BpUploaded(emptyList())
    private var uploadException: Exception? = null
    private var uploadCalls = 0
    private var lastUploaded: List<BloodPressureReading>? = null
    private val upload: suspend (List<BloodPressureReading>) -> UploadOutcome = { records ->
        uploadCalls++
        lastUploaded = records
        uploadException?.let { throw it }
        stagedOutcome
    }

    private val dao = FakeBpDao()
    private val repositoryErrors = mutableListOf<Throwable>()
    private val repository = OmronBpRepository(dao, onError = { repositoryErrors.add(it) })
    private val coordinatorErrors = mutableListOf<Throwable>()
    private val coordinator =
        BpSyncCoordinator(repository, upload, onError = { coordinatorErrors.add(it) })

    private fun reading(timestampEpochSec: Long, userSlot: Int = 1): BloodPressureReading =
        BloodPressureReading(
            timestampEpochSec = timestampEpochSec,
            systolic = 120,
            diastolic = 80,
            pulse = 62,
            mov = false,
            ihb = false,
            userSlot = userSlot
        )

    private fun syncedRows(): List<Pair<Long, Int>> =
        dao.rows.filter { it.synced }
            .map { it.timestampEpochSec to it.userSlot }

    // ------------------------------------------------------------------ //
    //  Spec scenarios (names match the task's TDD list 1:1)              //
    // ------------------------------------------------------------------ //

    @Test
    fun `failed_upload_leaves_records_unsynced`() = runTest {
        repository.storeReadings(listOf(reading(1_000), reading(2_000, userSlot = 2)))
        stagedOutcome = UploadOutcome.Retryable("Upload failed: retries exhausted")

        val outcome = coordinator.syncOnce()

        assertTrue("expected Retryable, was $outcome", outcome is UploadOutcome.Retryable)
        assertEquals("the pending batch must have been offered to the upload", 1, uploadCalls)
        assertEquals(
            "nothing may be marked synced on failure",
            emptyList<Pair<Long, Int>>(),
            syncedRows()
        )
    }

    @Test
    fun `success_marks_exactly_the_uploaded_records_synced`() = runTest {
        val confirmed1 = reading(1_000, userSlot = 1)
        val rejected = reading(2_000, userSlot = 1)
        val confirmed2 = reading(2_000, userSlot = 2)
        repository.storeReadings(listOf(confirmed1, rejected, confirmed2))
        stagedOutcome = UploadOutcome.BpUploaded(
            confirmedRecords = listOf(confirmed1, confirmed2),
            failureMessages = listOf("Row 2 rejected")
        )

        val outcome = coordinator.syncOnce()

        assertTrue("expected BpUploaded pass-through, was $outcome", outcome is UploadOutcome.BpUploaded)
        assertEquals(
            "unsynced readings, oldest first",
            listOf(confirmed1, rejected, confirmed2),
            lastUploaded
        )
        assertEquals(
            "exactly the confirmed records are marked synced (composite key: " +
                "USER2 at ts 2000 is synced, USER1 at ts 2000 is not)",
            listOf<Pair<Long, Int>>(1_000L to 1, 2_000L to 2),
            syncedRows().sortedBy { it.first }
        )
    }

    @Test
    fun `upload_throw_does_not_propagate`() = runTest {
        repository.storeReadings(listOf(reading(1_000)))
        uploadException = IllegalStateException("unexpected upload-layer crash")

        val outcome = coordinator.syncOnce()

        assertTrue("expected Retryable, was $outcome", outcome is UploadOutcome.Retryable)
        assertEquals("the failure must be reported through onError", 1, coordinatorErrors.size)
        assertTrue(coordinatorErrors.single() is IllegalStateException)
        assertEquals("nothing marked synced", emptyList<Pair<Long, Int>>(), syncedRows())
    }

    @Test
    fun `login_required_passes_through_without_marking`() = runTest {
        repository.storeReadings(listOf(reading(1_000)))
        stagedOutcome = UploadOutcome.LoginRequired

        val outcome = coordinator.syncOnce()

        assertTrue(outcome is UploadOutcome.LoginRequired)
        assertEquals(emptyList<Pair<Long, Int>>(), syncedRows())
    }

    @Test
    fun `empty_pending_skips_upload_entirely`() = runTest {
        val outcome = coordinator.syncOnce()

        assertTrue("expected NotNeeded, was $outcome", outcome is UploadOutcome.NotNeeded)
        assertEquals("no upload call for an empty pending set", 0, uploadCalls)
    }

    /**
     * Self-heal: NotNeeded over a non-empty pending batch means every pending
     * record is already journaled as uploaded on the Garmin side (e.g. a
     * previous pass confirmed the upload but its markSynced was lost to a DAO
     * failure) — all pending records are marked synced so a confirmed upload
     * can never get stuck un-synced.
     */
    @Test
    fun `not_needed_over_pending_batch_marks_all_synced_self_heal`() = runTest {
        repository.storeReadings(listOf(reading(1_000), reading(1_000, userSlot = 2)))
        stagedOutcome = UploadOutcome.NotNeeded

        val outcome = coordinator.syncOnce()

        assertTrue(outcome is UploadOutcome.NotNeeded)
        assertEquals(
            listOf<Pair<Long, Int>>(1_000L to 1, 1_000L to 2),
            syncedRows().sortedBy { it.second }
        )
    }

    @Test
    fun `dao_failure_reports_error_returns_not_needed_without_upload`() = runTest {
        repository.storeReadings(listOf(reading(1_000)))
        dao.failure = IllegalStateException("disk I/O error")

        val outcome = coordinator.syncOnce()

        assertTrue("expected NotNeeded, was $outcome", outcome is UploadOutcome.NotNeeded)
        assertEquals("no upload may happen when the pending set is unreadable", 0, uploadCalls)
        assertEquals("the DAO failure surfaced through the repository's onError", 1, repositoryErrors.size)
        assertFalse(dao.rows.single().synced)
    }
}
