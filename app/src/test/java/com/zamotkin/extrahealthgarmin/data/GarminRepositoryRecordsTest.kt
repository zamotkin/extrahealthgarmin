package com.zamotkin.extrahealthgarmin.data

import com.zamotkin.extrahealthgarmin.garmin.BpUploadJournal
import com.zamotkin.extrahealthgarmin.garmin.GarminAuthError
import com.zamotkin.extrahealthgarmin.garmin.GarminConnectionError
import com.zamotkin.extrahealthgarmin.garmin.GarminExistingWeighIn
import com.zamotkin.extrahealthgarmin.garmin.GarminTokenStore
import com.zamotkin.extrahealthgarmin.garmin.GarminTokens
import com.zamotkin.extrahealthgarmin.garmin.GarminTooManyRequestsError
import com.zamotkin.extrahealthgarmin.garmin.GarminValidationError
import com.zamotkin.extrahealthgarmin.garmin.GarminWeighInRecord
import com.zamotkin.extrahealthgarmin.garmin.JournalPersistence
import com.zamotkin.extrahealthgarmin.garmin.MfaClientState
import com.zamotkin.extrahealthgarmin.garmin.LoginResult
import com.zamotkin.extrahealthgarmin.garmin.TokenCrypto
import com.zamotkin.extrahealthgarmin.garmin.TokenPersistence
import com.zamotkin.extrahealthgarmin.garmin.UploadJournal
import com.zamotkin.extrahealthgarmin.garmin.UploadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [GarminRepository.uploadNewRecords] (records-based contract, Strangler
 * phase 1a) tests. Same fixture style as [GarminRepositoryTest]: plain JUnit
 * (Robolectric cannot run on this repo's JDK 26 toolchain, see
 * app/build.gradle), the repository is the real thing built through its
 * internal-collaborators constructor, and the network seam is a counting
 * fake that THROWS unless a test explicitly staged an upload result — every
 * zero-network claim is proven, not assumed.
 *
 * The outcome type under test is [UploadOutcome]: by design it has no
 * residual path and no Fallback variant (un-journaled records simply
 * retry on the next call).
 */
class GarminRepositoryRecordsTest {

    // ------------------------------------------------------------------ //
    //  Fakes (plain JUnit — no mocking frameworks)                        //
    // ------------------------------------------------------------------ //

    /**
     * Zero-config-throwing remote: [uploadWeighIns] fails the test unless the
     * test staged [uploadResult]/[uploadException]; login/MFA/logout are not
     * under test here and fail on touch.
     */
    private class FakeRemote : GarminRemote {
        var uploadResult: UploadResult? = null
        var uploadException: Exception? = null

        /** What Garmin Connect already holds; empty = the pre-existing behavior. */
        var existingWeighIns: List<GarminExistingWeighIn> = emptyList()
        var existingException: Exception? = null
        var existingCalls = 0
            private set
        var lastExistingRange: Pair<LocalDate, LocalDate>? = null
            private set

        /** Garmin profile height; null = no height set (the pre-existing behavior). */
        var profileHeightMeters: Double? = null
        var profileException: Exception? = null
        var profileCalls = 0
            private set

        var uploadCalls = 0
            private set
        var lastUploadRecords: List<GarminWeighInRecord>? = null
            private set
        var lastUploadTokens: GarminTokens? = null
            private set

        /**
         * Optional mid-pass gate (the sign-out race test): when set,
         * [uploadWeighIns] signals [uploadReached] and then suspends until
         * [releaseUpload] completes - the pass is in flight with its tokens
         * already loaded.
         */
        var uploadReached: CompletableDeferred<Unit>? = null
        var releaseUpload: CompletableDeferred<Unit>? = null

        override suspend fun login(email: String, password: String): LoginResult =
            throw AssertionError("login() is not under test in GarminRepositoryRecordsTest")

        override suspend fun resumeLogin(clientState: MfaClientState, code: String): GarminTokens =
            throw AssertionError("resumeLogin() is not under test in GarminRepositoryRecordsTest")

        override suspend fun uploadWeighIns(
            records: List<GarminWeighInRecord>,
            tokens: GarminTokens
        ): UploadResult {
            uploadCalls++
            lastUploadRecords = records
            lastUploadTokens = tokens
            uploadReached?.complete(Unit)
            releaseUpload?.await()
            uploadException?.let { throw it }
            return uploadResult ?: throw AssertionError(
                "uploadWeighIns must not be called in this test (zero-network path)"
            )
        }

