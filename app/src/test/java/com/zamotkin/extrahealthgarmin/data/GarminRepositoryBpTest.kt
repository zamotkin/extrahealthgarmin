package com.zamotkin.extrahealthgarmin.data

import com.zamotkin.extrahealthgarmin.garmin.BpUploadJournal
import com.zamotkin.extrahealthgarmin.garmin.GarminAuthError
import com.zamotkin.extrahealthgarmin.garmin.GarminConnectionError
import com.zamotkin.extrahealthgarmin.garmin.GarminTokenStore
import com.zamotkin.extrahealthgarmin.garmin.GarminTokens
import com.zamotkin.extrahealthgarmin.garmin.GarminTooManyRequestsError
import com.zamotkin.extrahealthgarmin.garmin.GarminValidationError
import com.zamotkin.extrahealthgarmin.garmin.JournalPersistence
import com.zamotkin.extrahealthgarmin.garmin.LoginResult
import com.zamotkin.extrahealthgarmin.garmin.MfaClientState
import com.zamotkin.extrahealthgarmin.garmin.TokenCrypto
import com.zamotkin.extrahealthgarmin.garmin.TokenPersistence
import com.zamotkin.extrahealthgarmin.garmin.UploadJournal
import com.zamotkin.extrahealthgarmin.garmin.UploadResult
import com.zamotkin.extrahealthgarmin.garmin.bpJournalKey
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [GarminRepository.uploadBloodPressure] tests — the blood-pressure
 * counterpart of [GarminRepositoryRecordsTest], same fixture style: plain
 * JUnit (Robolectric cannot run on this repo's JDK 26 toolchain, see
 * app/build.gradle), the repository is the real thing built through its
 * internal-collaborators constructor, and the network seam is a counting fake
 * that THROWS unless a test explicitly staged an upload result — every
 * zero-network claim is proven, not assumed.
 *
 * The BP outcome under test is [UploadOutcome.BpUploaded]: unlike the weight
 * flow the caller must know WHICH records were confirmed so it can mark them
 * synced in its own store, hence the confirmed-records payload.
 *
 * Catch-chain coverage for the BP method (too-many-requests / validation /
 * unexpected exception) added by task tefal2garmin-ji0 — previously only the
 * weight path exercised these branches.
 */
class GarminRepositoryBpTest {

    // ------------------------------------------------------------------ //
    //  Fakes (plain JUnit — no mocking frameworks)                        //
    // ------------------------------------------------------------------ //

    /**
     * Zero-config-throwing remote: [uploadBloodPressure] fails the test
     * unless the test staged [bpUploadResult]/[bpUploadException]; the weight
     * path and login/MFA/logout are not under test here and fail on touch.
     *
     * Concurrency hook (task tefal2garmin-n3q): [bpUploadDelayMs] holds the
     * upload in flight (real time — the repository body runs on
     * Dispatchers.IO, virtual time does not apply there), and
     * [recordedUploads] snapshots every batch ever handed over, recorded
     * under a monitor so concurrent callers (the pre-fix race) are counted
     * deterministically.
     */
    private class FakeRemote : GarminRemote {
        // Not under test here (PR #7 weigh-in dedup path) - minimal stubs.
        override suspend fun fetchExistingWeighIns(
            from: LocalDate,
            to: LocalDate,
            tokens: GarminTokens,
        ): ExistingWeighIns = ExistingWeighIns(emptyList(), tokens)

        override suspend fun fetchProfileHeightMeters(tokens: GarminTokens): ProfileHeight =
            ProfileHeight(null, tokens)

        var bpUploadResult: UploadResult? = null
        var bpUploadException: Exception? = null
        var bpUploadDelayMs = 0L

        var bpUploadCalls = 0
            private set
        var lastBpRecords: List<BloodPressureReading>? = null
            private set
        var lastBpTokens: GarminTokens? = null
            private set

        private val uploads = mutableListOf<List<BloodPressureReading>>()

        /** Every upload batch in call order — thread-safe for concurrent tests. */
        fun recordedUploads(): List<List<BloodPressureReading>> =
            synchronized(uploads) { uploads.toList() }

        override suspend fun login(email: String, password: String): LoginResult =
            throw AssertionError("login() is not under test in GarminRepositoryBpTest")

        override suspend fun resumeLogin(clientState: MfaClientState, code: String): GarminTokens =
            throw AssertionError("resumeLogin() is not under test in GarminRepositoryBpTest")

