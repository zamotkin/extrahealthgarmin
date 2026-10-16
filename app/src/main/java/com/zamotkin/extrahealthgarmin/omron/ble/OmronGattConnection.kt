package com.zamotkin.extrahealthgarmin.omron.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.zamotkin.extrahealthgarmin.omron.protocol.OmronProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * The thin Android GATT glue behind [GattChannelConnection]: scans for the M7
 * (Blood Pressure service 0x1810), connects with autoConnect = false (epic
 * anti-pattern: NEVER autoConnect = true - it silently reconnects hours later),
 * discovers services, enables the CCCDs of the RX channels and the unlock
 * characteristic, and bridges GATT callbacks to coroutines.
 *
 * Insufficient-authentication failures trigger one createBond() round-trip and
 * a single retry (spec §2); a failed or refused bond surfaces as
 * [BondingRequiredException].
 *
 * Caller must hold BLUETOOTH_SCAN / BLUETOOTH_CONNECT on Android 12+
 * (checked upstream in [OmronBleClient]) - hence the suppressions.
 * [close] must run on every exit path; [OmronBleClient] does that in finally.
 */
@SuppressLint("MissingPermission")
class OmronGattConnection private constructor(
    private val context: Context,
    private val device: BluetoothDevice,
) : GattChannelConnection, AutoCloseable {

    companion object {
        private const val TAG = "OmronGattConnection"
        private const val SCAN_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SERVICE_TIMEOUT_MS = 15_000L
        private const val GATT_OP_TIMEOUT_MS = 5_000L
        private const val BOND_TIMEOUT_MS = 30_000L
        private val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Blood Pressure service UUID - the M7's advertisement filter (spec §1.1). */
        private val BLOOD_PRESSURE_SERVICE: ParcelUuid =
            ParcelUuid.fromString("00001810-0000-1000-8000-00805f9b34fb")

        /** GATT_INSUFFICIENT_AUTHENTICATION (5) and GATT_INSUFFICIENT_ENCRYPTION (15). */
        private val AUTH_ERROR_STATUSES = intArrayOf(5, 15)

        /**
         * Scans for the M7 and brings a connection through service discovery.
         * The returned connection must be [close]d by the caller on every path.
         */
        suspend fun connect(context: Context, scanTimeoutMs: Long = SCAN_TIMEOUT_MS): OmronGattConnection {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: throw OmronBleException("no Bluetooth adapter on this device")
            if (!adapter.isEnabled) throw OmronBleException("Bluetooth is switched off")
            val scanner = adapter.bluetoothLeScanner
                ?: throw OmronBleException("BLE scanning unavailable - is Bluetooth on?")
            val device = scanForOmron(scanner, scanTimeoutMs)
            val connection = OmronGattConnection(context.applicationContext, device)
            try {
                connection.connectGattAndDiscover()
                return connection
            } catch (t: Throwable) {
                connection.close()
                throw t
            }
        }

        /** The device serves one app at a time (spec §9.3) - it only advertises while free. */
        private suspend fun scanForOmron(
            scanner: android.bluetooth.le.BluetoothLeScanner,
            timeoutMs: Long,
        ): BluetoothDevice {
            val found = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val callback = object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            if (continuation.isActive) continuation.resume(result.device)
                        }

                        override fun onScanFailed(errorCode: Int) {
                            if (continuation.isActive) {
                                continuation.resumeWith(
                                    Result.failure(OmronBleException("BLE scan failed with error $errorCode")),
                                )
                            }
                        }
                    }
                    val filters = listOf(
                        ScanFilter.Builder().setServiceUuid(BLOOD_PRESSURE_SERVICE).build(),
                    )
                    val settings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build()
                    scanner.startScan(filters, settings, callback)
                    continuation.invokeOnCancellation { scanner.stopScan(callback) }
                }
            }
            return found ?: throw GattTimeoutException(
                "scan",
                "Omron device not found within ${timeoutMs}ms - wake it (measurement or button) and stay close",
            )
        }
    }

    private val opMutex = Mutex()

    /**
     * The single write-with-response slot; transport-level serialization means
     * one at a time. Extracted (task tefal2garmin-983) so cancellation cleanup
     * is unit-tested in [PendingWriteSlot].
     */
    private val pendingWriteSlot = PendingWriteSlot(GATT_OP_TIMEOUT_MS)

    private var gatt: BluetoothGatt? = null
    private val characteristics = HashMap<String, BluetoothGattCharacteristic>()
    private val notifyCharacteristics = ArrayList<BluetoothGattCharacteristic>()

    @Volatile
    private var channelHandler: ((uuid: String, bytes: ByteArray) -> Unit)? = null

    @Volatile
    private var closed = false

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "connection state change: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connectResult?.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED -> failAllPending(
                    GattDisconnectedException(
                        state = "connect",
                        message = "link dropped (status=$status)",
                    ),
                )
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesResult?.complete(Unit)
            } else {
                servicesResult?.completeExceptionally(
                    OmronBleException("service discovery failed with status $status"),
                )
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pendingWriteSlot.complete(status)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pendingWriteSlot.complete(status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // API 33+ dispatches the two-arg overload whose default impl calls
            // this one, so overriding only the legacy variant covers all levels.
            onNotify(characteristic.uuid.toString(), characteristic.value ?: ByteArray(0))
        }
    }

    @Volatile
    private var connectResult: CompletableDeferred<Unit>? = null

    @Volatile
    private var servicesResult: CompletableDeferred<Unit>? = null

    // ------------------------------------------------------------------ //
    //  Connection setup                                                   //
    // ------------------------------------------------------------------ //

    private suspend fun connectGattAndDiscover() {
        // Deferreds are registered BEFORE connectGatt: the binder thread can call
        // onConnectionStateChange synchronously-fast, and a null deferred would
        // drop the callback into a 15 s timeout (task tefal2garmin-983, m4).
        val connected = CompletableDeferred<Unit>()
        val services = CompletableDeferred<Unit>()
        connectResult = connected
        servicesResult = services
        try {
            val gatt = device.connectGatt(context, false, gattCallback)
                ?: throw OmronBleException("connectGatt returned null for ${device.address}")
            this.gatt = gatt
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
                ?: throw GattTimeoutException("connect", "GATT connect timed out after ${CONNECT_TIMEOUT_MS}ms")
            if (!gatt.discoverServices()) {
                throw OmronBleException("discoverServices request was rejected")
            }
            withTimeoutOrNull(SERVICE_TIMEOUT_MS) { services.await() }
                ?: throw GattTimeoutException("discover", "service discovery timed out")
            cacheCharacteristics()
        } catch (t: Throwable) {
            close()
            throw t
        } finally {
            connectResult = null
            servicesResult = null
        }
    }

    /** Enumerates the proprietary service; does NOT assume all 4 channels exist (decision log #4). */
    private fun cacheCharacteristics() {
        val gatt = this.gatt ?: throw OmronBleException("not connected")
        val service = gatt.getService(UUID.fromString(OmronProtocol.PARENT_SERVICE_UUID))
            ?: throw OmronBleException("Omron data service not discovered - wrong device?")
        for (characteristic in service.characteristics) {
            characteristics[characteristic.uuid.toString()] = characteristic
        }
        // RX channel 0 first: enabling its CCCD is what triggers the device's
        // SMP Security Request (spec §3.2), then the unlock characteristic.
        for (uuid in OmronProtocol.RX_CHANNEL_UUIDS) {
            characteristics[uuid]?.let { notifyCharacteristics.add(it) }
        }
        characteristics[OmronProtocol.UNLOCK_CHARACTERISTIC_UUID]?.let { notifyCharacteristics.add(it) }
        Log.i(
            TAG,
            "Omron service up: ${notifyCharacteristics.size} notifiable characteristics, " +
                "TX channels present: ${OmronProtocol.TX_CHANNEL_UUIDS.count { characteristics.containsKey(it) }}",
        )
        if (notifyCharacteristics.isEmpty()) {
            throw OmronBleException("no Omron RX/unlock characteristics discovered - wrong device?")
        }
    }

    // ------------------------------------------------------------------ //
    //  GattChannelConnection seam                                        //
    // ------------------------------------------------------------------ //

    override suspend fun enableNotifications() = opMutex.withLock {
        for (characteristic in notifyCharacteristics) {
            withBondRetry("enable notify ${characteristic.uuid}") { enableCccd(characteristic) }
        }
    }

    override suspend fun writeCharacteristic(uuid: String, payload: ByteArray): Boolean = opMutex.withLock {
        val characteristic = characteristics[uuid]
            ?: throw OmronBleException("characteristic not found: $uuid - unexpected GATT layout")
        withBondRetry("write $uuid") {
            awaitWriteStatus("write $uuid") {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")   // legacy pre-33 API - works on all levels
                val accepted = gatt?.writeCharacteristic(characteristic) ?: false
                if (!accepted) throw OmronBleException("writeCharacteristic rejected for $uuid")
            }
        }
        true
    }

    override fun onChannelData(handler: (uuid: String, bytes: ByteArray) -> Unit) {
        channelHandler = handler
    }

    /** Releases the GATT client; safe to call more than once, from any exit path. */
    override fun close() {
        if (closed) return
        closed = true
        failAllPending(GattDisconnectedException("close", "connection closed"))
        try {
            gatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "gatt.close() threw", e)
        }
        gatt = null
    }

    private fun onNotify(uuid: String, bytes: ByteArray) {
        Log.d(TAG, "notify ${uuid.substringAfterLast('-')} (${bytes.size}B)")
        channelHandler?.invoke(uuid, bytes)
    }

    // ------------------------------------------------------------------ //
    //  CCCD + write-with-response plumbing                               //
    // ------------------------------------------------------------------ //

    /** Writes 0x0001/0x0002 to the CCCD (0x2902): notify or indicate, per the properties. */
    private suspend fun enableCccd(characteristic: BluetoothGattCharacteristic) {
        val cccd = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
            ?: throw OmronBleException("CCCD missing on ${characteristic.uuid}")
        val enableValue = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            byteArrayOf(0x02, 0x00)
        } else {
            byteArrayOf(0x01, 0x00)
        }
        awaitWriteStatus("CCCD ${characteristic.uuid}") {
            @Suppress("DEPRECATION")
            cccd.value = enableValue
            @Suppress("DEPRECATION")   // legacy pre-33 API - works on all levels
            val accepted = gatt?.writeDescriptor(cccd) ?: false
            if (!accepted) throw OmronBleException("writeDescriptor rejected for ${characteristic.uuid}")
        }
    }

    /**
     * Registers the pending write, invokes [invoke] (the actual GATT call) and
     * awaits the callback's status. All slot cleanup — including external
     * cancellation of the awaiting coroutine — lives in [PendingWriteSlot]
     * (task tefal2garmin-983, M1).
     */
    private suspend fun awaitWriteStatus(what: String, invoke: () -> Unit) {
        val status = pendingWriteSlot.awaitStatus(what, invoke)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            throw GattStatusException(status, what)
        }
    }

    private fun failAllPending(t: Throwable) {
        pendingWriteSlot.fail(t)
        connectResult?.completeExceptionally(t)
        servicesResult?.completeExceptionally(t)
    }

    // ------------------------------------------------------------------ //
    //  SMP bonding (spec §2)                                              //
    // ------------------------------------------------------------------ //

    /** Runs [op]; on an insufficient-authentication/encryption status bonds once, then retries once. */
    private suspend fun <T> withBondRetry(what: String, op: suspend () -> T): T = try {
        op()
    } catch (e: GattStatusException) {
        if (e.status in AUTH_ERROR_STATUSES) {
            Log.i(TAG, "$what hit status ${e.status} - bonding and retrying once")
            ensureBonded()
            op()
        } else {
            throw e
        }
    }

    private suspend fun ensureBonded() {
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> return
            BluetoothDevice.BOND_BONDING -> Unit   // pairing dialog already up - just wait
            else -> {
                if (!device.createBond()) {
                    throw BondingRequiredException(
                        "device refused bonding - pair the blood-pressure monitor in system Bluetooth settings",
                    )
                }
            }
        }
        val bonded = withTimeoutOrNull(BOND_TIMEOUT_MS) { awaitBonded() }
        if (bonded != true) {
            throw BondingRequiredException(
                "pairing did not complete within ${BOND_TIMEOUT_MS}ms - confirm the pairing dialog on both devices",
            )
        }
    }

    /**
     * true once BOND_BONDED is broadcast; false if pairing collapses back to BOND_NONE.
     *
     * The receiver is unregistered on EVERY exit path — bond broadcast, tail
     * bond-state check and cancellation — guarded by an atomic once-flag
     * (task tefal2garmin-983, M2/m7): the tail check runs on the awaiting
     * coroutine's thread while broadcasts arrive on the main thread, and the
     * previously registration-only cleanup leaked one receiver per bond wait
     * whenever a broadcast resumed the continuation first.
     */
    private suspend fun awaitBonded(): Boolean = suspendCancellableCoroutine { continuation ->
        lateinit var receiver: BroadcastReceiver
        val finished = AtomicBoolean(false)

        // Exactly-once: resumes (at most once) and unregisters the receiver,
        // whichever of the three exit paths gets there first.
        fun finish(outcome: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { context.unregisterReceiver(receiver) }
            if (continuation.isActive) continuation.resume(outcome)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val bondedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                }
                if (bondedDevice?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> finish(true)
                    BluetoothDevice.BOND_NONE -> finish(false)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        continuation.invokeOnCancellation {
            if (finished.compareAndSet(false, true)) {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
        // Post-registration tail check: the bond may have completed while we
        // were registering (also closes the receiver-vs-tail double-resume window).
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            finish(true)
        }
    }
}

