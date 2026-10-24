package com.zamotkin.extrahealthgarmin.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.zamotkin.extrahealthgarmin.data.GarminRepository
import com.zamotkin.extrahealthgarmin.data.GarminUploadOrchestrator
import com.zamotkin.extrahealthgarmin.data.LoginStatus
import com.zamotkin.extrahealthgarmin.data.UploadOutcome
import com.zamotkin.extrahealthgarmin.garmin.GarminWeighInRecord
import com.zamotkin.extrahealthgarmin.health.HcSdkStatus
import com.zamotkin.extrahealthgarmin.health.HealthPermissions
import com.zamotkin.extrahealthgarmin.health.HealthSyncFacade
import com.zamotkin.extrahealthgarmin.health.HealthSyncWiring
import com.zamotkin.extrahealthgarmin.health.SyncResult
import java.time.Duration

/**
 * Semantic upload status. MainActivity maps each value to an R.string — the
 * ViewModel never formats user-visible text itself.
 */
enum class GarminUploadStatus {
    /** An upload request is in flight. */
    UPLOADING,
    /** All new records were uploaded via the API. */
    SUCCESS,
    /** Nothing new to upload — everything is already on Garmin. */
    NOT_NEEDED,
    /** Some records were uploaded; the rest stay retriable (nothing is written anywhere). */
    PARTIAL,
    /** Nothing was uploaded; the records stay retriable for the next sync. */
    RETRYABLE,
    /** No stored token — the user must sign in before uploading. */
    LOGIN_REQUIRED
}

data class MainUiState(
    val garminConnected: Boolean = false,
    val uploadStatus: GarminUploadStatus? = null,
    /** Size of the batch the running upload was started with (for "x of y" copy). */
    val totalRecords: Int = 0,
    val uploadedCount: Int = 0,
    val uploadMessages: List<String> = emptyList(),
    val showLoginDialog: Boolean = false,
    val loginNeedsMfa: Boolean = false,
    val loginError: String? = null,

    // Health Connect home-screen state (task 2b).

    /** `null` until the first refreshHcStatus completes ("checking..." UI). */
    val hcAvailable: Boolean? = null,
    /** Foreground read permissions granted — the gate for syncing. */
    val hcPermissionsGranted: Boolean = false,
    /** Optional reads; while missing, the matching note offers "tap to allow". */
    val hcBloodPressureGranted: Boolean = false,
    val hcHydrationGranted: Boolean = false,
    /** A sync pass is in flight (manual or onResume-triggered). */
    val hcSyncRunning: Boolean = false,
    /** When sync state last advanced (engine truth); drives the 5-min throttle. */
    val hcLastSyncEpochMs: Long? = null,
    /** Measurements known to be waiting for the next pass after the last one. */
    val hcPendingCount: Int = 0,
    /** Non-localized error marker/reason of the last failed pass; null when fine. */
    val hcSyncError: String? = null
)