        override suspend fun uploadWeighIns(
            records: List<com.zamotkin.extrahealthgarmin.garmin.GarminWeighInRecord>,
            tokens: GarminTokens
        ): UploadResult =
            throw AssertionError("uploadWeighIns() is not under test in GarminRepositoryBpTest")

        override suspend fun uploadBloodPressure(
            records: List<BloodPressureReading>,
            tokens: GarminTokens
        ): UploadResult {
            synchronized(uploads) {
                uploads += records
                bpUploadCalls++
            }
            lastBpRecords = records
            lastBpTokens = tokens
            if (bpUploadDelayMs > 0) delay(bpUploadDelayMs)
            bpUploadException?.let { throw it }
            return bpUploadResult
                ?: throw AssertionError(
                    "uploadBloodPressure must not be called in this test (zero-network path)"
                )
        }

        override fun logout() =
            throw AssertionError("logout() is not under test in GarminRepositoryBpTest")
    }

    private class PlainTextCrypto : TokenCrypto {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(stored: String): String = stored
    }

    private class InMemoryTokenPersistence : TokenPersistence {
        var stored: String? = null
        var removeCount = 0

        override fun read(): String? = stored

        override fun write(value: String) {
            stored = value
        }

        override fun remove() {
            stored = null
            removeCount++
        }
    }

    private class InMemoryJournalPersistence : JournalPersistence {
        var stored: String? = null
        var savedCount = 0

        override fun load(): String? = stored

        override fun save(json: String) {
            stored = json
            savedCount++
        }
    }

    private val remote = FakeRemote()
    private val tokenPersistence = InMemoryTokenPersistence()
    private val tokenStore = GarminTokenStore(tokenPersistence, PlainTextCrypto())
    private val bpJournalPersistence = InMemoryJournalPersistence()
    private val bpJournal = BpUploadJournal(bpJournalPersistence)
    private val repository = GarminRepository(
        remote,
        tokenStore,
        UploadJournal(InMemoryJournalPersistence()), // weight path not under test
        bpJournal = bpJournal
    )

    // ------------------------------------------------------------------ //
    //  Fixtures                                                           //
    // ------------------------------------------------------------------ //

    /** In-range blood-pressure reading at [epochSecondsUtc] and [userSlot]. */
    private fun reading(epochSecondsUtc: Long, userSlot: Int = 1): BloodPressureReading =
        BloodPressureReading(
            timestampEpochSec = epochSecondsUtc,
            systolic = 128,
            diastolic = 82,
            pulse = 62,
            mov = false,
            ihb = false,
            userSlot = userSlot
        )

    // ------------------------------------------------------------------ //
    //  Zero-network / precondition outcomes                               //
    // ------------------------------------------------------------------ //

    @Test
    fun `uploadBloodPressure_allJournaled_returnsNotNeeded_zeroNetwork`() = runTest {
        val records = listOf(reading(E1), reading(E2, userSlot = 2))
        // No token stored on purpose: NotNeeded must be decided BEFORE the
        // token check, and must touch no network at all.
        bpJournal.markUploaded(records)

        val outcome = repository.uploadBloodPressure(records)

        assertTrue("expected NotNeeded, was $outcome", outcome is UploadOutcome.NotNeeded)
        assertEquals("zero network calls expected (fake throws on touch)", 0, remote.bpUploadCalls)
    }

    @Test
    fun `uploadBloodPressure_noStoredToken_returnsLoginRequired`() = runTest {
        val outcome = repository.uploadBloodPressure(listOf(reading(E1)))

        assertTrue("expected LoginRequired, was $outcome", outcome is UploadOutcome.LoginRequired)
        assertEquals("no upload may happen without tokens", 0, remote.bpUploadCalls)
        assertEquals("nothing journaled", 0, bpJournal.uploadedCount())
    }

    // ------------------------------------------------------------------ //
    //  Success / Partial                                                  //
    // ------------------------------------------------------------------ //

