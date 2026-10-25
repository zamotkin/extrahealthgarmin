package com.zamotkin.extrahealthgarmin.data

import com.zamotkin.extrahealthgarmin.garmin.BpUploadJournal
import com.zamotkin.extrahealthgarmin.garmin.GarminAuthError
import com.zamotkin.extrahealthgarmin.garmin.GarminConnectionError
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * [GarminRepository] login orchestration tests. Robolectric cannot run on
 * this repo's JDK 26 toolchain (see app/build.gradle), so the network seam
 * is a counting [FakeRemote] and the token store / journal ride their own
 * in-memory persistence fakes — the repository itself is the real thing,
 * constructed through its internal collaborators constructor.
 *
 * Upload-outcome semantics live in [GarminRepositoryRecordsTest] (records
 * API); this class pins the login/MFA state machine only.
 */
class GarminRepositoryTest {

    // ------------------------------------------------------------------ //
    //  Fakes (plain JUnit — no mocking frameworks)                        //
    // ------------------------------------------------------------------ //

    private class FakeRemote : GarminRemote {
        var loginResult: LoginResult = LoginResult.Success(TOKENS)
        var loginException: Exception? = null
        var resumeTokens: GarminTokens = TOKENS
        var resumeException: Exception? = null
        var uploadResult: UploadResult = UploadResult.Success(0, TOKENS)
        var uploadException: Exception? = null

        var loginCalls = 0
            private set
        var resumeCalls = 0
            private set
        var uploadCalls = 0
            private set
        var lastResumeState: MfaClientState? = null
            private set
        var lastResumeCode: String? = null
            private set
        var lastUploadRecords: List<GarminWeighInRecord>? = null
            private set
        var lastUploadTokens: GarminTokens? = null
            private set
        var logoutCalls = 0
            private set

        override fun logout() {
            logoutCalls++
        }

        override suspend fun login(email: String, password: String): LoginResult {
            loginCalls++
            loginException?.let { throw it }
            return loginResult
        }

        override suspend fun resumeLogin(clientState: MfaClientState, code: String): GarminTokens {
            resumeCalls++
            lastResumeState = clientState
            lastResumeCode = code
            resumeException?.let { throw it }
            return resumeTokens
        }

        override suspend fun uploadWeighIns(
            records: List<GarminWeighInRecord>,
            tokens: GarminTokens
        ): UploadResult {
            uploadCalls++
            lastUploadRecords = records
            lastUploadTokens = tokens
            uploadException?.let { throw it }
            return uploadResult
        }

        override suspend fun fetchExistingWeighIns(
            from: LocalDate,
            to: LocalDate,
            tokens: GarminTokens
        ): ExistingWeighIns = ExistingWeighIns(emptyList(), tokens)

        override suspend fun fetchProfileHeightMeters(tokens: GarminTokens): ProfileHeight =
            ProfileHeight(null, tokens)
        override suspend fun uploadBloodPressure(
            records: List<com.zamotkin.extrahealthgarmin.omron.BloodPressureReading>,
            tokens: GarminTokens
        ): UploadResult =
            throw AssertionError("uploadBloodPressure() is not under test in GarminRepositoryTest")
    }

    /**
     * STATEFUL fake mirroring GarminAuthClient's `pendingMfa` gate 1:1: a
     * second [login] while an MFA flow is pending THROWS, [resumeLogin]
     * without a pending flow THROWS, a successful resume clears the gate and
     * [logout] clears it. Every login starts a NEW MFA flow with a distinct
     * client state (`mfaMethod` = EMAIL-1, EMAIL-2, ...), so tests can tell
     * which pending flow a resume consumed.
     *
     * The stateless [FakeRemote] is exactly why the MFA deadlock of task
     * garmin-api-import-j5e.6 shipped: it happily answered a
     * second login() after NeedsMfa, which the real client rejects. Every
     * MFA-path test must run against this fake.
     */
    private class StatefulFakeRemote : GarminRemote {
        private var pendingMfa: MfaClientState? = null
        private var mfaFlowsStarted = 0

        var resumeTokens: GarminTokens = TOKENS
        var loginCalls = 0
            private set
        var resumeCalls = 0
            private set
        var logoutCalls = 0
            private set
        var uploadCalls = 0
            private set
        var lastResumeState: MfaClientState? = null
            private set
        var lastResumeCode: String? = null
            private set