        override suspend fun fetchExistingWeighIns(
            from: LocalDate,
            to: LocalDate,
            tokens: GarminTokens
        ): ExistingWeighIns {
            existingCalls++
            lastExistingRange = from to to
            existingException?.let { throw it }
            return ExistingWeighIns(existingWeighIns, tokens)
        }

        override suspend fun fetchProfileHeightMeters(tokens: GarminTokens): ProfileHeight {
            profileCalls++
            profileException?.let { throw it }
            return ProfileHeight(profileHeightMeters, tokens)
        }
        override suspend fun uploadBloodPressure(
            records: List<com.zamotkin.extrahealthgarmin.omron.BloodPressureReading>,
            tokens: GarminTokens
        ): UploadResult =
            throw AssertionError("uploadBloodPressure() is not under test in GarminRepositoryRecordsTest")

        override fun logout() =
            throw AssertionError("logout() is not under test in GarminRepositoryRecordsTest")
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
    private val journalPersistence = InMemoryJournalPersistence()
    private val journal = UploadJournal(journalPersistence)
    private val repository = GarminRepository(
        remote,
        tokenStore,
        journal,
        zone = { ZoneOffset.UTC },
        bpJournal = BpUploadJournal(InMemoryJournalPersistence()) // BP path not under test
    )

    // ------------------------------------------------------------------ //
    //  Fixtures                                                           //
    // ------------------------------------------------------------------ //

    /** In-range weigh-in at [epochSecondsUtc] (range validation must pass). */
    private fun weighIn(epochSecondsUtc: Long, weightKg: Float = 79.65f): GarminWeighInRecord =
        GarminWeighInRecord(epochSecondsUtc, weightKg, percentFat = 21.9f, bmi = 24.6f)

    // ------------------------------------------------------------------ //
    //  Zero-network / precondition outcomes                               //
    // ------------------------------------------------------------------ //

    @Test
    fun `uploadNewRecords_emptyList_returnsNotNeeded_zeroNetwork`() = runTest {
        val outcome = repository.uploadNewRecords(emptyList())

        assertTrue("expected NotNeeded, was $outcome", outcome is UploadOutcome.NotNeeded)
        assertEquals("zero network calls expected (fake throws on touch)", 0, remote.uploadCalls)
    }

    @Test
    fun `uploadNewRecords_noStoredToken_returnsLoginRequired`() = runTest {
        val outcome = repository.uploadNewRecords(listOf(weighIn(E1), weighIn(E2)))

        assertTrue("expected LoginRequired, was $outcome", outcome is UploadOutcome.LoginRequired)
        assertEquals("no upload may happen without tokens", 0, remote.uploadCalls)
        assertEquals("nothing journaled", 0, journal.sentCount())
    }

    @Test
    fun `uploadNewRecords_allFilteredByJournal_returnsNotNeeded`() = runTest {
        val records = listOf(weighIn(E1), weighIn(E2))
        // No token stored on purpose: NotNeeded must be decided BEFORE the
        // token check, and must touch no network at all.
        journal.markSent(records.map { it.journalKey() })

        val outcome = repository.uploadNewRecords(records)

        assertTrue("expected NotNeeded, was $outcome", outcome is UploadOutcome.NotNeeded)
        assertEquals("zero network calls expected (fake throws on touch)", 0, remote.uploadCalls)
    }

    // ------------------------------------------------------------------ //
    //  Success / Partial                                                  //
    // ------------------------------------------------------------------ //

    @Test
    fun `uploadNewRecords_refreshedTokens_persistedToStore`() = runTest {
        // Task 5 fix: GarminUploadClient rotates tokens (refreshIfNeeded/
        // forceRefresh); the repository must persist the tokens the upload
        // actually used, or the next call loads a stale refresh token and
        // eventually forces a re-login.
        tokenStore.save(TOKENS)
        val rotated = GarminTokens("jwt-new", "rt-new", TOKENS.diClientId)
        remote.uploadResult = UploadResult.Success(1, rotated)

        val outcome = repository.uploadNewRecords(listOf(weighIn(E1)))

        assertTrue("expected Success, was $outcome", outcome is UploadOutcome.Success)
        assertEquals("rotated tokens must replace the stored ones", rotated, tokenStore.load())
    }