    @Test
    fun `uploadBloodPressure_success_uploads_new_records_and_journals_them`() = runTest {
        tokenStore.save(TOKENS)
        remote.bpUploadResult = UploadResult.Success(2, TOKENS)
        val records = listOf(reading(E1), reading(E1, userSlot = 2))

        val outcome = repository.uploadBloodPressure(records)

        assertTrue("expected BpUploaded, was $outcome", outcome is UploadOutcome.BpUploaded)
        val uploaded = outcome as UploadOutcome.BpUploaded
        assertEquals(
            "confirmed records must be exactly the new ones, in order",
            records,
            uploaded.confirmedRecords
        )
        assertTrue("full success carries no failure messages", uploaded.failureMessages.isEmpty())
        assertEquals(1, remote.bpUploadCalls)
        assertEquals("records passed to the client must be the new ones in order", records, remote.lastBpRecords)
        assertEquals("stored tokens drive the upload", TOKENS, remote.lastBpTokens)
        assertEquals("both confirmed records journaled", 2, bpJournal.uploadedCount())
        records.forEach { record ->
            assertTrue(
                "journal must contain key of $record, was ${bpJournalPersistence.stored}",
                bpJournalPersistence.stored!!.contains("\"${record.bpJournalKey()}\"")
            )
        }
        assertTrue(
            "re-running must find nothing new",
            bpJournal.filterNew(records).isEmpty()
        )
    }

    @Test
    fun `uploadBloodPressure_success_persists_rotated_tokens`() = runTest {
        tokenStore.save(TOKENS)
        val rotated = GarminTokens("jwt-new", "rt-new", TOKENS.diClientId)
        remote.bpUploadResult = UploadResult.Success(1, rotated)

        repository.uploadBloodPressure(listOf(reading(E1)))

        assertEquals("rotated tokens must replace the stored ones", rotated, tokenStore.load())
    }

    @Test
    fun `uploadBloodPressure_partial_journals_only_confirmed_records`() = runTest {
        tokenStore.save(TOKENS)
        val confirmed1 = reading(E1)                     // ts E1, slot 1
        val rejected = reading(E2)                       // ts E2, slot 1
        val confirmed2 = reading(E2, userSlot = 2)       // ts E2, slot 2
        remote.bpUploadResult = UploadResult.Partial(
            succeededKeys = listOf(confirmed1.bpJournalKey(), confirmed2.bpJournalKey()),
            failureMessages = listOf("Row 2 rejected"),
            tokens = TOKENS
        )

        val outcome = repository.uploadBloodPressure(listOf(confirmed1, rejected, confirmed2))

        assertTrue("expected BpUploaded, was $outcome", outcome is UploadOutcome.BpUploaded)
        val uploaded = outcome as UploadOutcome.BpUploaded
        assertEquals("only the provably imported records are confirmed", listOf(confirmed1, confirmed2), uploaded.confirmedRecords)
        assertEquals(listOf("Row 2 rejected"), uploaded.failureMessages)
        assertEquals("only confirmed records journaled", 2, bpJournal.uploadedCount())
        assertFalse(
            "unconfirmed key must NOT be journaled: ${bpJournalPersistence.stored}",
            bpJournalPersistence.stored!!.contains("\"${rejected.bpJournalKey()}\"")
        )

        // The unconfirmed record stays retriable: a second run uploads exactly it.
        remote.bpUploadResult = UploadResult.Success(1, TOKENS)
        val second = repository.uploadBloodPressure(listOf(confirmed1, rejected, confirmed2))

        assertTrue("expected success-shaped BpUploaded on retry, was $second", second is UploadOutcome.BpUploaded)
        assertEquals(
            "retry must carry only the unconfirmed record",
            listOf(rejected),
            (second as UploadOutcome.BpUploaded).confirmedRecords
        )
        assertEquals(3, bpJournal.uploadedCount())
    }

    // ------------------------------------------------------------------ //
    //  Retryable error mapping                                            //
    // ------------------------------------------------------------------ //

    @Test
    fun `uploadBloodPressure_transportError_returnsRetryable_journalsNothing`() = runTest {
        tokenStore.save(TOKENS)
        remote.bpUploadException = GarminConnectionError("Upload failed: retries exhausted")

        val outcome = repository.uploadBloodPressure(listOf(reading(E1), reading(E2)))

        assertTrue("expected Retryable, was $outcome", outcome is UploadOutcome.Retryable)
        val retryable = outcome as UploadOutcome.Retryable
        assertTrue(
            "reason must carry the client's message, was ${retryable.reason}",
            retryable.reason.contains("retries exhausted")
        )
        assertEquals("nothing journaled", 0, bpJournal.uploadedCount())
        assertEquals("journal persistence untouched", 0, bpJournalPersistence.savedCount)
        assertEquals(
            "clients own retries — the orchestrator tries exactly once",
            1,
            remote.bpUploadCalls
        )
    }

