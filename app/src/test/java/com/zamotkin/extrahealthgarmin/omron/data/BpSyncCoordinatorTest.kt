package com.zamotkin.extrahealthgarmin.omron.data

import com.zamotkin.extrahealthgarmin.data.GarminRemote
import com.zamotkin.extrahealthgarmin.data.GarminRepository
import com.zamotkin.extrahealthgarmin.data.UploadOutcome
import com.zamotkin.extrahealthgarmin.garmin.BpUploadJournal
import com.zamotkin.extrahealthgarmin.garmin.GarminTokenStore
import com.zamotkin.extrahealthgarmin.garmin.GarminTokens
import com.zamotkin.extrahealthgarmin.garmin.GarminWeighInRecord
import com.zamotkin.extrahealthgarmin.garmin.JournalPersistence
import com.zamotkin.extrahealthgarmin.garmin.LoginResult
import com.zamotkin.extrahealthgarmin.garmin.MfaClientState
import com.zamotkin.extrahealthgarmin.garmin.TokenCrypto
import com.zamotkin.extrahealthgarmin.garmin.TokenPersistence
import com.zamotkin.extrahealthgarmin.garmin.UploadJournal
import com.zamotkin.extrahealthgarmin.garmin.UploadResult
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [BpSyncCoordinator] tests — one direct-upload pass over the real
 * [OmronBpRepository] (in-memory fake DAO, same style as
 * [OmronBpRepositoryTest]) with the Garmin upload entry point injected as a
 * staging fake. Plain JUnit, no Robolectric (see app/build.gradle).
 *
 * Task tefal2garmin-ji0 added the two-pass lost-markSynced integration test
 * (the upload lambda wired through the REAL [GarminRepository] + real
 * [BpUploadJournal] over a shared persistence) and the CancellationException
 * hygiene test.
 */
class BpSyncCoordinatorTest {

    // ------------------------------------------------------------------ //
    //  Fakes                                                              //
    // ------------------------------------------------------------------ //

    /**
     * Mutable-list [BloodPressureDao] honoring the contract the coordinator
     * relies on: unsynced ordering, per-(timestamp, userSlot) markSynced.
     * Only the queries the coordinator touches are faithfully implemented.
     * [markSyncedFailure] (task tefal2garmin-ji0) is per-method injection:
     * when set, ONLY markSynced throws — reproducing a lost markSynced while
     * every other DAO operation stays healthy.
     */
    private class FakeBpDao : BloodPressureDao {
        val rows = mutableListOf<BloodPressureRecordEntity>()
        private var nextId = 1L
        var failure: Exception? = null
        var markSyncedFailure: Exception? = null

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
            markSyncedFailure?.let { throw it }
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

    // ------------------------------------------------------------------ //
    //  Garmin-side fakes (two-pass integration test, task ji0)            //
    // ------------------------------------------------------------------ //

    /**
     * Counting fake of the repository's network seam (same style as
     * GarminRepositoryBpTest.FakeRemote): login/weight/logout fail on touch,
     * [uploadBloodPressure] throws unless a result/exception was staged.
     */
    private class FakeGarminRemote : GarminRemote {
        var bpUploadResult: UploadResult? = null
        var bpUploadException: Exception? = null

        var bpUploadCalls = 0
            private set

        override suspend fun login(email: String, password: String): LoginResult =
            throw AssertionError("login() is not under test in BpSyncCoordinatorTest")

        override suspend fun resumeLogin(clientState: MfaClientState, code: String): GarminTokens =
            throw AssertionError("resumeLogin() is not under test in BpSyncCoordinatorTest")

        override suspend fun uploadWeighIns(
            records: List<GarminWeighInRecord>,
            tokens: GarminTokens
        ): UploadResult =
            throw AssertionError("uploadWeighIns() is not under test in BpSyncCoordinatorTest")

        override suspend fun uploadBloodPressure(
            records: List<BloodPressureReading>,
            tokens: GarminTokens
        ): UploadResult {
            bpUploadCalls++
            bpUploadException?.let { throw it }
            return bpUploadResult
                ?: throw AssertionError("uploadBloodPressure must not be called un-staged")
        }

        override fun logout() =
            throw AssertionError("logout() is not under test in BpSyncCoordinatorTest")
    }

    private class PlainTextCrypto : TokenCrypto {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(stored: String): String = stored
    }

    private class InMemoryTokenPersistence : TokenPersistence {
        var stored: String? = null

        override fun read(): String? = stored

        override fun write(value: String) {
            stored = value
        }

        override fun remove() {
            stored = null
        }
    }

    private class InMemoryJournalPersistence : JournalPersistence {
        var stored: String? = null

        override fun load(): String? = stored