        override suspend fun login(email: String, password: String): LoginResult {
            // Same gate as GarminAuthClient.login (GarminAuthClient.kt:93-97).
            if (pendingMfa != null) {
                throw GarminAuthError(
                    "MFA login already in progress; complete it with resumeLogin() or call logout() first"
                )
            }
            loginCalls++
            val state = MfaClientState(mfaMethod = "EMAIL-${++mfaFlowsStarted}")
            pendingMfa = state
            return LoginResult.NeedsMfa(state)
        }

        override suspend fun resumeLogin(clientState: MfaClientState, code: String): GarminTokens {
            if (pendingMfa == null) {
                throw GarminAuthError("No pending MFA login; start one with login() first")
            }
            resumeCalls++
            lastResumeState = clientState
            lastResumeCode = code
            pendingMfa = null
            return resumeTokens
        }

        override suspend fun uploadWeighIns(
            records: List<GarminWeighInRecord>,
            tokens: GarminTokens
        ): UploadResult {
            uploadCalls++
            return UploadResult.Success(records.size, TOKENS)
        }

        override suspend fun fetchExistingWeighIns(
            from: LocalDate,
            to: LocalDate,
            tokens: GarminTokens
        ): ExistingWeighIns = ExistingWeighIns(emptyList(), tokens)

        override suspend fun fetchProfileHeightMeters(tokens: GarminTokens): ProfileHeight =
            ProfileHeight(null, tokens)
        override suspend fun uploadBloodPressure(
            records: List<com.zamotkin.extrahealthgarmin.omron.BloodPressureReading>,
            tokens: GarminTokens
        ): UploadResult =
            throw AssertionError("uploadBloodPressure() is not under test in GarminRepositoryTest")

        override fun logout() {
            logoutCalls++
            pendingMfa = null
        }
    }

    private class PlainTextCrypto : TokenCrypto {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(stored: String): String = stored
    }

    private class InMemoryTokenPersistence : TokenPersistence {
        var stored: String? = null
        var writeCount = 0
        var removeCount = 0

        override fun read(): String? = stored
        override fun write(value: String) {
            stored = value
            writeCount++
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
        bpJournal = BpUploadJournal(InMemoryJournalPersistence()) // BP path not under test
    )

    // ------------------------------------------------------------------ //
    //  Login                                                              //
    // ------------------------------------------------------------------ //

    @Test
    fun `login_success_stores_tokens`() = runTest {
        remote.loginResult = LoginResult.Success(TOKENS)

        val status = repository.login("dmitry@example.com", "s3cret")

        assertTrue("expected Success, was $status", status is LoginStatus.Success)
        assertEquals(1, remote.loginCalls)
        assertEquals("tokens must be persisted on success", TOKENS, tokenStore.load())
        assertTrue(repository.hasStoredToken())
    }

    @Test
    fun `login_needs_mfa_then_resume_with_code`() = runTest {
        val mfaState = MfaClientState(mfaMethod = "EMAIL")
        remote.loginResult = LoginResult.NeedsMfa(mfaState)

        // Without a code the flow stops at NeedsMfa and stores nothing.
        val first = repository.login("dmitry@example.com", "s3cret")
        assertTrue("expected NeedsMfa, was $first", first is LoginStatus.NeedsMfa)
        assertNull("no tokens before MFA completion", tokenStore.load())
        assertEquals(0, remote.resumeCalls)

        // With a code the same login call resumes the pending MFA flow.
        val second = repository.login("dmitry@example.com", "s3cret", "123456")
        assertTrue("expected Success after MFA, was $second", second is LoginStatus.Success)
        assertEquals(1, remote.resumeCalls)
        assertEquals(mfaState, remote.lastResumeState)
        assertEquals("123456", remote.lastResumeCode)
        assertEquals(TOKENS, tokenStore.load())
    }