class MainViewModel(
    private val garminUploader: GarminUploadOrchestrator,
    application: Application,
    private val hcFacade: HealthSyncFacade = HealthSyncWiring.facade(application),
    private val clock: () -> Long = System::currentTimeMillis
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"

        /** onResume-triggered syncs run at most once per 5 minutes. */
        const val RESUME_SYNC_THROTTLE_MS: Long = 5 * 60 * 1000

        /** [MainUiState.hcSyncError] markers (never user-visible text). */
        const val HC_SYNC_ERROR_RETRYABLE = "retryable"
        const val HC_SYNC_ERROR_LOGIN_REQUIRED = "login_required"
    }

    private val _uiState = MutableLiveData<MainUiState>()
    val uiState: LiveData<MainUiState> = _uiState

    /**
     * Records of the upload that is pending (awaiting login or in flight) —
     * never credentials, only the data itself. A successful dialog login
     * retries exactly this payload; nothing is ever written to a file.
     */
    private var pendingRecords: List<GarminWeighInRecord> = emptyList()

    /**
     * The in-flight login coroutine. [onLoginCancelled] cancels it so a late
     * Success can never run the upload retry (or flip any state) after the
     * dialog was already dismissed.
     */
    private var loginJob: Job? = null

    init {
        _uiState.value = MainUiState(garminConnected = garminUploader.hasStoredToken())
    }

    /**
     * Starts an upload of [records]. LoginRequired keeps the records in
     * [pendingRecords] so a successful dialog login can retry once with
     * exactly the same payload.
     */
    fun startGarminUpload(records: List<GarminWeighInRecord>) {
        pendingRecords = records
        updateState {
            it.copy(
                uploadStatus = GarminUploadStatus.UPLOADING,
                totalRecords = records.size,
                uploadedCount = 0,
                uploadMessages = emptyList(),
                showLoginDialog = false,
                loginNeedsMfa = false,
                loginError = null
            )
        }
        viewModelScope.launch { performUpload(records) }
    }

    private suspend fun performUpload(records: List<GarminWeighInRecord>) {
        try {
            handleUploadOutcome(garminUploader.uploadNewRecords(records))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Garmin upload failed", e)
            updateState {
                it.copy(
                    uploadStatus = GarminUploadStatus.RETRYABLE,
                    uploadMessages = listOf(e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }

    private fun handleUploadOutcome(outcome: UploadOutcome) {
        when (outcome) {
            is UploadOutcome.NotNeeded -> updateState {
                it.copy(uploadStatus = GarminUploadStatus.NOT_NEEDED)
            }
            is UploadOutcome.Success -> updateState {
                it.copy(
                    uploadStatus = GarminUploadStatus.SUCCESS,
                    uploadedCount = outcome.uploadedCount
                )
            }
            is UploadOutcome.Partial -> updateState {
                it.copy(
                    uploadStatus = GarminUploadStatus.PARTIAL,
                    uploadedCount = outcome.uploadedCount,
                    uploadMessages = outcome.failureMessages
                )
            }
            is UploadOutcome.Retryable -> updateState {
                it.copy(
                    uploadStatus = GarminUploadStatus.RETRYABLE,
                    uploadMessages = listOf(outcome.reason)
                )
            }
            is UploadOutcome.LoginRequired -> updateState {
                it.copy(
                    uploadStatus = GarminUploadStatus.LOGIN_REQUIRED,
                    showLoginDialog = true
                )
            }
        }
    }

    /**
     * Signs in to Garmin. Called from the login dialog for the initial submit,
     * the MFA resume, and the "resend code" action (which re-runs login with
     * the same credentials and no code). Credentials go straight into the
     * login call and are never stored on this ViewModel.
     */
    fun loginToGarmin(email: String, password: String, mfaCode: String? = null) {
        loginJob = viewModelScope.launch {
            try {
                val status = garminUploader.login(email, password, mfaCode)
                if (!isActive) {
                    // Cancelled while the login coroutine was suspended: the
                    // dialog is already dismissed — no state flip, no upload.
                    return@launch
                }
                when (status) {
                    is LoginStatus.Success -> {
                        updateState {
                            it.copy(
                                garminConnected = true,
                                showLoginDialog = false,
                                loginNeedsMfa = false,
                                loginError = null
                            )
                        }
                        if (pendingRecords.isNotEmpty()) {
                            // Retry the upload once with the SAME records
                            performUpload(pendingRecords)
                        }
                    }
                    is LoginStatus.NeedsMfa -> updateState {
                        it.copy(loginNeedsMfa = true, showLoginDialog = true, loginError = null)
                    }
                    is LoginStatus.Failed -> updateState {
                        it.copy(loginError = status.reason, showLoginDialog = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e // cancelled by onLoginCancelled — it owns the state now
            } catch (e: Exception) {
                Log.e(TAG, "Garmin login failed", e)
                updateState {
                    it.copy(loginError = e.message ?: e.javaClass.simpleName, showLoginDialog = true)
                }
            }
        }
    }

    /**
     * User cancelled the login dialog. The in-flight login Job is cancelled
     * first so a late Success can never run the upload retry or flip state
     * after the dialog shipped. The pending records stay un-journaled — the
     * next login (or the next sync, once a data source exists) retries them;
     * nothing is written anywhere.
     */
    fun onLoginCancelled() {
        Log.d(TAG, "Garmin login cancelled — records stay pending for the next sync")
        loginJob?.cancel()
        loginJob = null
        updateState {
            it.copy(showLoginDialog = false, loginNeedsMfa = false, loginError = null)
        }
    }

    // ------------------------------------------------------------------ //
    //  Health Connect status + sync (task 2b)                             //
    // ------------------------------------------------------------------ //

    /**
     * Re-reads Health Connect availability/permissions and the persisted
     * last-sync timestamp. Called after the permission screen returns and by
     * [onScreenResume] — a revocation while the app was backgrounded is
     * picked up here on Android 13- (on 14+ the process is restarted).
     */
    fun refreshHcStatus() {
        viewModelScope.launch { refreshHcStatusOnce() }
    }

    private suspend fun refreshHcStatusOnce() {
        try {
            val snapshot = hcFacade.status()
            updateState {
                it.copy(
                    hcAvailable = snapshot.sdkStatus == HcSdkStatus.Available,
                    hcPermissionsGranted = snapshot.permissionsGranted,
                    hcBloodPressureGranted = snapshot.bloodPressureGranted,
                    hcHydrationGranted = snapshot.hydrationGranted,
                    hcLastSyncEpochMs = hcFacade.lastSyncEpochMs() ?: it.hcLastSyncEpochMs
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Health Connect status refresh failed", e)
            updateState { it.copy(hcSyncError = e.message ?: e.javaClass.simpleName) }
        }
    }

    /**
     * Permission-screen result: re-derives the granted flag from the returned
     * set (the background read is optional and may be absent), then re-reads
     * the provider truth.
     */
    fun onHcPermissionsResult(granted: Set<String>) {
        updateState {
            it.copy(
                hcPermissionsGranted = HealthPermissions.hasForegroundPermissions(granted),
                hcBloodPressureGranted = HealthPermissions.READ_BLOOD_PRESSURE in granted,
                hcHydrationGranted = HealthPermissions.READ_HYDRATION in granted
            )
        }
        refreshHcStatus()
    }

    /** Permission set for the Health Connect permission screen launch. */
    fun permissionsToRequest(): Set<String> = hcFacade.permissionsToRequest()

    /**
     * Manual "Sync now" (button). Runs the engine directly in a viewModelScope
     * coroutine — NOT via SyncWorker — so the UI gets instant state. This is
     * the documented split: the worker is background-only, the button is
     * foreground-only. A LoginRequired here opens the login dialog.
     */
    fun syncNow() {
        startSync(userInitiated = true)
    }

    /**
     * Manual backfill over the last [window] (the Backfill dialog). Same UI
     * state machine as [syncNow]; the engine keeps the regular sync position.
     */
    fun backfill(window: Duration) {
        startSync(userInitiated = true) { hcFacade.backfill(window) }
    }

    /**
     * onResume hook: refresh the Health Connect status, then kick a sync pass
     * at most once per [RESUME_SYNC_THROTTLE_MS] (via the injectable [clock]).
     * The persisted lastSync also throttles across process restarts and
     * against passes the periodic SyncWorker already ran. Auto passes never
     * pop the login dialog — a LoginRequired outcome only surfaces the
     * Connect Garmin entry point.
     */
    fun onScreenResume() {
        viewModelScope.launch {
            refreshHcStatusOnce()
            val state = _uiState.value ?: return@launch
            if (state.hcAvailable != true || !state.hcPermissionsGranted) return@launch
            val last = state.hcLastSyncEpochMs
            if (last == null || clock() - last >= RESUME_SYNC_THROTTLE_MS) {
                startSync(userInitiated = false)
            }
        }
    }

    private fun startSync(
        userInitiated: Boolean,
        pass: suspend () -> SyncResult = { hcFacade.sync() }
    ) {
        val current = _uiState.value ?: return
        if (current.hcSyncRunning) return
        if (current.hcAvailable != true || !current.hcPermissionsGranted) return
        updateState {
            it.copy(
                hcSyncRunning = true,
                hcSyncError = null,
                uploadStatus = GarminUploadStatus.UPLOADING,
                uploadMessages = emptyList()
            )
        }
        viewModelScope.launch {
            try {
                handleSyncResult(pass(), userInitiated)
                syncBloodPressure()
                syncHydration()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Health Connect sync failed", e)
                updateState { it.copy(hcSyncError = e.message ?: e.javaClass.simpleName) }
            } finally {
                updateState { it.copy(hcSyncRunning = false) }
            }
            // The sync-state store is the single source of truth for "when
            // did state last advance": the engine leaves it untouched on
            // Retryable and LoginRequired, so this can never advance past a
            // failed pass. (When young lone anchors were deferred the engine
            // deliberately keeps the timestamp put, so the next resume
            // re-reads that window — the cheap Idle pass is the throttle.)
            updateState { state ->
                state.copy(hcLastSyncEpochMs = hcFacade.lastSyncEpochMs() ?: state.hcLastSyncEpochMs)
            }
        }
    }

    /** Best effort after the weigh-in pass; results only reach the log (the worker notifies drops). */
    private suspend fun syncBloodPressure() {
        try {
            val result = hcFacade.syncBloodPressure()
            Log.d(TAG, "Blood pressure pass: $result")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Blood pressure pass failed", e)
        }
    }

    /** Best effort after the blood pressure pass; results only reach the log. */
    private suspend fun syncHydration() {
        try {
            Log.d(TAG, "Hydration pass: ${hcFacade.syncHydration()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Hydration pass failed", e)
        }
    }

    /**
     * Maps one [SyncResult] onto the UI state, reusing the existing
     * upload-status machine (MainActivity already renders every
     * [GarminUploadStatus]); the hc* fields carry the sync-specific bits.
     */
    private fun handleSyncResult(result: SyncResult, userInitiated: Boolean) {
        when (result) {
            SyncResult.Idle -> updateState {
                it.copy(uploadStatus = GarminUploadStatus.NOT_NEEDED, hcPendingCount = 0)
            }
            is SyncResult.Uploaded -> updateState {
                it.copy(
                    uploadStatus = GarminUploadStatus.SUCCESS,
                    uploadedCount = result.uploadedCount,
                    totalRecords = result.uploadedCount,
                    hcPendingCount = 0
                )
            }
            is SyncResult.PartialUploaded -> updateState {
                it.copy(
                    uploadStatus = GarminUploadStatus.PARTIAL,
                    uploadedCount = result.uploadedCount,
                    totalRecords = result.uploadedCount + result.failureMessages.size,
                    uploadMessages = result.failureMessages,
                    hcPendingCount = result.failureMessages.size
                )
            }
            SyncResult.Retryable -> updateState {
                it.copy(
                    uploadStatus = GarminUploadStatus.RETRYABLE,
                    uploadMessages = emptyList(),
                    hcSyncError = HC_SYNC_ERROR_RETRYABLE
                )
            }
            SyncResult.LoginRequired -> updateState {
                val withoutDialog = it.copy(
                    // A sync only hits LoginRequired when the stored token is
                    // gone — surface the Connect Garmin entry point again.
                    garminConnected = false,
                    uploadStatus = GarminUploadStatus.LOGIN_REQUIRED,
                    hcSyncError = HC_SYNC_ERROR_LOGIN_REQUIRED
                )
                if (userInitiated) withoutDialog.copy(showLoginDialog = true) else withoutDialog
            }
        }
    }

    private fun updateState(update: (MainUiState) -> MainUiState) {
        _uiState.value = _uiState.value?.let(update) ?: MainUiState()
    }
}

@Suppress("UNCHECKED_CAST")
class MainViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {

    companion object {
        fun createInstance(application: Application): MainViewModel {
            val garminRepository = GarminRepository(application)
            return MainViewModel(
                garminUploader = garminRepository,
                application = application,
                // One repository instance is shared by the login dialog and
                // the sync gateway so MFA/token session state stays coherent.
                hcFacade = HealthSyncWiring.facade(application, garminRepository),
                clock = System::currentTimeMillis
            )
        }
    }

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return createInstance(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
