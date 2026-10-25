package com.zamotkin.extrahealthgarmin.ui

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.data.GarminUploadOrchestrator
import com.zamotkin.extrahealthgarmin.data.LoginStatus
import com.zamotkin.extrahealthgarmin.data.UploadOutcome
import com.zamotkin.extrahealthgarmin.garmin.GarminWeighInRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock

/**
 * Pins the MainViewModel upload state machine against the
 * GarminUploadOrchestrator records contract (Strangler phase 1b): which
 * outcome maps to which UI state, that no file is ever written anywhere, and
 * the login dialog flow (retry-after-login with the exact pending payload,
 * MFA resume, resend, cancel-without-upload, inline errors).
 *
 * No Robolectric (broken on the current JDK — see build.gradle testOptions):
 * Dispatchers.Main is swapped for a StandardTestDispatcher and LiveData
 * values are read directly under InstantTaskExecutorRule.
 *
 * The orchestrator is a Mockito mock of the interface whose default Answer
 * records every call and returns the current [loginStatus]/[uploadOutcome]/
 * [storedToken] fields. Plain when()/verify() stubbing of suspend functions
 * needs a matcher for the hidden Continuation parameter (mockito-kotlin is
 * not a dependency of this project); the recording answer sidesteps that
 * entirely and doubles as the call log the assertions verify against.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelUploadTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: MainViewModel

    // Orchestrator mock state: what each method returns next
    private var loginStatus: LoginStatus = LoginStatus.Success
    private var uploadOutcome: UploadOutcome = UploadOutcome.NotNeeded
    private var storedToken: Boolean = false

    /** When set, uploadNewRecords throws instead of returning [uploadOutcome]. */
    private var uploadException: Exception? = null

    // Orchestrator call log: (email, password, mfaCode) triples, the
    // record batches handed to uploadNewRecords and the sign-out calls
    private val loginCalls = mutableListOf<Triple<String, String, String?>>()
    private val uploadCalls = mutableListOf<List<GarminWeighInRecord>>()
    private val signOutCalls = mutableListOf<Int>()

    /** Two distinct in-range weigh-ins — the canonical pending payload. */
    private val twoRecords = listOf(
        GarminWeighInRecord(epochSecondsUtc = 1_761_350_400L, weightKg = 79.65f, percentFat = 21.9f, bmi = 24.6f),
        GarminWeighInRecord(epochSecondsUtc = 1_761_436_800L, weightKg = 80.25f, percentFat = 21.7f, bmi = 24.8f)
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = MainViewModel(buildOrchestrator(), Application())
    }

    /** The recording orchestrator mock — also the call log under assertion. */
    private fun buildOrchestrator(): GarminUploadOrchestrator =
        mock(GarminUploadOrchestrator::class.java) { invocation ->
            when (invocation.method.name) {
                "login" -> {
                    loginCalls.add(
                        Triple(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2)
                        )
                    )
                    loginStatus
                }
                "uploadNewRecords" -> {
                    @Suppress("UNCHECKED_CAST")
                    uploadCalls.add(invocation.getArgument(0) as List<GarminWeighInRecord>)
                    val exception = uploadException
                    if (exception != null) throw exception
                    uploadOutcome
                }
                "hasStoredToken" -> storedToken
                "signOut" -> {
                    signOutCalls.add(1)
                    null
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Starts an upload of [twoRecords] and waits until the state machine
     * reaches [status].
     */
    private fun uploadAndAwait(status: GarminUploadStatus): MainUiState {
        viewModel.startGarminUpload(twoRecords)
        pumpUntil { it.uploadStatus == status }
        return viewModel.uiState.value!!
    }

    /**
     * Advances the test scheduler until [predicate] holds on the current
     * state; a poll loop with a generous deadline keeps this robust against
     * scheduling orderings.
     */
    private fun pumpUntil(timeoutMs: Long = 10_000, predicate: (MainUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = viewModel.uiState.value
            if (state != null && predicate(state)) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for the upload state machine; last state: ${viewModel.uiState.value}")
    }

    // ------------------------------------------------------------------ //
    //  Outcome → UI state mapping                                         //
    // ------------------------------------------------------------------ //

    @Test
    fun upload_success_reports_count_without_dialog() {
        uploadOutcome = UploadOutcome.Success(uploadedCount = 2)

        val state = uploadAndAwait(GarminUploadStatus.SUCCESS)

        assertEquals(
            "the pending records must be uploaded exactly once",
            listOf(twoRecords),
            uploadCalls
        )
        assertEquals(2, state.uploadedCount)
        assertEquals(2, state.totalRecords)
        assertFalse(state.showLoginDialog)
    }

    @Test
    fun not_needed_maps_to_status() {
        uploadOutcome = UploadOutcome.NotNeeded

        val state = uploadAndAwait(GarminUploadStatus.NOT_NEEDED)

        assertEquals(0, state.uploadedCount)
        assertTrue(state.uploadMessages.isEmpty())
        assertFalse(state.showLoginDialog)
    }

    @Test
    fun partial_reports_counts_messages_and_total() {
        uploadOutcome = UploadOutcome.Partial(
            uploadedCount = 1,
            failureMessages = listOf("row 2 rejected by Garmin")
        )

        val state = uploadAndAwait(GarminUploadStatus.PARTIAL)

        assertEquals(1, state.uploadedCount)
        assertEquals(
            "total must stay the size of the batch that was submitted",
            2,
            state.totalRecords
        )
        assertEquals(listOf("row 2 rejected by Garmin"), state.uploadMessages)
    }

    @Test
    fun retryable_maps_to_status_with_reason() {
        uploadOutcome = UploadOutcome.Retryable("Upload returned HTTP 429 - rate limited")

        val state = uploadAndAwait(GarminUploadStatus.RETRYABLE)

        assertEquals(
            listOf("Upload returned HTTP 429 - rate limited"),
            state.uploadMessages
        )
        assertEquals(0, state.uploadedCount)
        assertFalse(state.showLoginDialog)
    }

    @Test
    fun login_required_opens_dialog_and_keeps_records_pending() {
        uploadOutcome = UploadOutcome.LoginRequired

        val state = uploadAndAwait(GarminUploadStatus.LOGIN_REQUIRED)

        assertTrue("LoginRequired must open the login dialog", state.showLoginDialog)
        assertEquals(1, uploadCalls.size)
    }

    /**
     * The orchestrator throwing across uploadNewRecords (it never should, but
     * defensively) must land on the retryable status — nothing is written
     * anywhere and nothing crashes.
     */
    @Test
    fun orchestrator_exception_maps_to_retryable() {
        uploadException = RuntimeException("upload pipeline exploded")

        val state = uploadAndAwait(GarminUploadStatus.RETRYABLE)

        assertEquals(1, uploadCalls.size)
        assertEquals(listOf("upload pipeline exploded"), state.uploadMessages)
    }

    // ------------------------------------------------------------------ //
    //  Login flow                                                         //
    // ------------------------------------------------------------------ //

    @Test
    fun login_success_retries_pending_records_exactly() {
        uploadOutcome = UploadOutcome.LoginRequired
        viewModel.startGarminUpload(twoRecords)
        pumpUntil { it.showLoginDialog }

        loginStatus = LoginStatus.Success
        uploadOutcome = UploadOutcome.Success(uploadedCount = 2)
        viewModel.loginToGarmin("user@example.com", "hunter2")
        pumpUntil { it.uploadStatus == GarminUploadStatus.SUCCESS }

        val state = viewModel.uiState.value!!
        assertTrue("login success must flip the connected flag", state.garminConnected)
        assertFalse("login success must close the dialog", state.showLoginDialog)
        assertEquals(
            Triple("user@example.com", "hunter2", null),
            loginCalls.single()
        )
        assertEquals(
            "login success must retry the upload with the SAME records",
            2,
            uploadCalls.size
        )
        assertEquals("retry payload must be identical", uploadCalls[0], uploadCalls[1])
    }

    /**
     * There is no data source yet: a plain
     * Connect-Garmin login with nothing pending must only report connected —
     * no upload attempt at all.
     */
    @Test
    fun login_success_noPendingRecords_showsConnected_noUpload() {
        loginStatus = LoginStatus.Success

        viewModel.loginToGarmin("user@example.com", "hunter2")
        pumpUntil { it.garminConnected }

        val state = viewModel.uiState.value!!
        assertTrue(state.garminConnected)
        assertFalse(state.showLoginDialog)
        assertNull(state.loginError)
        assertEquals("no upload may be attempted with nothing pending", 0, uploadCalls.size)
        assertNull("no upload status may appear", state.uploadStatus)
    }

    @Test
    fun needs_mfa_flow_resumes_with_code() {
        uploadOutcome = UploadOutcome.LoginRequired
        viewModel.startGarminUpload(twoRecords)
        pumpUntil { it.showLoginDialog }

        loginStatus = LoginStatus.NeedsMfa
        viewModel.loginToGarmin("user@example.com", "hunter2")
        pumpUntil { it.loginNeedsMfa }

        val mfaState = viewModel.uiState.value!!
        assertTrue("MFA prompt must stay inside the dialog", mfaState.showLoginDialog)

        loginStatus = LoginStatus.Success
        uploadOutcome = UploadOutcome.Success(uploadedCount = 2)
        viewModel.loginToGarmin("user@example.com", "hunter2", "123456")
        pumpUntil { it.uploadStatus == GarminUploadStatus.SUCCESS }

        val state = viewModel.uiState.value!!
        assertFalse(state.showLoginDialog)
        assertFalse(state.loginNeedsMfa)
        assertNull(state.loginError)
        assertEquals(
            Triple("user@example.com", "hunter2", "123456"),
            loginCalls.last()
        )
        assertEquals("MFA resume must retry the upload", 2, uploadCalls.size)
    }

    @Test
    fun resend_code_calls_login_again() {
        uploadOutcome = UploadOutcome.LoginRequired
        viewModel.startGarminUpload(twoRecords)
        pumpUntil { it.showLoginDialog }

        loginStatus = LoginStatus.NeedsMfa
        viewModel.loginToGarmin("user@example.com", "hunter2")
        pumpUntil { it.loginNeedsMfa }

        // Resend = re-run login with the same credentials and no code
        viewModel.loginToGarmin("user@example.com", "hunter2", null)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "resend must re-call login with the same credentials and no code",
            listOf(
                Triple("user@example.com", "hunter2", null),
                Triple("user@example.com", "hunter2", null)
            ),
            loginCalls
        )
        val state = viewModel.uiState.value!!
        assertTrue("dialog must stay open across resend", state.showLoginDialog)
        assertTrue(state.loginNeedsMfa)
        // No upload retry may happen while still signed out
        assertEquals(1, uploadCalls.size)
    }

    @Test
    fun login_cancel_after_login_required_closes_dialog_without_retry() {
        uploadOutcome = UploadOutcome.LoginRequired
        viewModel.startGarminUpload(twoRecords)
        pumpUntil { it.showLoginDialog }

        viewModel.onLoginCancelled()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value!!
        assertFalse("cancel must close the dialog", state.showLoginDialog)
        assertFalse(state.loginNeedsMfa)
        assertNull(state.loginError)
        assertEquals(
            "no upload retry may happen after cancel",
            1,
            uploadCalls.size
        )
    }

    /**
     * Pins the login-cancel race: cancelling while the login coroutine is
     * still suspended must kill its Job, so a late Success can neither flip
     * state nor run the upload retry.
     */
    @Test
    fun login_cancel_cancels_inflight_job_and_skips_upload() {
        uploadOutcome = UploadOutcome.LoginRequired
        viewModel.startGarminUpload(twoRecords)
        pumpUntil { it.showLoginDialog }

        loginStatus = LoginStatus.Success
        uploadOutcome = UploadOutcome.Success(uploadedCount = 2)
        // Launch the login but do NOT advance the scheduler first: the login
        // coroutine is created-and-suspended — exactly the window a fast
        // dialog cancel hits.
        viewModel.loginToGarmin("user@example.com", "hunter2")
        viewModel.onLoginCancelled()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "cancel must kill the login job before it reaches the orchestrator",
            0,
            loginCalls.size
        )
        assertEquals(
            "no upload retry may happen after cancel",
            1,
            uploadCalls.size
        )
        val state = viewModel.uiState.value!!
        assertFalse("late Success must not flip the connected flag", state.garminConnected)
        assertFalse("late Success must not reopen the dialog", state.showLoginDialog)
        assertNull("late Success must not surface a login error", state.loginError)
        assertEquals(
            "the upload status from the failed attempt must stand",
            GarminUploadStatus.LOGIN_REQUIRED,
            state.uploadStatus
        )
    }

    @Test
    fun login_failed_shows_error_stays_in_dialog() {
        uploadOutcome = UploadOutcome.LoginRequired
        viewModel.startGarminUpload(twoRecords)
        pumpUntil { it.showLoginDialog }

        loginStatus = LoginStatus.Failed(reason = "bad credentials")
        viewModel.loginToGarmin("user@example.com", "wrong-password")
        pumpUntil { it.loginError != null }

        val state = viewModel.uiState.value!!
        assertEquals("bad credentials", state.loginError)
        assertTrue("Failed must keep the dialog open", state.showLoginDialog)
        assertFalse(state.loginNeedsMfa)
        // A failed login must not trigger an upload retry
        assertEquals(1, uploadCalls.size)
    }

    // ------------------------------------------------------------------ //
    //  Initial state                                                      //
    // ------------------------------------------------------------------ //

    @Test
    fun connect_visibility_follows_token_presence() {
        assertFalse(
            "no stored token -> Connect Garmin entry point visible",
            viewModel.uiState.value!!.garminConnected
        )

        storedToken = true
        val connectedViewModel = MainViewModel(buildOrchestrator(), Application())
        assertTrue(
            "stored token -> connected status instead of the button",
            connectedViewModel.uiState.value!!.garminConnected
        )
    }

    // ------------------------------------------------------------------ //
    //  Account entries relocated to Settings (task xlg.7)                 //
    // ------------------------------------------------------------------ //

    @Test
    fun requestGarminLogin_opens_the_shared_login_dialog() {
        // The Settings Garmin group's sign-in entry: only surfaces the
        // dialog, never runs a login by itself.
        viewModel.requestGarminLogin()

        assertTrue(viewModel.uiState.value!!.showLoginDialog)
        assertTrue("no login ran", loginCalls.isEmpty())
    }

    @Test
    fun signOut_clears_the_account_state_and_delegates_to_the_orchestrator() {
        loginStatus = LoginStatus.Success
        viewModel.loginToGarmin("user@example.com", "s3cret")
        pumpUntil { it.garminConnected }

        viewModel.signOut()

        assertEquals(1, signOutCalls.size)
        assertFalse(
            "sign-out flips the account state both tabs render",
            viewModel.uiState.value!!.garminConnected
        )
    }

    @Test
    fun signOut_keeps_local_sync_state_intact() {
        // Records that were waiting for a sign-in (LoginRequired) stay
        // pending across a sign-out: only the tokens go, so the sign-in
        // afterwards retries exactly the same payload.
        uploadOutcome = UploadOutcome.LoginRequired
        uploadAndAwait(GarminUploadStatus.LOGIN_REQUIRED)
        assertEquals(1, uploadCalls.size)

        viewModel.signOut()

        loginStatus = LoginStatus.Success
        uploadOutcome = UploadOutcome.Success(uploadedCount = 2)
        viewModel.loginToGarmin("user@example.com", "s3cret")
        pumpUntil { it.garminConnected && it.uploadStatus == GarminUploadStatus.SUCCESS }

        assertEquals(
            "the pending payload must be retried after the re-login",
            listOf(twoRecords, twoRecords),
            uploadCalls
        )
    }

    // ------------------------------------------------------------------ //
    //  Home slimming contract (epic R7, task xlg.7)                       //
    // ------------------------------------------------------------------ //

    @Test
    fun home_ui_state_exposes_only_status_and_sync_fields() {
        // The slimmed Home tab renders MainUiState - its fields are exactly
        // the status/sync lines plus the app-level login dialog's state
        // (rendered at the root, not a Home action). The allowlist fails the
        // moment someone sneaks a service action onto the Home state instead
        // of the Settings groups - the R7 regression this pins.
        val allowed = setOf(
            // Status: Garmin account, upload pass, HC availability/permissions
            "garminConnected", "uploadStatus", "totalRecords", "uploadedCount", "uploadMessages",
            "hcAvailable", "hcPermissionsGranted", "hcBloodPressureGranted",
            "hcHydrationGranted", "hcLastSyncEpochMs", "hcPendingCount", "hcSyncError",
            // Sync: the in-flight flag gating "Sync now"
            "hcSyncRunning",
            // The app-level login dialog (AppRoot renders it, Settings opens it)
            "showLoginDialog", "loginNeedsMfa", "loginError",
        )
        val names = MainUiState::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .map { it.name }
            .toSet()
        assertEquals(allowed, names)
    }

    // ------------------------------------------------------------------ //
    //  Shared upload summary line (Home status card + Settings Garmin)    //
    // ------------------------------------------------------------------ //

    @Test
    fun uploadSummary_maps_every_status_onto_the_shared_outcome_line() {
        // Before the first pass: nothing to render.
        assertNull(MainUiState().uploadSummary())

        // In flight: the progress note, no args.
        assertEquals(
            UploadSummary(R.string.upload_in_progress),
            MainUiState(uploadStatus = GarminUploadStatus.UPLOADING).uploadSummary()
        )

        // Terminal outcomes with and without arguments.
        assertEquals(
            UploadSummary(R.string.upload_success, listOf(2)),
            MainUiState(
                uploadStatus = GarminUploadStatus.SUCCESS, uploadedCount = 2
            ).uploadSummary()
        )
        assertEquals(
            UploadSummary(R.string.upload_not_needed),
            MainUiState(uploadStatus = GarminUploadStatus.NOT_NEEDED).uploadSummary()
        )
        assertEquals(
            UploadSummary(
                R.string.upload_partial,
                listOf(1, 2),
                failureMessage = "row 2 rejected by Garmin"
            ),
            MainUiState(
                uploadStatus = GarminUploadStatus.PARTIAL,
                uploadedCount = 1,
                totalRecords = 2,
                uploadMessages = listOf("", "row 2 rejected by Garmin")
            ).uploadSummary()
        )
        assertEquals(
            UploadSummary(
                R.string.upload_retryable,
                failureMessage = "HTTP 429"
            ),
            MainUiState(
                uploadStatus = GarminUploadStatus.RETRYABLE,
                uploadMessages = listOf("HTTP 429")
            ).uploadSummary()
        )
        assertEquals(
            UploadSummary(R.string.login_required),
            MainUiState(uploadStatus = GarminUploadStatus.LOGIN_REQUIRED).uploadSummary()
        )
    }
}