    /**
     * Pins the CRITICAL MFA deadlock (task .6 finding 1) against a fake that
     * models the real client's pendingMfa gate: after login() returned
     * NeedsMfa, entering the code must complete via resumeLogin on the STORED
     * client state — never by calling login() again (the real client throws
     * "MFA login already in progress" there, which deadlocked the dialog).
     */
    @Test
    fun `stateful_fake_mfa_code_entry_completes_via_resume`() = runTest {
        val statefulRemote = StatefulFakeRemote()
        val repository = GarminRepository(
            statefulRemote,
            GarminTokenStore(InMemoryTokenPersistence(), PlainTextCrypto()),
            UploadJournal(InMemoryJournalPersistence()),
            bpJournal = BpUploadJournal(InMemoryJournalPersistence()) // BP path not under test
        )

        val first = repository.login("dmitry@example.com", "s3cret")
        assertTrue("expected NeedsMfa, was $first", first is LoginStatus.NeedsMfa)
        assertEquals(1, statefulRemote.loginCalls)
        assertEquals(0, statefulRemote.resumeCalls)

        val second = repository.login("dmitry@example.com", "s3cret", "123456")

        assertTrue("code entry after MFA_REQUIRED must complete via resume, was $second", second is LoginStatus.Success)
        assertEquals("code entry must NOT restart login() (the real client gates it)", 1, statefulRemote.loginCalls)
        assertEquals(1, statefulRemote.resumeCalls)
        assertEquals("the STORED client state (EMAIL-1) must be resumed", "EMAIL-1", statefulRemote.lastResumeState?.mfaMethod)
        assertEquals("123456", statefulRemote.lastResumeCode)
        assertTrue("tokens must be persisted after the MFA resume", repository.hasStoredToken())
    }

    /**
     * Resend-code path: with a pending MFA flow the repository must clear the
     * client gate via logout() before re-running login(), and the code typed
     * after the resend must resume the NEW flow's client state.
     */
    @Test
    fun `resend_code_relogin_after_logout_completes`() = runTest {
        val statefulRemote = StatefulFakeRemote()
        val repository = GarminRepository(
            statefulRemote,
            GarminTokenStore(InMemoryTokenPersistence(), PlainTextCrypto()),
            UploadJournal(InMemoryJournalPersistence()),
            bpJournal = BpUploadJournal(InMemoryJournalPersistence()) // BP path not under test
        )

        repository.login("dmitry@example.com", "s3cret")

        // Resend = login again with the same credentials and no code.
        val resend = repository.login("dmitry@example.com", "s3cret")

        assertTrue("resend must restart the MFA flow, was $resend", resend is LoginStatus.NeedsMfa)
        assertEquals("resend must clear the client's pendingMfa gate via logout()", 1, statefulRemote.logoutCalls)
        assertEquals("resend must run a fresh login() after the logout", 2, statefulRemote.loginCalls)

        val done = repository.login("dmitry@example.com", "s3cret", "654321")

        assertTrue("code entry after resend must complete, was $done", done is LoginStatus.Success)
        assertEquals(1, statefulRemote.resumeCalls)
        assertEquals(
            "the SECOND (post-resend) client state must be resumed, was ${statefulRemote.lastResumeState}",
            "EMAIL-2",
            statefulRemote.lastResumeState?.mfaMethod
        )
        assertTrue(repository.hasStoredToken())
    }

    @Test
    fun `login_wrong_password_returns_failed`() = runTest {
        remote.loginException = GarminAuthError("Invalid username or password")

        val status = repository.login("dmitry@example.com", "wrong")

        assertTrue("expected Failed, was $status", status is LoginStatus.Failed)
        assertEquals(
            "reason must carry the client's message",
            "Invalid username or password",
            (status as LoginStatus.Failed).reason
        )
        assertNull("failed login must not store tokens", tokenStore.load())
        assertFalse(repository.hasStoredToken())
        assertEquals(0, remote.resumeCalls)
    }

    @Test
    fun `login_transport_error_returns_failed_not_throw`() = runTest {
        remote.loginException = GarminConnectionError("Connection reset by peer")

        val status = try {
            repository.login("dmitry@example.com", "s3cret")
        } catch (e: Exception) {
            fail("login must never throw across the interface, got $e")
        }

        assertTrue("expected Failed, was $status", status is LoginStatus.Failed)
        assertEquals("Connection reset by peer", (status as LoginStatus.Failed).reason)
    }

    companion object {
        private val TOKENS = GarminTokens(
            diToken = "jwt-access",
            diRefreshToken = "jwt-refresh",
            diClientId = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"
        )
    }
}