    // Task tefal2garmin-ji0: the remaining catch-chain branches, previously
    // covered only for the weight path (GarminRepositoryRecordsTest).

    @Test
    fun `uploadBloodPressure_tooManyRequests_returnsRetryable`() = runTest {
        tokenStore.save(TOKENS)
        remote.bpUploadException = GarminTooManyRequestsError("Upload returned HTTP 429 - rate limited")

        val outcome = repository.uploadBloodPressure(listOf(reading(E1), reading(E2)))

        assertTrue("expected Retryable, was $outcome", outcome is UploadOutcome.Retryable)
        val retryable = outcome as UploadOutcome.Retryable
        assertTrue("reason must mention the rate limit, was ${retryable.reason}", retryable.reason.contains("429"))
        assertEquals("nothing journaled", 0, bpJournal.uploadedCount())
        assertEquals("journal persistence untouched", 0, bpJournalPersistence.savedCount)
        assertEquals(
            "rate limiting is not an auth problem — tokens must survive",
            TOKENS,
            tokenStore.load()
        )
        assertEquals("token persistence must not be emptied", 0, tokenPersistence.removeCount)
    }

    @Test
    fun `uploadBloodPressure_validationError_returnsRetryable`() = runTest {
        tokenStore.save(TOKENS)
        remote.bpUploadException = GarminValidationError("Record 1 rejected: systolic outside plausible range")

        val outcome = repository.uploadBloodPressure(listOf(reading(E1)))

        assertTrue("expected Retryable, was $outcome", outcome is UploadOutcome.Retryable)
        val retryable = outcome as UploadOutcome.Retryable
        assertTrue(
            "reason must carry the client's message, was ${retryable.reason}",
            retryable.reason.contains("plausible range")
        )
        assertEquals("nothing journaled", 0, bpJournal.uploadedCount())
        assertEquals("journal persistence untouched", 0, bpJournalPersistence.savedCount)
        assertEquals(1, remote.bpUploadCalls)
    }

    @Test
    fun `uploadBloodPressure_unexpectedException_returnsRetryable`() = runTest {
        tokenStore.save(TOKENS)
        remote.bpUploadException = RuntimeException("encoder crashed")

        val outcome = repository.uploadBloodPressure(listOf(reading(E1)))

        assertTrue("expected Retryable, was $outcome", outcome is UploadOutcome.Retryable)
        val retryable = outcome as UploadOutcome.Retryable
        assertTrue(
            "unexpected errors are wrapped with the Unexpected error prefix, was ${retryable.reason}",
            retryable.reason.contains("Unexpected error") && retryable.reason.contains("encoder crashed")
        )
        assertEquals("nothing journaled", 0, bpJournal.uploadedCount())
        assertEquals("journal persistence untouched", 0, bpJournalPersistence.savedCount)
        assertEquals(
            "an unexpected crash is not an auth problem — tokens must survive",
            TOKENS,
            tokenStore.load()
        )
        assertEquals("token persistence must not be emptied", 0, tokenPersistence.removeCount)
    }

    // ------------------------------------------------------------------ //
    //  Auth rejection                                                     //
    // ------------------------------------------------------------------ //

    @Test
    fun `uploadBloodPressure_authError_clearsTokens_returnsLoginRequired`() = runTest {
        tokenStore.save(TOKENS)
        remote.bpUploadException = GarminAuthError("Upload rejected: HTTP 401 even after token refresh")

        val outcome = repository.uploadBloodPressure(listOf(reading(E1)))

        assertTrue("expected LoginRequired, was $outcome", outcome is UploadOutcome.LoginRequired)
        assertNull("dead tokens must be cleared", tokenStore.load())
        assertEquals("token persistence must be emptied", 1, tokenPersistence.removeCount)
        assertFalse(repository.hasStoredToken())
        assertEquals("nothing journaled", 0, bpJournal.uploadedCount())
    }

    // ------------------------------------------------------------------ //
    //  Composite-key isolation through the full path                      //
    // ------------------------------------------------------------------ //

