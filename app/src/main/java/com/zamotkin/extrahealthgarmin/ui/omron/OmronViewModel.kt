package com.zamotkin.extrahealthgarmin.ui.omron

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.data.GarminRepository
import com.zamotkin.extrahealthgarmin.data.UploadOutcome
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import com.zamotkin.extrahealthgarmin.omron.ble.KeystoreAesPairingKeyCrypto
import com.zamotkin.extrahealthgarmin.omron.ble.OmronBleClient
import com.zamotkin.extrahealthgarmin.omron.ble.OmronBleResult
import com.zamotkin.extrahealthgarmin.omron.ble.OmronKeyStore
import com.zamotkin.extrahealthgarmin.omron.ble.FileKeyPersistence
import com.zamotkin.extrahealthgarmin.omron.ble.missingPermissions
import com.zamotkin.extrahealthgarmin.omron.data.BloodPressureDatabase
import com.zamotkin.extrahealthgarmin.omron.data.BpSyncCoordinator
import com.zamotkin.extrahealthgarmin.omron.data.OmronBpRepository
import androidx.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel of the Omron blood-pressure screen (task tefal2garmin-mnb, epic
 * tefal2garmin-939): foreground-interactive only — every pass is user-tapped,
 * there is no background BP sync by design.
 *
 * One [syncNow] pass: permissions check -> BLE read of the selected user slot
 * (key via [keyProvider]) -> store readings (insert-ignore dedup in the
 * repository) -> optional immediate Garmin upload ([coordinator]) when
 * auto-sync is on -> [OmronUiState] mapping. Never throws: every dependency
 * failure lands in [OmronUiState.Error].
 *
 * Collaborators arrive as lambdas/interfaces (manual wiring, no DI framework
 * — same style as ui.MainViewModel): [bleRead] keeps this class free of the
 * omron/ble Android types, so unit tests inject fakes without any BLE
 * scaffolding. [onReloginGarmin] is an explicit callback because the Garmin
 * login dialog state machine lives in MainActivity/MainViewModel — the
 * activity wires the callback to relaunch MainActivity with the login extra.
 *
 * BLE lifecycle: the in-flight pass is cancelled in [onCleared]; GATT
 * cleanup happens inside [OmronBleClient.readNewRecords]'s finally — this
 * class only cancels its own scope job.
 */
