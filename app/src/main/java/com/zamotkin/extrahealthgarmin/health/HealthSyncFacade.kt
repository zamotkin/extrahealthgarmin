package com.zamotkin.extrahealthgarmin.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import com.zamotkin.extrahealthgarmin.data.GarminBloodPressureRepository
import com.zamotkin.extrahealthgarmin.data.GarminHydrationRepository
import com.zamotkin.extrahealthgarmin.data.GarminRepository
import com.zamotkin.extrahealthgarmin.data.GarminUploadOrchestrator
import java.io.File
import java.time.Duration
import java.time.Instant

/** Availability + permission snapshot the home screen renders. */
data class HcStatusSnapshot(
    val sdkStatus: HcSdkStatus,
    val permissionsGranted: Boolean,
    /** Optional reads: only steer the "tap to allow" hints on the notes. */
    val bloodPressureGranted: Boolean = false,
    val hydrationGranted: Boolean = false
)

/**
 * UI-facing seam over the health/ core (task 2b): one call per thing
 * [com.zamotkin.extrahealthgarmin.ui.MainViewModel] needs — the
 * availability/permission snapshot, one sync pass, the persisted last-sync
 * timestamp and the permission set to request. The interface keeps the
 * ViewModel JVM-testable with a fake; the production implementation is
 * [RealHealthSyncFacade].
 *
 * Note on the permission request: connect-client 1.1.0 offers no
 * Intent-based request builder — the permission screen is launched through
 * `PermissionController.createRequestPermissionResultContract()` registered
 * by MainActivity with the Activity Result API, which is why the contract
 * lives outside this seam.
 */
interface HealthSyncFacade {

    /** Current provider availability + whether the foreground reads are granted. */
    suspend fun status(): HcStatusSnapshot

    /** Runs one [SyncEngine] pass with the Garmin repository as the gateway. */
    suspend fun sync(): SyncResult

    /** Runs one [SyncEngine.backfill] pass over the last [window]. */
    suspend fun backfill(window: Duration): SyncResult

    /** Runs one [BloodPressureSync] pass (no-op until blood pressure access is granted). */
    suspend fun syncBloodPressure(): BloodPressureResult = BloodPressureResult.NOT_ENABLED

    /** Runs one [HydrationSync] pass (no-op until hydration access is granted). */
    suspend fun syncHydration(): HydrationResult = HydrationResult.NOT_ENABLED

    /** Persisted "when sync state last advanced" ([SyncState.lastSyncInstantEpochMs]). */
    fun lastSyncEpochMs(): Long?

    /** Permissions for the Health Connect permission screen (see [HealthPermissions]). */
    fun permissionsToRequest(): Set<String>
}

/**
 * Production [HealthSyncFacade]: wires [HealthConnectSource] + [SyncEngine]
 * over one shared sync-state file, with a lazily provided
 * [GarminUploadOrchestrator] as the upload gateway. All members are
 * main-safe: connect-client calls are suspend and the repository hops to
 * Dispatchers.IO itself.
 *
 * When Health Connect is unavailable, [status] reports the raw
 * [HcSdkStatus] with `permissionsGranted = false` and [sync] degrades to
 * [SyncResult.Idle] — callers gate on [status] before syncing anyway.
 */