    @Test
    fun `uploadNewRecords_partial_persistsRefreshedTokensToo`() = runTest {
        tokenStore.save(TOKENS)
        val rotated = GarminTokens("jwt-new", "rt-new", TOKENS.diClientId)
        remote.uploadResult = UploadResult.Partial(
            succeededKeys = emptyList(),
            failureMessages = listOf("Row 1 rejected"),
            tokens = rotated
        )

        repository.uploadNewRecords(listOf(weighIn(E1)))

        assertEquals("partial uploads must also persist rotated tokens", rotated, tokenStore.load())
    }

    @Test
    fun `uploadNewRecords_success_journalsAllKeys`() = runTest {
        tokenStore.save(TOKENS)
        remote.uploadResult = UploadResult.Success(2, TOKENS)
        val records = listOf(weighIn(E1), weighIn(E2))

        val outcome = repository.uploadNewRecords(records)

        assertTrue("expected Success, was $outcome", outcome is UploadOutcome.Success)
        assertEquals(2, (outcome as UploadOutcome.Success).uploadedCount)
        assertEquals(1, remote.uploadCalls)
        assertEquals(
            "records passed to the client must be the new ones in order",
            records,
            remote.lastUploadRecords
        )
        assertEquals("stored tokens drive the upload", TOKENS, remote.lastUploadTokens)
        assertEquals("all confirmed records journaled", 2, journal.sentCount())
        records.forEach { record ->
            assertTrue(
                "journal must contain key of $record, was ${journalPersistence.stored}",
                journalPersistence.stored!!.contains("\"${record.journalKey()}\"")
            )
        }
        assertTrue(
            "re-running must find nothing new (every journalKey journaled)",
            journal.filterNew(records).isEmpty()
        )
    }

    @Test
    fun `uploadNewRecords_partial_journalsOnlySucceededKeys`() = runTest {
        tokenStore.save(TOKENS)
        val confirmed = weighIn(E1)
        val rejected1 = weighIn(E2)
        val rejected2 = weighIn(E3)
        remote.uploadResult = UploadResult.Partial(
            succeededKeys = listOf(confirmed.journalKey()),
            failureMessages = listOf("Row 2 rejected", "Row 3 rejected"),
            tokens = TOKENS
        )

        val outcome = repository.uploadNewRecords(listOf(confirmed, rejected1, rejected2))

        assertTrue("expected Partial, was $outcome", outcome is UploadOutcome.Partial)
        val partial = outcome as UploadOutcome.Partial
        assertEquals("uploadedCount counts only the provably imported records", 1, partial.uploadedCount)
        assertEquals(listOf("Row 2 rejected", "Row 3 rejected"), partial.failureMessages)

        // Only the provably imported record is journaled.
        assertEquals(1, journal.sentCount())
        assertTrue(
            "confirmed key must be journaled",
            journalPersistence.stored!!.contains("\"${confirmed.journalKey()}\"")
        )
        assertFalse(
            "unconfirmed keys must NOT be journaled: ${journalPersistence.stored}",
            journalPersistence.stored!!.contains("\"${rejected1.journalKey()}\"")
        )
        assertFalse(journalPersistence.stored!!.contains("\"${rejected2.journalKey()}\""))

        // The unconfirmed records stay retriable: a second run uploads exactly them.
        remote.uploadResult = UploadResult.Success(2, TOKENS)
        val second = repository.uploadNewRecords(listOf(confirmed, rejected1, rejected2))

        assertTrue("expected Success on retry, was $second", second is UploadOutcome.Success)
        assertEquals(2, (second as UploadOutcome.Success).uploadedCount)
        assertEquals(
            "retry must carry only the two unconfirmed records",
            listOf(rejected1, rejected2),
            remote.lastUploadRecords
        )
        assertEquals(3, journal.sentCount())
    }

    // ------------------------------------------------------------------ //
    //  Retryable error mapping                                            //
    // ------------------------------------------------------------------ //

    @Test
    fun `uploadNewRecords_rateLimited_returnsRetryable_journalsNothing`() = runTest {
        tokenStore.save(TOKENS)
        remote.uploadException = GarminTooManyRequestsError("Upload returned HTTP 429 - rate limited")

        val outcome = repository.uploadNewRecords(listOf(weighIn(E1), weighIn(E2)))

        assertTrue("expected Retryable, was $outcome", outcome is UploadOutcome.Retryable)
        val retryable = outcome as UploadOutcome.Retryable
        assertTrue("reason must mention the rate limit, was ${retryable.reason}", retryable.reason.contains("429"))
        assertEquals("nothing journaled", 0, journal.sentCount())
        assertEquals("journal persistence untouched", 0, journalPersistence.savedCount)
        assertEquals(
            "clients own retries — the orchestrator tries exactly once",
            1,
            remote.uploadCalls
        )
    }

