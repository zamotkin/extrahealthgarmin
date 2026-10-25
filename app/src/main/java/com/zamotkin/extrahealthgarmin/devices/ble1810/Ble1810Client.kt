package com.zamotkin.extrahealthgarmin.devices.ble1810

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Terminal outcome of one standard 0x1810 readout. Every non-success
 * variant carries a human-readable reason/step — raw English like
 * [com.zamotkin.extrahealthgarmin.omron.ble.OmronBleResult]; the UI layer
 * (add-source flow, task xlg.3) lifts them onto the ble1810_error_* string
 * resources.
 *
 * Pure JVM — no Android types.
 */
sealed class Ble1810Result {

    /**
     * The session ended (self-disconnect or idle timeout — both normal) and
     * delivered [measurements]; an empty list means "nothing new was
     * pushed". The counters tell the non-fatal quirk story (§4.2).
     */
    data class Success(
        val measurements: List<Ble1810Measurement>,
        val feature: Int?,
        val deviceDescription: String?,
        val skippedSentinels: Int,
        val malformedFrames: Int,
        val duplicateFrames: Int
    ) : Ble1810Result()

    /** System bonding is missing or failed — the user must pair the device. */
    data class BondingRequired(val reason: String) : Ble1810Result()

    /** A BLE step exceeded its deadline; [step] names the step it stalled in. */
    data class Timeout(val step: String) : Ble1810Result()

    /** The link dropped before the session could start; [step] names the step. */
    data class Disconnected(val step: String) : Ble1810Result()

    /** Any other failure, including missing Bluetooth permissions. */
    data class Failed(val reason: String) : Ble1810Result()
}

/** Base of the BLE-layer exception hierarchy (thrown by the GATT glue, mapped by the client). */
open class Ble1810Exception(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A GATT operation completed with a non-success status code. */
class Ble1810GattStatusException(val status: Int, what: String) :
    Ble1810Exception("$what failed with GATT status $status")

/** A BLE step exceeded its deadline; [step] is a short human-readable name of the step. */
class Ble1810TimeoutException(val step: String, message: String) : Ble1810Exception(message)

/** System bonding did not complete (refused, timed out or collapsed). */
class Ble1810BondingException(message: String) : Ble1810Exception(message)

/** The link went down before the session started; [step] names the step it died in. */
class Ble1810DisconnectedException(val step: String, message: String) : Ble1810Exception(message)

/**
 * Facade of the standard 0x1810 readout — the
 * [com.zamotkin.extrahealthgarmin.omron.ble.OmronBleClient] counterpart:
 * connect the GATT glue to the stored device, run one [Ble1810Session],
 * fold every failure into [Ble1810Result]. The GATT connection is closed on
 * every exit path (try/finally); everything runs on Dispatchers.IO.
 */
object Ble1810Client {

    private const val TAG = "Ble1810Client"

    /**
     * One read pass of the monitor at [address] (the persisted handle of a
     * connected source). Never throws: cancellation propagates, every other
     * failure is mapped.
     */
    suspend fun readMeasurements(context: Context, address: String): Ble1810Result =
        withContext(Dispatchers.IO) {
            val missing = missingBlePermissions(context)
            if (missing.isNotEmpty()) {
                return@withContext Ble1810Result.Failed(
                    "Missing permissions for Bluetooth: ${missing.joinToString()} - grant them for this app"
                )
            }
            var connection: Ble1810GattConnection? = null
            try {
                connection = Ble1810GattConnection.connect(context, address)
                val result = Ble1810Session(connection).read()
                Log.i(
                    TAG,
                    "session done (${result.endedBy}): ${result.measurements.size} measurements, " +
                        "skipped=${result.skippedSentinels} malformed=${result.malformedFrames} " +
                        "duplicates=${result.duplicateFrames}"
                )
                Ble1810Result.Success(
                    measurements = result.measurements,
                    feature = result.feature,
                    deviceDescription = result.deviceDescription,
                    skippedSentinels = result.skippedSentinels,
                    malformedFrames = result.malformedFrames,
                    duplicateFrames = result.duplicateFrames
                )
            } catch (e: Exception) {
                if (e !is CancellationException) Log.w(TAG, "readMeasurements: ${labelFor(e)} - ${e.message}")
                mapCaughtFailure(e)
            } finally {
                connection?.close()
            }
        }
}

/**
 * The runtime permissions a BLE scan/read needs at API level [sdkInt] — the
 * same set the Omron driver needs (a deliberate copy, not an import: each
 * driver stays independent so either can be removed alone, extensibility
 * contract of epic R2). BLUETOOTH_SCAN + BLUETOOTH_CONNECT on Android 12+;
 * below that (minSdk 26..30) ACCESS_FINE_LOCATION, without which pre-31
 * scans silently return empty results. Pure function - unit-tested.
 */
internal fun requiredBlePermissions(sdkInt: Int): List<String> =
    if (sdkInt >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/**
 * Permissions from [requiredBlePermissions] that [context] has NOT granted;
 * empty means the readout may start. Android-bound - the decision logic
 * itself lives in the pure [requiredBlePermissions].
 */
internal fun missingBlePermissions(context: Context): List<String> =
    requiredBlePermissions(Build.VERSION.SDK_INT).filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

/** Short log label for a caught failure. Pure - mirrors [mapCaughtFailure] for the logging side. */
internal fun labelFor(e: Exception): String = when (e) {
    is Ble1810BondingException -> "bonding required"
    is Ble1810DisconnectedException -> "disconnected (step: ${e.step})"
    is Ble1810TimeoutException -> "timed out (step: ${e.step})"
    else -> "BLE read failed"
}

/**
 * Maps one caught failure onto [Ble1810Result]. Rethrows
 * [CancellationException] first - a cancelled scope must propagate, never
 * surface as Failed (same hygiene as omron/ble). Pure function -
 * unit-tested.
 */
internal fun mapCaughtFailure(e: Exception): Ble1810Result = when (e) {
    is CancellationException -> throw e
    is Ble1810BondingException ->
        Ble1810Result.BondingRequired(e.message ?: "Bluetooth bonding with the monitor is required")
    is Ble1810DisconnectedException -> Ble1810Result.Disconnected(e.step)
    is Ble1810TimeoutException -> Ble1810Result.Timeout(e.step)
    else -> Ble1810Result.Failed(e.message ?: e.javaClass.simpleName)
}