class OmronViewModel(
    application: Application,
    private val bleRead: suspend (Context, ByteArray, Int) -> OmronBleResult,
    private val keyProvider: () -> ByteArray,
    private val repository: OmronBpRepository,
    private val coordinator: BpSyncCoordinator,
    private val missingPermissions: (Context) -> List<String>,
    private val settings: OmronSettingsStore,
    private val onReloginGarmin: () -> Unit,
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OmronViewModel"
    }

    private val _uiState = MutableLiveData<OmronUiState>()
    val uiState: LiveData<OmronUiState> = _uiState

    private val _latestReadings = MutableLiveData<List<BloodPressureReading>>()
    val latestReadings: LiveData<List<BloodPressureReading>> = _latestReadings

    private val _userSlot = MutableLiveData(coerceUserSlot(settings.userSlot()))
    val userSlot: LiveData<Int> = _userSlot

    private val _autoSyncEnabled = MutableLiveData(settings.autoSync())
    val autoSyncEnabled: LiveData<Boolean> = _autoSyncEnabled

    /** The in-flight transfer/upload pass; cancelled in [onCleared]. */
    private var syncJob: Job? = null

    init {
        _uiState.value = initialPermissionState()
        loadLatest()
    }

    // ------------------------------------------------------------------ //
    //  Transfer + upload pass                                             //
    // ------------------------------------------------------------------ //

    /**
     * User-tapped pass (Start on the transfer screen / Sync on the
     * dashboard). Re-entrant taps while a pass is running are ignored.
     * Never throws.
     */
    fun syncNow() {
        if (syncJob?.isActive == true) return

        val missing = checkPermissions()
        if (missing.isNotEmpty()) {
            _uiState.value = OmronUiState.PermissionsMissing(missing)
            return
        }

        syncJob = viewModelScope.launch {
            _uiState.value = OmronUiState.Reading
            try {
                val key = keyProvider()
                when (val result = bleRead(getApplication(), key, settings.userSlot())) {
                    is OmronBleResult.Success -> onReadingsRead(result.readings)
                    else -> _uiState.value = result.toUiState()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Omron transfer failed", e)
                _uiState.value = OmronUiState.Error(
                    R.string.omron_error_unexpected,
                    e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    /**
     * Success continuation: persist (idempotent), refresh the dashboard
     * list, then — when auto-sync is on — upload the pending records of the
     * SELECTED slot only (task tefal2garmin-db5: the monitor's two slots are
     * different people and the Garmin account is one person, so the other
     * slot's readings stay local until it is selected). An upload failure
     * never discards the read: readings are already stored and stay
     * retriable, only the state says what happened.
     */
    private suspend fun onReadingsRead(readings: List<BloodPressureReading>) {
        repository.storeReadings(readings)
        loadLatest()
        if (!settings.autoSync()) {
            _uiState.value = OmronUiState.Success(readings.size)
            return
        }
        _uiState.value = when (val outcome = coordinator.syncOnce(settings.userSlot())) {
            UploadOutcome.NotNeeded,
            is UploadOutcome.Success -> OmronUiState.Success(readings.size)
            is UploadOutcome.BpUploaded ->
                if (outcome.failureMessages.isEmpty()) {
                    OmronUiState.Success(readings.size)
                } else {
                    OmronUiState.Error(
                        R.string.omron_error_sync_partial,
                        outcome.failureMessages.firstOrNull()
                    )
                }
            // Weigh-in-only variants: unreachable through the BP coordinator
            // wiring; mapped by closest semantics, defensively.
            is UploadOutcome.Partial -> OmronUiState.Error(
                R.string.omron_error_sync_partial,
                outcome.failureMessages.firstOrNull()
            )
            is UploadOutcome.Retryable -> OmronUiState.Error(
                R.string.omron_error_sync_retryable,
                outcome.reason
            )
            UploadOutcome.LoginRequired ->
                OmronUiState.Error(R.string.omron_error_garmin_login_required)
        }
    }

    /**
     * Settings action: wipes every stored reading from the local database.
     * An in-flight transfer is cancelled and awaited first — otherwise its
     * storeReadings would silently re-insert the wiped rows on completion.
     * A DAO failure is silent by design (never-throws gateway) — the reloaded
     * list keeps showing the rows.
     */
    fun deleteAllReadings() {
        val active = syncJob
        viewModelScope.launch {
            active?.cancel()
            active?.join()
            repository.deleteAll()
            loadLatest()
        }
    }

    /** Permission-request result landed: re-evaluate the gate. */
    fun onPermissionsResult() {
        if (_uiState.value !is OmronUiState.PermissionsMissing) return
        val missing = checkPermissions()
        _uiState.value =
            if (missing.isEmpty()) OmronUiState.Idle
            else OmronUiState.PermissionsMissing(missing)
    }

    // ------------------------------------------------------------------ //
    //  Settings                                                           //
    // ------------------------------------------------------------------ //

    /** Switches the monitor user slot (1|2, coerced) and reloads the list. */
    fun selectUserSlot(slot: Int) {
        val coerced = coerceUserSlot(slot)
        settings.setUserSlot(coerced)
        _userSlot.value = coerced
        loadLatest()
    }

    fun setAutoSync(enabled: Boolean) {
        settings.setAutoSync(enabled)
        _autoSyncEnabled.value = enabled
    }

    /**
     * Garmin re-login entry (Settings screen): delegates to the injected
     * callback — the login dialog flow belongs to MainActivity.
     */
    fun reloginGarmin() {
        onReloginGarmin()
    }

    override fun onCleared() {
        // Cancels the in-flight pass; the cancellation propagates into the
        // BLE read, whose own finally closes the GATT connection.
        syncJob?.cancel()
        super.onCleared()
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    private fun checkPermissions(): List<String> = try {
        missingPermissions(getApplication())
    } catch (e: Exception) {
        // The check is a pure grant lookup — treat any failure as "granted"
        // and let the BLE layer surface a typed result instead of blocking.
        Log.w(TAG, "Permission check failed: ${e.message}")
        emptyList()
    }

    private fun initialPermissionState(): OmronUiState {
        val missing = checkPermissions()
        return if (missing.isEmpty()) OmronUiState.Idle
        else OmronUiState.PermissionsMissing(missing)
    }

    private fun loadLatest() {
        viewModelScope.launch {
            // Never throws by contract; empty list on DAO failure.
            _latestReadings.value = repository.allForUser(settings.userSlot())
        }
    }

    /**
     * Maps a non-success [OmronBleResult] onto the UI state. [Success] is
     * handled by the caller (store + optional upload); here it maps to Idle
     * only to keep the when exhaustive over the sealed hierarchy.
     */
    private fun OmronBleResult.toUiState(): OmronUiState = when (this) {
        is OmronBleResult.Success -> OmronUiState.Idle // unreachable — see KDoc
        is OmronBleResult.BondingRequired ->
            OmronUiState.Pairing(OmronPairingStep.SYSTEM_BONDING)
        is OmronBleResult.AuthFailed ->
            OmronUiState.Error(R.string.omron_error_auth, reason)
        is OmronBleResult.Timeout ->
            OmronUiState.Error(R.string.omron_error_timeout, state)
        is OmronBleResult.Disconnected ->
            OmronUiState.Error(R.string.omron_error_disconnected, state)
        is OmronBleResult.Failed ->
            OmronUiState.Error(R.string.omron_error_failed, reason)
    }
}

/**
 * Manual wiring for [OmronViewModel] (no DI framework — same style as
 * ui.MainViewModelFactory). The Room database is memoized process-wide: the
 * factory is re-created for every activity instance, but one SQLite
 * connection must serve them all (the backup-excluded DB name is pinned to
 * [BloodPressureDatabase.DATABASE_NAME] so the backup rules keep matching).
 */
@Suppress("UNCHECKED_CAST")
class OmronViewModelFactory(
    private val application: Application,
    private val reloginGarmin: () -> Unit,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OmronViewModel::class.java)) {
            return createInstance(application, reloginGarmin) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }

    companion object {
        private const val TAG = "OmronViewModelFactory"

        /** Pairing-key file in filesDir (encrypted blob, see [OmronKeyStore]). */
        private const val PAIRING_KEY_FILE_NAME = "omron-pairing-key"

        /** SharedPreferences file behind [OmronSettingsStore]. */
        private const val SETTINGS_PREFS_NAME = "omron_settings"

        @Volatile
        private var database: BloodPressureDatabase? = null

        private fun database(app: Application): BloodPressureDatabase =
            database ?: synchronized(this) {
                database ?: Room.databaseBuilder(
                    app,
                    BloodPressureDatabase::class.java,
                    BloodPressureDatabase.DATABASE_NAME
                ).build().also { database = it }
            }

        fun createInstance(
            application: Application,
            reloginGarmin: () -> Unit,
        ): OmronViewModel {
            val repository = OmronBpRepository(
                dao = database(application).dao(),
                onError = { e -> Log.w(TAG, "BP repository failure: ${e.message}") }
            )
            // One GarminRepository instance is shared by the BP upload and
            // (per instance) the login dialog session state, mirroring
            // MainViewModelFactory; the file-backed BP journal defuses
            // cross-instance concurrency (merge-on-save).
            val garminRepository = GarminRepository(application)
            val coordinator = BpSyncCoordinator(
                repository = repository,
                upload = { garminRepository.uploadBloodPressure(it) },
                onError = { e -> Log.w(TAG, "BP sync failure: ${e.message}") }
            )
            val keyStore = OmronKeyStore(
                crypto = KeystoreAesPairingKeyCrypto(),
                persistence = FileKeyPersistence(
                    File(application.filesDir, PAIRING_KEY_FILE_NAME)
                )
            )
            return OmronViewModel(
                application = application,
                bleRead = { context, key, userSlot ->
                    OmronBleClient.readNewRecords(context, key, userSlot)
                },
                keyProvider = { keyStore.getOrCreate() },
                repository = repository,
                coordinator = coordinator,
                missingPermissions = { context -> missingPermissions(context) },
                settings = SharedPreferencesOmronSettingsStore(
                    application.getSharedPreferences(
                        SETTINGS_PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                ),
                onReloginGarmin = reloginGarmin,
            )
        }
    }
}