    @Test
    fun `uploadNewRecords_transportError_returnsRetryable_journalsNothing`() = runTest {
        tokenStore.save(TOKENS)
        remote.uploadException = GarminConnectionError("Upload failed: retries exhausted")

        val outcome = repository.uploadNewRecords(listOf(weighIn(E1)))

        assertTrue("expected Retryable, was $outcome", outcome is UploadOutcome.Retryable)
        val retryable = outcome as UploadOutcome.Retryable
        assertTrue("reason must carry the client's message, was ${retryable.reason}", retryable.reason.contains("retries exhausted"))
        assertEquals("nothing journaled", 0, journal.sentCount())
        assertEquals("journal persistence untouched", 0, journalPersistence.savedCount)
        assertEquals(1, remote.uploadCalls)
    }

    @Test
    fun `uploadNewRecords_validationError_returnsRetryable`() = runTest {
        tokenStore.save(TOKENS)

        // (a) Local range violation short-circuits BEFORE any network call.
        val outcome = repository.uploadNewRecords(listOf(weighIn(E1), weighIn(E2, weightKg = 900f)))

        assertTrue("expected Retryable, was $outcome", outcome is UploadOutcome.Retryable)
        assertTrue(
            "reason must name the range problem, was ${(outcome as UploadOutcome.Retryable).reason}",
            outcome.reason.contains("range")
        )
        assertEquals("range check must run BEFORE any network call", 0, remote.uploadCalls)
        assertEquals("nothing journaled", 0, journal.sentCount())

        // (b) The remote re-validates before encoding and throws the same
        // typed error — must map to Retryable too, journaling nothing.
        val remote2 = FakeRemote()
        val journalPersistence2 = InMemoryJournalPersistence()
        val repository2 = GarminRepository(
            remote2,
            GarminTokenStore(InMemoryTokenPersistence(), PlainTextCrypto()).also { it.save(TOKENS) },
            UploadJournal(journalPersistence2),
            bpJournal = BpUploadJournal(InMemoryJournalPersistence()) // BP path not under test
        )
        remote2.uploadException = GarminValidationError("Weight 900.0 kg outside plausible range (0, 500.0]")

        val second = repository2.uploadNewRecords(listOf(weighIn(E1)))

        assertTrue("expected Retryable, was $second", second is UploadOutcome.Retryable)
        assertTrue(
            "reason must carry the client's message",
            (second as UploadOutcome.Retryable).reason.contains("plausible range")
        )
        assertEquals("journal persistence untouched", 0, journalPersistence2.savedCount)
    }

    // ------------------------------------------------------------------ //
    //  Auth rejection                                                     //
    // ------------------------------------------------------------------ //

    @Test
    fun `uploadNewRecords_authError_clearsTokens_returnsLoginRequired`() = runTest {
        tokenStore.save(TOKENS)
        remote.uploadException = GarminAuthError("Upload rejected: HTTP 401 even after token refresh")

        val outcome = repository.uploadNewRecords(listOf(weighIn(E1)))

        assertTrue("expected LoginRequired, was $outcome", outcome is UploadOutcome.LoginRequired)
        assertNull("dead tokens must be cleared", tokenStore.load())
        assertEquals("token persistence must be emptied", 1, tokenPersistence.removeCount)
        assertFalse(repository.hasStoredToken())
        assertEquals("nothing journaled", 0, journal.sentCount())
    }

    // ------------------------------------------------------------------ //
    //  Dedup                                                              //
    // ------------------------------------------------------------------ //

    /**
     * Source records can repeat a measurement: two identical records in one
     * call share the composite journal key, so the batch must be deduped to
     * one upload (see [UploadJournal.filterNew] intra-batch dedup).
     */
    @Test
    fun `uploadNewRecords_intraBatchDedup`() = runTest {
        tokenStore.save(TOKENS)
        remote.uploadResult = UploadResult.Success(1, TOKENS)

        val outcome = repository.uploadNewRecords(listOf(weighIn(E1), weighIn(E1)))

        assertTrue("expected Success, was $outcome", outcome is UploadOutcome.Success)
        assertEquals(1, (outcome as UploadOutcome.Success).uploadedCount)
        assertEquals("upload must be called with exactly one record", 1, remote.lastUploadRecords!!.size)
        assertEquals(1, journal.sentCount())
    }