    /**
     * Same measurement time for USER1 and USER2 travels as TWO records end
     * to end — the composite journal key must never collapse them.
     */
    @Test
    fun `uploadBloodPressure_same_ts_different_slots_both_uploaded`() = runTest {
        tokenStore.save(TOKENS)
        remote.bpUploadResult = UploadResult.Success(2, TOKENS)
        val slot1 = reading(E1, userSlot = 1)
        val slot2 = reading(E1, userSlot = 2)

        repository.uploadBloodPressure(listOf(slot1, slot2))

        assertEquals(
            "both slots' records must reach the client",
            listOf(slot1, slot2),
            remote.lastBpRecords
        )
        assertEquals(2, bpJournal.uploadedCount())
    }

    // ------------------------------------------------------------------ //
    //  Concurrency: single-flight (task tefal2garmin-n3q)                 //
    // ------------------------------------------------------------------ //

    /**
     * Task tefal2garmin-n3q: two CONCURRENT uploadBloodPressure calls over
     * the same pending batch must not race filterNew -> upload ->
     * markUploaded into uploading the batch twice. The single-flight mutex
     * serializes the whole sequence: the first caller uploads and journals;
     * the second WAITS, then sees the journal already updated and answers
     * NotNeeded with zero network. The in-flight delay is real time (the
     * repository body runs on Dispatchers.IO), so the second caller provably
     * arrives while the first is still uploading — before the fix both
     * callers passed filterNew and the batch hit Garmin twice.
     */
    @Test
    fun `uploadBloodPressure_concurrent_same_batch_uploads_exactly_once`() = runTest {
        tokenStore.save(TOKENS)
        remote.bpUploadResult = UploadResult.Success(2, TOKENS)
        remote.bpUploadDelayMs = 500
        val batch = listOf(reading(E1), reading(E2, userSlot = 2))

        val first = async { repository.uploadBloodPressure(batch) }
        val second = async { repository.uploadBloodPressure(batch) }
        val outcomes = listOf(first.await(), second.await())

        assertEquals(
            "single-flight: the remote upload must execute exactly once",
            1,
            remote.recordedUploads().size
        )
        assertEquals("one caller performs the upload", 1, remote.bpUploadCalls)
        assertEquals(
            "one caller uploads, the other sees the journal already updated",
            1,
            outcomes.count { it is UploadOutcome.BpUploaded }
        )
        assertEquals(
            "the waiting caller answers NotNeeded",
            1,
            outcomes.count { it is UploadOutcome.NotNeeded }
        )
        assertEquals("the winning caller journaled the whole batch", 2, bpJournal.uploadedCount())
        assertTrue(
            "no record may be left re-uploadable after both calls",
            bpJournal.filterNew(batch).isEmpty()
        )
    }

    /**
     * Task tefal2garmin-n3q: overlapping concurrent batches — the second
     * caller waits for the first's journal update, so a record two callers
     * both hold is uploaded by exactly one of them; whichever caller wins
     * the mutex is unspecified, the invariant is not: no composite key may
     * reach the remote twice, and afterwards the journal covers everything.
     */
    @Test
    fun `uploadBloodPressure_concurrent_overlapping_batches_no_record_uploaded_twice`() = runTest {
        tokenStore.save(TOKENS)
        remote.bpUploadResult = UploadResult.Success(3, TOKENS)
        remote.bpUploadDelayMs = 500
        val sharedBatch = listOf(reading(E1), reading(E2))
        val supersetBatch = listOf(reading(E1), reading(E2), reading(E3))

        val narrow = async { repository.uploadBloodPressure(sharedBatch) }
        val wide = async { repository.uploadBloodPressure(supersetBatch) }
        narrow.await()
        wide.await()

        val sentKeys = remote.recordedUploads().flatten().map { it.bpJournalKey() }
        assertEquals(
            "every record must be uploaded (the union of both batches)",
            setOf(reading(E1).bpJournalKey(), reading(E2).bpJournalKey(), reading(E3).bpJournalKey()),
            sentKeys.toSet()
        )
        assertEquals(
            "no composite key may reach the remote twice: $sentKeys",
            sentKeys.size,
            sentKeys.toSet().size
        )
        assertEquals("the journal covers every record of both batches", 3, bpJournal.uploadedCount())
    }

    companion object {
        private val TOKENS = GarminTokens(
            diToken = "jwt-access",
            diRefreshToken = "jwt-refresh",
            diClientId = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"
        )

        // Distinct measurement epochs (arbitrary UTC seconds, one day apart).
        private const val E1 = 1_761_350_400L
        private const val E2 = E1 + 86_400L
        private const val E3 = E2 + 86_400L
    }
}
