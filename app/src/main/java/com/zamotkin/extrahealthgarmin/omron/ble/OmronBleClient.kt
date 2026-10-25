package com.zamotkin.extrahealthgarmin.omron.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.zamotkin.extrahealthgarmin.omron.protocol.OmronSession
import com.zamotkin.extrahealthgarmin.omron.protocol.OmronSessionException
import com.zamotkin.extrahealthgarmin.omron.protocol.UnlockRejectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Facade of the Omron M7 BLE readout: connect, unlock with the application
 * key, run the EEPROM session, and fold every failure into [OmronBleResult].
 * The GATT connection is closed on every exit path (try/finally).
 *
 * The pairing key arrives as a parameter; [OmronKeyStore] provides the
 * Keystore-backed storage (16 bytes, AES at rest). Everything runs on
 * Dispatchers.IO; the BLE code never touches UI state.
 */
object OmronBleClient {

    private const val TAG = "OmronBleClient"

    /**
     * One BLE readout of [userSlot]. [fullRead] selects the whole-ring history
     * transfer (task tefal2garmin-8rb): every stored record of the ring is
     * read and the device's unread counters are left alone; false reads only
     * the unread window and resets the counters.
     */
    suspend fun readNewRecords(
        context: Context,
        key: ByteArray,
        userSlot: Int,
        fullRead: Boolean,
    ): OmronBleResult = withContext(Dispatchers.IO) {
        val missing = missingPermissions(context)
        if (missing.isNotEmpty()) {
            return@withContext OmronBleResult.Failed(
                "Missing permissions for Bluetooth: ${missing.joinToString()} - grant them for this app",
            )
        }
        var connection: OmronGattConnection? = null
        try {
            connection = OmronGattConnection.connect(context)
            val transport = GattChannelTransport(connection)
            transport.initialize()
            val result = OmronSession.readOut(transport, key, userSlot, readFullRing = fullRead)
            OmronBleResult.Success(result.readings)
        } catch (e: Exception) {
            if (e !is CancellationException) log(labelFor(e), e)
            mapCaughtFailure(e)
        } finally {
            connection?.close()
        }
    }

    private fun log(what: String, e: Exception) {
        Log.w(TAG, "readNewRecords: $what - ${e.message}")
    }
}

/**
 * The runtime permissions a BLE readout needs at API level [sdkInt]
 * (task tefal2garmin-983, M3): BLUETOOTH_SCAN + BLUETOOTH_CONNECT on
 * Android 12+; below that (minSdk 26..30) ACCESS_FINE_LOCATION - a dangerous
 * permission with a runtime grant required since API 23, without which BLE
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
 * empty means the readout may start. Public so the UI layer can request
 * exactly what is missing before calling [OmronBleClient.readNewRecords]
 * (task tefal2garmin-983, M3). Android-bound - the decision logic itself
 * lives in the pure [requiredBlePermissions].
 */
fun missingPermissions(context: Context): List<String> =
    requiredBlePermissions(Build.VERSION.SDK_INT).filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

/**
 * Short log label for a caught failure. Pure - mirrors [mapCaughtFailure]
 * for the logging side (task tefal2garmin-983).
 */
internal fun labelFor(e: Exception): String = when (e) {
    is UnlockRejectedException -> "unlock rejected"
    is BondingRequiredException -> "bonding required"
    is GattDisconnectedException -> "disconnected (state: ${e.state})"
    is GattTimeoutException -> "timed out (state: ${e.state})"
    is OmronSessionException -> "session failed"
    else -> "BLE read failed"
}

/**
 * Maps one caught failure onto [OmronBleResult] (task tefal2garmin-983).
 * Rethrows [CancellationException] first - a cancelled scope must propagate,
 * never surface as Failed (same hygiene as omron/data). Unlock rejection is
 * recognized by the typed [UnlockRejectedException], not message text.
 * Pure function - unit-tested.
 */
internal fun mapCaughtFailure(e: Exception): OmronBleResult = when (e) {
    is CancellationException -> throw e
    is UnlockRejectedException -> OmronBleResult.AuthFailed(e.message ?: "unlock rejected")
    is BondingRequiredException ->
        OmronBleResult.BondingRequired(e.message ?: "Bluetooth bonding with the monitor is required")
    is GattDisconnectedException -> OmronBleResult.Disconnected(e.message ?: e.state)
    is GattTimeoutException -> OmronBleResult.Timeout(e.message ?: e.state)
    is OmronSessionException -> OmronBleResult.Failed(e.message ?: "session failed")
    else -> OmronBleResult.Failed(e.message ?: e.javaClass.simpleName)
}
