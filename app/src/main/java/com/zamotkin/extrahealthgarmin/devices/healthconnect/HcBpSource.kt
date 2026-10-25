package com.zamotkin.extrahealthgarmin.devices.healthconnect

import android.content.Context
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.devices.api.BpSource
import com.zamotkin.extrahealthgarmin.devices.api.SingletonBpSourceType
import com.zamotkin.extrahealthgarmin.devices.api.SourceCapabilities
import com.zamotkin.extrahealthgarmin.devices.api.SourceCounters
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadRequest
import com.zamotkin.extrahealthgarmin.devices.api.SourceReadResult
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
import com.zamotkin.extrahealthgarmin.health.BloodPressureResult
import com.zamotkin.extrahealthgarmin.health.HealthSyncWiring
import kotlinx.coroutines.CancellationException

/**
 * [BpSource] adapter over the existing Health Connect blood-pressure
 * pipeline (task tefal2garmin-app-redesign-v3-xlg.2): one [read] runs one
 * [com.zamotkin.extrahealthgarmin.health.BloodPressureSync] pass - the
 * changes-token incremental read with its own state file, its 30-day
 * first-run backfill and its Garmin upload - unchanged. The pass consumes
 * its readings internally (Garmin needs the watch's pulse, so the pipeline
 * uploads them itself), which is why a successful read reports its outcome
 * through [SourceCounters] and no readings.
 *
 * The pass arrives as an injected seam, so the adapter is plain JVM and
 * unit-testable with a fake; [HealthConnectBpSourceType] wires the real
 * pipeline. Health Connect has no device-side users (every reading is the
 * phone owner's) and no history re-read (the changes token is the anchor),
 * hence NONE attribution and Current-only support.
 */
class HcBpSource(
    private val syncPass: suspend () -> BloodPressureResult
) : BpSource {

    override val sourceTypeId: String = TYPE_ID
    override val displayNameRes: Int = DISPLAY_NAME_RES
    override val capabilities: SourceCapabilities = CAPABILITIES

    override suspend fun read(request: SourceReadRequest): SourceReadResult {
        if (request.syncMode != SyncMode.CURRENT) {
            return SourceReadResult.Failed("Health Connect supports Current sync only")
        }
        // A userSlot, if a caller sends one, is meaningless here (NONE
        // attribution) and safely ignored.
        // The pass is not exception-free (Health Connect provider IPC, the
        // state-file save) and read() is terminal, so fold its failures
        // onto Failed; cancellation still propagates — every other caller
        // of this pipeline (MainViewModel, SyncWorker) wraps it the same way.
        val result = try {
            syncPass()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SourceReadResult.Failed(
                "Health Connect blood pressure pass failed: " +
                    "${e.message ?: e.javaClass.simpleName}"
            )
        }
        return if (!result.enabled) {
            // The connect step of this source type is the permission grant.
            SourceReadResult.ConnectionRequired(
                "Health Connect blood pressure access is not granted"
            )
        } else {
            SourceReadResult.Success(
                readings = emptyList(),
                counters = SourceCounters(
                    uploaded = result.uploaded,
                    waiting = result.waiting,
                    dropped = result.dropped
                )
            )
        }
    }

    companion object {
        const val TYPE_ID = "health-connect"

        /**
         * Getter-backed, not const: an R field is not a compile-time
         * constant in this setup, and a const companion val initialized
         * from one compiles cross-class references to a getter that is
         * never generated (NoSuchMethodError at runtime).
         */
        val DISPLAY_NAME_RES: Int get() = R.string.bp_source_type_health_connect

        /** One user (the phone owner), one mode (changes since the last pass). */
        val CAPABILITIES = SourceCapabilities(
            userAttribution = UserAttribution.NONE,
            supportedSyncModes = setOf(SyncMode.CURRENT),
            requiresPairingKey = false
        )
    }
}

/**
 * [com.zamotkin.extrahealthgarmin.devices.api.SingletonBpSourceType] of
 * Health Connect: its connect step is the READ_BLOOD_PRESSURE grant, not a
 * scan, so there is exactly one source of this type. The sync pass is the
 * existing facade entry point - the same one the weigh-in flow runs after
 * its own pass - so the pipeline, its state file and its backfill window
 * are shared, never duplicated.
 */
object HealthConnectBpSourceType : SingletonBpSourceType {

    override val typeId: String = HcBpSource.TYPE_ID
    override val displayNameRes: Int = HcBpSource.DISPLAY_NAME_RES
    override val capabilities: SourceCapabilities = HcBpSource.CAPABILITIES

    override fun createSingleton(context: Context): BpSource = HcBpSource(
        syncPass = { HealthSyncWiring.facade(context).syncBloodPressure() }
    )
}