    companion object {
        private val TOKENS = GarminTokens(
            diToken = "jwt-access",
            diRefreshToken = "jwt-refresh",
            diClientId = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"
        )

        // Distinct measurement epochs (arbitrary UTC seconds, one day apart).
        private const val E1 = 1_761_350_400L // 2025-10-25T00:00:00Z-ish fixture value
        private const val E2 = E1 + 86_400L
        private const val E3 = E2 + 86_400L
    }

    // ------------------------------------------------------------------ //
    //  Server-side dedup against Garmin Connect's weigh-in list           //
    // ------------------------------------------------------------------ //

    /** 2026-10-21T11:46:49Z — a morning weigh-in. */
    private val morning = 1_792_583_209L

    @Test
    fun `uploadNewRecords_sameDaySameWeightInGarmin_skipsUpload_journals`() = runTest {
        tokenStore.save(TOKENS)
        remote.existingWeighIns = listOf(GarminExistingWeighIn(LocalDate.parse("2026-10-21"), 110.95))
        val record = weighIn(morning, weightKg = 110.9f)

        val outcome = repository.uploadNewRecords(listOf(record))

        assertTrue("already in Garmin -> NotNeeded, was $outcome", outcome is UploadOutcome.NotNeeded)
        assertEquals("no upload when Garmin already holds it", 0, remote.uploadCalls)
        assertTrue("journaled so the next pass skips the lookup", journal.filterNew(listOf(record)).isEmpty())
    }

    @Test
    fun `uploadNewRecords_sameDayDifferentWeight_uploads`() = runTest {
        tokenStore.save(TOKENS)
        remote.existingWeighIns = listOf(GarminExistingWeighIn(LocalDate.parse("2026-10-21"), 111.1))
        remote.uploadResult = UploadResult.Success(1, TOKENS)

        val outcome = repository.uploadNewRecords(listOf(weighIn(morning, weightKg = 110.9f)))

        assertEquals(UploadOutcome.Success(1), outcome)
        assertEquals(1, remote.uploadCalls)
    }

    @Test
    fun `uploadNewRecords_sameWeightOtherDay_uploads`() = runTest {
        tokenStore.save(TOKENS)
        remote.existingWeighIns = listOf(GarminExistingWeighIn(LocalDate.parse("2026-10-20"), 110.9))
        remote.uploadResult = UploadResult.Success(1, TOKENS)

        val outcome = repository.uploadNewRecords(listOf(weighIn(morning, weightKg = 110.9f)))

        assertEquals(UploadOutcome.Success(1), outcome)
    }

    @Test
    fun `uploadNewRecords_mixedBatch_uploadsOnlyMissing_journalsBoth`() = runTest {
        tokenStore.save(TOKENS)
        val present = weighIn(morning, weightKg = 110.9f)
        val missing = weighIn(morning + 86_400, weightKg = 110.4f)
        remote.existingWeighIns = listOf(GarminExistingWeighIn(LocalDate.parse("2026-10-21"), 110.9))
        remote.uploadResult = UploadResult.Success(1, TOKENS)

        val outcome = repository.uploadNewRecords(listOf(present, missing))

        assertEquals(UploadOutcome.Success(1), outcome)
        assertEquals(listOf(missing), remote.lastUploadRecords)
        assertEquals(2, journal.sentCount())
        assertEquals(
            "lookup covers the batch's days with a 1-day margin",
            LocalDate.parse("2026-10-20") to LocalDate.parse("2026-10-23"),
            remote.lastExistingRange
        )
    }

    @Test
    fun `uploadNewRecords_matchesOnLocalDay_notUtcDay`() = runTest {
        tokenStore.save(TOKENS)
        // 2026-10-21T03:00Z is still 2026-10-20 evening in Denver.
        val denver = GarminRepository(remote, tokenStore, journal, zone = { ZoneId.of("America/Denver") }, bpJournal = BpUploadJournal(InMemoryJournalPersistence()))
        remote.existingWeighIns = listOf(GarminExistingWeighIn(LocalDate.parse("2026-10-20"), 110.9))

        val outcome = denver.uploadNewRecords(listOf(weighIn(1_792_551_600L, weightKg = 110.9f)))

        assertTrue("Garmin's calendarDate is local, was $outcome", outcome is UploadOutcome.NotNeeded)
        assertEquals(0, remote.uploadCalls)
    }

