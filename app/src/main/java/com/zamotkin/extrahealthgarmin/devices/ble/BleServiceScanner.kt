package com.zamotkin.extrahealthgarmin.devices.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.zamotkin.extrahealthgarmin.devices.api.BleSourceScanner
import com.zamotkin.extrahealthgarmin.devices.api.DiscoveredBleDevice
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [BleSourceScanner] over Android's BLE scanner (task
 * tefal2garmin-app-redesign-v3-xlg.3). One filter - the advertised Blood
 * Pressure Service - serves every BLE source type: the standard 0x1810
 * service is what makes a monitor a blood-pressure monitor, and the Omron
 * M7 advertises it too (OmronGattConnection scans with the same filter),
 * so the standard 0x1810 driver (task xlg.9) needs no scanner changes.
 *
 * Callbacks are posted to the main thread (the flow updates LiveData) and
 * dropped after [BleSourceScanner.ScanSession.stop]; every framework call
 * is guarded because the adapter can die or a permission can be revoked
 * mid-scan - the seam contract is "never throws".
 *
 * Caller must hold the BLUETOOTH_SCAN / ACCESS_FINE_LOCATION grant
 * (checked here in [hasRuntimePermissions] before the scan) - hence the
 * suppression, the same pattern OmronGattConnection uses.
 */
@SuppressLint("MissingPermission")
class BleServiceScanner(private val context: Context) : BleSourceScanner {

    override fun start(
        onDevice: (DiscoveredBleDevice) -> Unit,
        onError: (String) -> Unit,
    ): BleSourceScanner.ScanSession {
        val main = Handler(Looper.getMainLooper())
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            main.post { onError("no Bluetooth adapter on this device") }
            return StoppedSession
        }
        if (!hasRuntimePermissions()) {
            // Without the runtime grant a scan either throws (Android 12+)
            // or, worse, silently returns nothing (location below 12) - the
            // flow checks first, this guard keeps an honest error either way.
            main.post { onError("Missing permissions for Bluetooth scanning") }
            return StoppedSession
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            main.post { onError("Bluetooth is switched off") }
            return StoppedSession
        }

        val stopped = AtomicBoolean(false)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (stopped.get()) return
                val name = try {
                    // Reading the name needs BLUETOOTH_CONNECT on Android
                    // 12+; an unnamed device is still selectable by address.
                    result.device.name ?: result.scanRecord?.deviceName ?: ""
                } catch (e: SecurityException) {
                    ""
                }
                val discovered = DiscoveredBleDevice(
                    name = name,
                    address = result.device.address,
                    rssi = result.rssi
                )
                main.post { if (!stopped.get()) onDevice(discovered) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (stopped.get()) return
                main.post {
                    if (!stopped.get()) onError("BLE scan failed with error $errorCode")
                }
            }
        }

        try {
            scanner.startScan(SCAN_FILTERS, SCAN_SETTINGS, callback)
        } catch (e: Exception) {
            // The adapter can vanish between the checks and the call.
            if (!stopped.getAndSet(true)) onError("BLE scan could not start: ${e.message}")
            return StoppedSession
        }

        return BleSourceScanner.ScanSession {
            if (stopped.compareAndSet(false, true)) {
                try {
                    scanner.stopScan(callback)
                } catch (e: Exception) {
                    // Already dead - nothing to stop.
                }
            }
        }
    }

    /** The runtime grant a scan itself needs at this API level. */
    private fun hasRuntimePermissions(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Session handed out when the scan never started. */
    private object StoppedSession : BleSourceScanner.ScanSession {
        override fun stop() = Unit
    }

    private companion object {
        /**
         * The standard Blood Pressure Service (0x1810): every BLE
         * blood-pressure monitor advertises it - the same filter
         * OmronGattConnection's own scan uses.
         */
        private val BLOOD_PRESSURE_SERVICE: ParcelUuid =
            ParcelUuid.fromString("00001810-0000-1000-8000-00805f9b34fb")

        private val SCAN_FILTERS = listOf(
            ScanFilter.Builder().setServiceUuid(BLOOD_PRESSURE_SERVICE).build()
        )

        private val SCAN_SETTINGS = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        private val REQUIRED_PERMISSIONS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