        override fun save(json: String) {
            stored = json
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

    private fun syncedRows(): List<Pair<Long, Int>> = syncedRowsOf(dao)

    private fun syncedRowsOf(dao: FakeBpDao): List<Pair<Long, Int>> =
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

    // ------------------------------------------------------------------ //
    //  Task tefal2garmin-ji0: lost-markSynced two-pass integration        //
    // ------------------------------------------------------------------ //

    /**
     * End-to-end lost-markSynced reproduction (task tefal2garmin-ji0): the
     * upload lambda is the REAL [GarminRepository.uploadBloodPressure] over
     * a real [BpUploadJournal] whose in-memory persistence is SHARED across
     * both passes — nothing about the outcome is staged. Pass 1: Garmin
     * confirms the batch and the journal records it, but every markSynced
     * dies on the DAO, so the rows stay un-synced. Pass 2 (markSynced
     * healthy again): the shared journal makes the honest repository path
     * answer NotNeeded with ZERO network calls, and the coordinator's
     * NotNeeded self-heal marks the rows synced.
     */
    @Test
    fun `lost_markSynced_self_heals_on_next_pass`() = runTest {
        val remote = FakeGarminRemote()
        val tokenStore = GarminTokenStore(InMemoryTokenPersistence(), PlainTextCrypto())
        tokenStore.save(TOKENS)
        val sharedJournal = BpUploadJournal(InMemoryJournalPersistence())
        val garminRepository = GarminRepository(
            remote,
            tokenStore,
            UploadJournal(InMemoryJournalPersistence()), // weight path not under test
            sharedJournal
        )
        val realUpload: suspend (List<BloodPressureReading>) -> UploadOutcome =
            { records -> garminRepository.uploadBloodPressure(records) }

        val integrationDao = FakeBpDao()
        val integrationErrors = mutableListOf<Throwable>()
        val integrationRepository = OmronBpRepository(integrationDao, onError = { integrationErrors.add(it) })
        val integrationCoordinator = BpSyncCoordinator(integrationRepository, realUpload)
        val readings = listOf(reading(1_000), reading(2_000, userSlot = 2))
        integrationRepository.storeReadings(readings)

        // Pass 1: the upload is confirmed and journaled, but every markSynced
        // is lost to a DAO failure.
        remote.bpUploadResult = UploadResult.Success(2, TOKENS)
        integrationDao.markSyncedFailure = IllegalStateException("disk I/O error")
        val pass1 = integrationCoordinator.syncOnce()

        assertTrue("pass 1 must confirm the batch, was $pass1", pass1 is UploadOutcome.BpUploaded)
        assertEquals(
            "rows stay un-synced when markSynced is lost",
            emptyList<Pair<Long, Int>>(),
            syncedRowsOf(integrationDao)
        )
        assertEquals(
            "each lost markSynced surfaces through the repository's onError",
            2,
            integrationErrors.size
        )
        assertEquals("the Garmin journal DID record the confirmed upload", 2, sharedJournal.uploadedCount())
        assertEquals(1, remote.bpUploadCalls)

        // Pass 2: the journal already covers the batch — the repository path
        // answers NotNeeded without touching the network, and the
        // coordinator's self-heal finally marks the rows synced.
        integrationDao.markSyncedFailure = null
        val pass2 = integrationCoordinator.syncOnce()

        assertTrue("pass 2 must self-heal to NotNeeded, was $pass2", pass2 is UploadOutcome.NotNeeded)
        assertEquals(
            "every previously-confirmed row is now synced",
            listOf<Pair<Long, Int>>(1_000L to 1, 2_000L to 2),
            syncedRowsOf(integrationDao).sortedBy { it.first }
        )
        assertEquals("the journaled batch must not be re-uploaded", 1, remote.bpUploadCalls)
    }

    // ------------------------------------------------------------------ //
    //  Task tefal2garmin-ji0: CancellationException hygiene               //
    // ------------------------------------------------------------------ //

    /**
     * Cancellation from the upload lambda must PROPAGATE (rethrown before
     * the onError/Retryable fallback) — swallowing it would turn a cancelled
     * coroutine into a phantom upload failure.
     */
    @Test
    fun `cancellation_exception_from_upload_propagates`() = runTest {
        repository.storeReadings(listOf(reading(1_000)))
        uploadException = CancellationException("coroutine cancelled")

        try {
            coordinator.syncOnce()
            fail("CancellationException must propagate out of syncOnce")
        } catch (e: CancellationException) {
            // expected — rethrown before the onError/Retryable fallback
        }

        assertEquals("cancellation is not an upload failure — onError stays silent", 0, coordinatorErrors.size)
        assertEquals("nothing marked synced", emptyList<Pair<Long, Int>>(), syncedRows())
    }

    private companion object {
        private val TOKENS = GarminTokens(
            diToken = "jwt-access",
            diRefreshToken = "jwt-refresh",
            diClientId = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"
        )
    }
}