class RealHealthSyncFacade(
    private val context: Context,
    private val stateStore: SyncStateStore,
    private val uploaderProvider: () -> GarminUploadOrchestrator,
    private val lockFile: java.io.File? = null
) : HealthSyncFacade {

    override suspend fun status(): HcStatusSnapshot {
        val source = HealthConnectSource.create(context)
            ?: return HcStatusSnapshot(
                sdkStatus = HealthPermissions.sdkStatus(context),
                permissionsGranted = false
            )
        val granted = source.grantedPermissions()
        return HcStatusSnapshot(
            sdkStatus = HcSdkStatus.Available,
            permissionsGranted = granted.containsAll(HealthConnectSource.READ_PERMISSIONS),
            bloodPressureGranted = HealthPermissions.READ_BLOOD_PRESSURE in granted,
            hydrationGranted = HealthPermissions.READ_HYDRATION in granted
        )
    }

    override suspend fun sync(): SyncResult = engine()?.sync() ?: SyncResult.Idle

    override suspend fun backfill(window: Duration): SyncResult =
        engine()?.backfill(window) ?: SyncResult.Idle

    private suspend fun engine(): SyncEngine? {
        val source = HealthConnectSource.create(context) ?: return null
        val uploader = uploaderProvider()
        return SyncEngine(
            source = source,
            stateStore = stateStore,
            gateway = { records -> uploader.uploadNewRecords(records) },
            clock = Instant::now,
            ownPackage = context.packageName,
            lockFile = lockFile
        )
    }

    override suspend fun syncBloodPressure(): BloodPressureResult {
        val source = HealthConnectSource.create(context) ?: return BloodPressureResult.NOT_ENABLED
        return BloodPressureSync(
            source = source,
            store = FileBloodPressureStateStore(
                File(context.filesDir, HealthSyncWiring.BLOOD_PRESSURE_STATE_FILE_NAME)
            ),
            gateway = GarminBloodPressureRepository(context),
            clock = Instant::now
        ).sync()
    }

    override suspend fun syncHydration(): HydrationResult {
        val source = HealthConnectSource.create(context) ?: return HydrationResult.NOT_ENABLED
        return HydrationSync(
            source = source,
            store = FileHydrationStateStore(File(context.filesDir, HealthSyncWiring.HYDRATION_STATE_FILE_NAME)),
            gateway = GarminHydrationRepository(context),
            clock = Instant::now
        ).sync()
    }

    override fun lastSyncEpochMs(): Long? =
        stateStore.load().lastSyncInstantEpochMs

    override fun permissionsToRequest(): Set<String> {
        val backgroundReadAvailable = try {
            HealthConnectClient.getOrCreate(context).features
                .getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (e: Exception) {
            // Provider vanished between the availability check and this tap,
            // or the feature probe failed — request the foreground set only.
            Log.w(TAG, "Background-read feature probe failed; requesting foreground reads only", e)
            false
        }
        return HealthPermissions.permissionsToRequest(backgroundReadAvailable)
    }

    private companion object {
        const val TAG = "RealHealthSyncFacade"
    }
}

/**
 * One wiring point for everything that needs the real facade
 * (MainViewModelFactory, SyncWorker) so the sync-state file name can never
 * drift between them. The uploader is provided lazily because this is also
 * the MainViewModel constructor default — constructing [GarminRepository]
 * eagerly would pull the AndroidKeyStore-backed token store into JVM unit
 * tests that never sync.
 */
object HealthSyncWiring {

    /** Sync state file inside the app's filesDir. */
    const val STATE_FILE_NAME = "hc-sync-state.json"

    /** Cross-process guard next to the state file (task 5 lock). */
    const val LOCK_FILE_NAME = "hc-sync-state.lock"

    /** Blood pressure sync state (own changes token + readings waiting for a pulse). */
    const val BLOOD_PRESSURE_STATE_FILE_NAME = "blood-pressure-sync.json"

    /** Hydration sync state (own changes token + what was sent, for take-backs). */
    const val HYDRATION_STATE_FILE_NAME = "hydration-sync.json"

    fun facade(context: Context, uploader: GarminUploadOrchestrator? = null): HealthSyncFacade =
        RealHealthSyncFacade(
            // The caller's Context is stored as-is (no applicationContext
            // hop): the ViewModel default passes the Application, and the
            // worker passes the application context explicitly.
            context = context,
            stateStore = FileSyncStateStore(File(context.filesDir, STATE_FILE_NAME)),
            uploaderProvider = { uploader ?: GarminRepository(context) },
            lockFile = File(context.filesDir, LOCK_FILE_NAME)
        )
}