    @Test
    fun `uploadNewRecords_lookupRateLimited_uploadsNothing_returnsRetryable`() = runTest {
        tokenStore.save(TOKENS)
        remote.existingException = GarminTooManyRequestsError("429")

        val outcome = repository.uploadNewRecords(listOf(weighIn(morning)))

        assertTrue("a failed lookup must not risk a duplicate, was $outcome", outcome is UploadOutcome.Retryable)
        assertEquals(0, remote.uploadCalls)
        assertEquals("nothing journaled", 0, journal.sentCount())
    }

    @Test
    fun `uploadNewRecords_lookupAuthError_clearsTokens_returnsLoginRequired`() = runTest {
        tokenStore.save(TOKENS)
        remote.existingException = GarminAuthError("401")

        val outcome = repository.uploadNewRecords(listOf(weighIn(morning)))

        assertEquals(UploadOutcome.LoginRequired, outcome)
        assertNull(tokenStore.load())
        assertEquals(0, remote.uploadCalls)
    }

    // ------------------------------------------------------------------ //
    //  BMI from the Garmin profile height                                 //
    // ------------------------------------------------------------------ //

    private fun bare(epochSecondsUtc: Long, weightKg: Float = 110.0f) =
        GarminWeighInRecord(epochSecondsUtc, weightKg, percentFat = null, bmi = null)

    @Test
    fun `uploadNewRecords_missingBmi_filledFromProfileHeight`() = runTest {
        tokenStore.save(TOKENS)
        remote.profileHeightMeters = 2.0
        remote.uploadResult = UploadResult.Success(1, TOKENS)

        repository.uploadNewRecords(listOf(bare(morning, weightKg = 110.0f)))

        assertEquals(27.5f, remote.lastUploadRecords!!.single().bmi!!, 0.001f)
    }

    @Test
    fun `uploadNewRecords_existingBmi_keptAndNoProfileRead`() = runTest {
        tokenStore.save(TOKENS)
        remote.profileHeightMeters = 2.0
        remote.uploadResult = UploadResult.Success(1, TOKENS)

        repository.uploadNewRecords(listOf(weighIn(morning)))

        assertEquals("Health Connect height wins", 24.6f, remote.lastUploadRecords!!.single().bmi!!, 0.001f)
        assertEquals("no profile request when every record has BMI", 0, remote.profileCalls)
    }

    @Test
    fun `uploadNewRecords_profileReadFails_uploadsWithoutBmi`() = runTest {
        tokenStore.save(TOKENS)
        remote.profileException = GarminConnectionError("timeout")
        remote.uploadResult = UploadResult.Success(1, TOKENS)

        val outcome = repository.uploadNewRecords(listOf(bare(morning)))

        assertEquals(UploadOutcome.Success(1), outcome)
        assertNull(remote.lastUploadRecords!!.single().bmi)
    }

    @Test
    fun `uploadNewRecords_noProfileHeight_uploadsWithoutBmi`() = runTest {
        tokenStore.save(TOKENS)
        remote.uploadResult = UploadResult.Success(1, TOKENS)

        repository.uploadNewRecords(listOf(bare(morning)))

        assertEquals(1, remote.profileCalls)
        assertNull(remote.lastUploadRecords!!.single().bmi)
    }

    @Test
    fun `signOut during an in-flight upload does not resurrect the session`() = runTest {
        // The xlg.7 review race (fixed in task xlg.8): a pass that loaded
        // its tokens before the user signed out finishes and persists its
        // rotated tokens - which would flip hasStoredToken() true again on
        // the next start, silently re-signing the user in. The late save
        // must be dropped; the pass's own outcome and journaling are not.
        tokenStore.save(TOKENS)
        val rotated = GarminTokens("jwt-late", "rt-late", TOKENS.diClientId)
        remote.uploadResult = UploadResult.Success(1, rotated)
        val uploadReached = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        remote.uploadReached = uploadReached
        remote.releaseUpload = releaseUpload

        var outcome: UploadOutcome? = null
        val pass = launch { outcome = repository.uploadNewRecords(listOf(weighIn(E1))) }
        uploadReached.await() // the pass is mid-upload, its tokens loaded
        repository.signOut()
        assertFalse("sign-out clears the stored session", repository.hasStoredToken())

        releaseUpload.complete(Unit) // the pass finishes with rotated tokens
        pass.join()

        assertTrue("the pass itself still succeeds", outcome is UploadOutcome.Success)
        assertNull("the late token save must not resurrect the session", tokenStore.load())
        assertFalse(repository.hasStoredToken())
        assertEquals("the upload's journaling is untouched", 1, journal.sentCount())
    }
}
