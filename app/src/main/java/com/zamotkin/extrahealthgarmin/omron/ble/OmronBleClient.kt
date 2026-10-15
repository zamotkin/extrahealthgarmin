package com.zamotkin.extrahealthgarmin.omron.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.zamotkin.extrahealthgarmin.omron.protocol.OmronSession
import com.zamotkin.extrahealthgarmin.omron.protocol.OmronSessionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Facade of the Omron M7 BLE readout: connect, unlock with the application
 * key, run the EEPROM session, and fold every failure into [OmronBleResult].
 * The GATT connection is closed on every exit path (try/finally).
 *
 * The pairing key arrives as a parameter - Keystore-backed key storage is a
 * separate task. Everything runs on Dispatchers.IO; the BLE code never
 * touches UI state.
 */
object OmronBleClient {

    private const val TAG = "OmronBleClient"

    /** Prefix of OmronSession's unlock-rejection message - maps to AuthFailed. */
    private const val UNLOCK_REJECTED = "unlock rejected"

    suspend fun readNewRecords(
        context: Context,
        key: ByteArray,
        userSlot: Int,
    ): OmronBleResult = withContext(Dispatchers.IO) {
        if (!hasRequiredPermissions(context)) {
            return@withContext OmronBleResult.Failed(
                "Missing Bluetooth permissions - grant Nearby devices for this app",
            )
        }
        var connection: OmronGattConnection? = null
        try {
            connection = OmronGattConnection.connect(context)
            val transport = GattChannelTransport(connection)
            transport.initialize()
            val result = OmronSession.readOut(transport, key, userSlot)
            OmronBleResult.Success(result.readings)
        } catch (e: BondingRequiredException) {
            log("bonding required", e)
            OmronBleResult.BondingRequired(e.message ?: "Bluetooth bonding with the monitor is required")
        } catch (e: GattDisconnectedException) {
            log("disconnected (state: ${e.state})", e)
            OmronBleResult.Disconnected(e.message ?: e.state)
        } catch (e: GattTimeoutException) {
            log("timed out (state: ${e.state})", e)
            OmronBleResult.Timeout(e.message ?: e.state)
        } catch (e: OmronSessionException) {
            log("session failed", e)
            if (e.message?.startsWith(UNLOCK_REJECTED) == true) {
                OmronBleResult.AuthFailed(e.message!!)
            } else {
                OmronBleResult.Failed(e.message ?: "session failed")
            }
        } catch (e: Exception) {
            log("BLE read failed", e)
            OmronBleResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.close()
        }
    }

    private fun log(what: String, e: Exception) {
        Log.w(TAG, "readNewRecords: $what - ${e.message}")
    }

    /** BLUETOOTH_SCAN/CONNECT are runtime permissions on Android 12+; below that they are install-time. */
    private fun hasRequiredPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}
