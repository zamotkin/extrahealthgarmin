package com.zamotkin.extrahealthgarmin.devices.ble1810

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * The Android GATT glue of the standard 0x1810 driver — the
 * [com.zamotkin.extrahealthgarmin.omron.ble.OmronGattConnection]
 * counterpart, and THE ONLY FILE of the driver that imports
 * `android.bluetooth`. It implements the pure [Ble1810Transport] seam:
 *
 *  - connects to the stored device by address, direct (`autoConnect=false`;
 *    epic anti-pattern: NEVER autoConnect=true - it silently reconnects
 *    hours later, and a 0x1810 monitor self-disconnects after every
 *    transfer by design, §4.2),
 *  - completes system bonding when the device is not bonded yet
 *    (opportunistic createBond + broadcast wait; pairing prompts are system
 *    UI - the flow shows an explanatory step before it),
 *  - discovers services, enables indications on 0x2A35 via the CCCD
 *    (0x2902 = 0x0002) and reads 0x2A49 / 0x180A,
 *  - bridges every callback to coroutines; indications are buffered from
 *    connection start, so the "indication before CCCD confirmation" quirk
 *    (§4.2) loses nothing,
 *  - maps the device's self-disconnect (GATT status 19, remote user
 *    terminated - the NORMAL transfer end) onto a clean session end.
 *
 * Caller must hold BLUETOOTH_SCAN / BLUETOOTH_CONNECT on Android 12+
 * (checked upstream in [Ble1810Client] / the scan transport) - hence the
 * suppression. [close] must run on every exit path; [Ble1810Client] does
 * that in finally.
 *
 * Coverage stance (same as OmronGattConnection): this Android-bound glue is
 * deliberately NOT unit-tested - no Robolectric on this toolchain. The
 * testable logic lives behind the seams ([Ble1810Session] over faked
 * [Ble1810Transport], [Ble1810Scanner] over faked [Ble1810ScanTransport]).
 */
@SuppressLint("MissingPermission")
class Ble1810GattConnection private constructor(
    private val context: Context,
    private val device: BluetoothDevice
) : Ble1810Transport, AutoCloseable {

    companion object {
        private const val TAG = "Ble1810GattConnection"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 15_000L
        private const val GATT_OP_TIMEOUT_MS = 5_000L
        private const val BOND_TIMEOUT_MS = 30_000L

        /** GATT_INSUFFICIENT_AUTHENTICATION (5) and GATT_INSUFFICIENT_ENCRYPTION (15). */
        private val AUTH_ERROR_STATUSES = intArrayOf(5, 15)

        /**
         * Brings a connection to the device at [address] through bonding and
         * the CCCD subscription. The returned connection must be [close]d by
         * the caller on every path.
         */
        suspend fun connect(context: Context, address: String): Ble1810GattConnection {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: throw Ble1810Exception("no Bluetooth adapter on this device")
            if (!adapter.isEnabled) throw Ble1810Exception("Bluetooth is switched off")
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                throw Ble1810Exception("invalid device address: $address")
            }
            val connection = Ble1810GattConnection(context.applicationContext, device)
            try {
                connection.connectAndSubscribe()
                return connection
            } catch (t: Throwable) {
                connection.close()
                throw t
            }
        }
    }

    private val opMutex = Mutex()

    /** The one in-flight write-with-response (the CCCD write). */
    private val pendingWrite = PendingOp<Int>()

    /** The one in-flight characteristic read (0x2A49 / Device Information). */
    private val pendingRead = PendingOp<ReadOutcome>()

    /** Indications buffered from connection start; closed on disconnect (drains first). */
    private val indications = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var deviceDisconnected = false

    @Volatile
    private var closed = false

    private var gatt: BluetoothGatt? = null

    @Volatile
    private var connectResult: CompletableDeferred<Unit>? = null

    @Volatile
    private var servicesResult: CompletableDeferred<Unit>? = null

    private data class ReadOutcome(val status: Int, val value: ByteArray)

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "connection state change: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connectResult?.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (status == Ble1810GattStatuses.REMOTE_USER_TERMINATED) {
                        // Remote user terminated connection (0x13): the monitor
                        // closed the link after its transfer burst - §4.2, the
                        // NORMAL end. Never reconnect in a loop after it.
                        Log.i(TAG, "device self-disconnected (status 19) - normal transfer end")
                    } else {
                        Log.w(TAG, "link dropped: status=$status")
                    }
                    deviceDisconnected = true
                    indications.close()
                    failPending(
                        Ble1810DisconnectedException("connect", "link dropped (status=$status)")
                    )
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesResult?.complete(Unit)
            } else {
                servicesResult?.completeExceptionally(
                    Ble1810Exception("service discovery failed with status $status")
                )
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "descWrite status=$status")
            pendingWrite.complete(status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // Pre-33 dispatch path; the 4-arg override below carries the
            // value there. Both funnel into the same pending slot.
            pendingRead.complete(ReadOutcome(status, characteristic.value ?: ByteArray(0)))
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            pendingRead.complete(ReadOutcome(status, value))
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onIndication(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onIndication(characteristic.uuid, value)
        }
    }

    // ------------------------------------------------------------------ //
    //  Connection setup                                                   //
    // ------------------------------------------------------------------ //

    override suspend fun connectAndSubscribe() {
        // Deferreds are registered BEFORE connectGatt: the binder thread can
        // call onConnectionStateChange synchronously-fast, and a null
        // deferred would drop the callback into a 15 s timeout (the
        // tefal2garmin-983 m4 lesson).
        val connected = CompletableDeferred<Unit>()
        val services = CompletableDeferred<Unit>()
        connectResult = connected
        servicesResult = services
        try {
            val gatt = device.connectGatt(context, false, gattCallback)
                ?: throw Ble1810Exception("connectGatt returned null for ${device.address}")
            this.gatt = gatt
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
                ?: throw Ble1810TimeoutException("connect", "GATT connect timed out after ${CONNECT_TIMEOUT_MS}ms")
            if (!gatt.discoverServices()) {
                throw Ble1810Exception("discoverServices request was rejected")
            }
            withTimeoutOrNull(SERVICE_DISCOVERY_TIMEOUT_MS) { services.await() }
                ?: throw Ble1810TimeoutException("discover", "service discovery timed out")
            // System bonding before the CCCD write: the service itself needs
            // none (spec §1.1), but the verified devices pair through the OS
            // and bonded reads ride an encrypted link (§4.1 step 2).
            ensureBonded()
            subscribeMeasurements()
        } finally {
            connectResult = null
            servicesResult = null
        }
    }

    /** Locates 0x2A35, registers locally and enables indications through the CCCD. */
    private suspend fun subscribeMeasurements() = opMutex.withLock {
        val gatt = this.gatt ?: throw Ble1810Exception("not connected")
        val service = serviceOf(Ble1810Protocol.BLOOD_PRESSURE_SERVICE_UUID)
            ?: throw Ble1810Exception("Blood Pressure Service (0x1810) not discovered - wrong device?")
        val measurement = service.getCharacteristic(Ble1810Protocol.MEASUREMENT_CHARACTERISTIC_UUID)
            ?: throw Ble1810Exception("Blood Pressure Measurement (0x2A35) missing - wrong device?")
        if (measurement.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE == 0) {
            throw Ble1810Exception("0x2A35 does not carry Indicate - wrong device?")
        }
        // Local client-side registration is mandatory: the CCCD write alone
        // only tells the DEVICE to send indications - without this call the
        // phone stack never routes them to onCharacteristicChanged (the
        // tefal2garmin-nov lesson, proven on a real HEM-7361T-ALRU).
        if (!gatt.setCharacteristicNotification(measurement, true)) {
            throw Ble1810Exception("setCharacteristicNotification failed for 0x2A35")
        }
        val cccd = measurement.getDescriptor(Ble1810Protocol.CCC_DESCRIPTOR_UUID)
            ?: throw Ble1810Exception("CCCD (0x2902) missing on 0x2A35")
        withBondRetry("enable indications") {
            val status = pendingWrite.await(GATT_OP_TIMEOUT_MS, "CCCD 0x2A35") {
                @Suppress("DEPRECATION") // legacy pre-33 API - works on all levels
                cccd.value = Ble1810Protocol.CCCD_ENABLE_INDICATIONS
                @Suppress("DEPRECATION") // legacy pre-33 API - works on all levels
                if (!gatt.writeDescriptor(cccd)) {
                    throw Ble1810Exception("writeDescriptor rejected for 0x2A35")
                }
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw Ble1810GattStatusException(status, "CCCD write on 0x2A35")
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Ble1810Transport seam                                              //
    // ------------------------------------------------------------------ //

    override suspend fun readFeature(): Int? = opMutex.withLock {
        val service = serviceOf(Ble1810Protocol.BLOOD_PRESSURE_SERVICE_UUID) ?: return@withLock null
        val characteristic = service.getCharacteristic(Ble1810Protocol.FEATURE_CHARACTERISTIC_UUID)
            ?: return@withLock null
        val bytes = readBytes(characteristic) ?: return@withLock null
        if (bytes.size < 2) {
            Log.w(TAG, "0x2A49 carries ${bytes.size} bytes - ignoring the feature bitmap")
            null
        } else {
            (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        }
    }

    override suspend fun readDeviceDescription(): String? = opMutex.withLock {
        val service = serviceOf(Ble1810Protocol.DEVICE_INFORMATION_SERVICE_UUID) ?: return@withLock null
        val manufacturer = descriptionString(service.getCharacteristic(Ble1810Protocol.MANUFACTURER_NAME_UUID))
        val model = descriptionString(service.getCharacteristic(Ble1810Protocol.MODEL_NUMBER_UUID))
        listOfNotNull(manufacturer, model).joinToString(" ").ifBlank { null }
    }

    override suspend fun awaitNextMeasurement(idleTimeoutMs: Long): Ble1810NextMeasurement {
        // Buffered indications drain first - even after the channel was
        // closed by a disconnect - so a burst that raced the link teardown
        // still delivers every record before the session ends.
        val next = withTimeoutOrNull(idleTimeoutMs) {
            try {
                Ble1810NextMeasurement.Indication(indications.receive())
            } catch (e: ClosedReceiveChannelException) {
                null
            }
        }
        return when {
            next != null -> next
            deviceDisconnected -> Ble1810NextMeasurement.DeviceDisconnected
            else -> Ble1810NextMeasurement.IdleTimeout
        }
    }

    /** Releases the GATT client; safe to call more than once, from any exit path. */
    override fun close() {
        if (closed) return
        closed = true
        failPending(Ble1810DisconnectedException("close", "connection closed"))
        indications.close()
        try {
            gatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "gatt.close() threw", e)
        }
        gatt = null
    }

    // ------------------------------------------------------------------ //
    //  Reads + bonding plumbing                                           //
    // ------------------------------------------------------------------ //

    private fun serviceOf(uuid: UUID): BluetoothGattService? =
        gatt?.getService(uuid)

    /**
     * Reads one characteristic with response; throws
     * [Ble1810GattStatusException] on a non-success status so
     * [withBondRetry] can recognize authentication failures.
     */
    private suspend fun readBytes(characteristic: BluetoothGattCharacteristic): ByteArray {
        val gatt = this.gatt ?: throw Ble1810Exception("not connected")
        val what = "read 0x${shortUuid(characteristic.uuid)}"
        return withBondRetry(what) {
            val outcome = pendingRead.await(GATT_OP_TIMEOUT_MS, what) {
                @Suppress("DEPRECATION") // legacy pre-33 API - works on all levels
                if (!gatt.readCharacteristic(characteristic)) {
                    throw Ble1810Exception("readCharacteristic rejected for 0x${shortUuid(characteristic.uuid)}")
                }
            }
            if (outcome.status != BluetoothGatt.GATT_SUCCESS) {
                throw Ble1810GattStatusException(outcome.status, what)
            }
            outcome.value
        }
    }

    /** A Device Information String characteristic; null when absent or unreadable. */
    private suspend fun descriptionString(characteristic: BluetoothGattCharacteristic?): String? {
        if (characteristic == null) return null
        return try {
            String(readBytes(characteristic), Charsets.UTF_8).trim().ifBlank { null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // diagnostics only - one unreadable string never fails the session
        }
    }

    /** Runs [op]; on an insufficient-authentication/encryption status bonds once, then retries once. */
    private suspend fun <T> withBondRetry(what: String, op: suspend () -> T): T = try {
        op()
    } catch (e: Ble1810GattStatusException) {
        if (e.status in AUTH_ERROR_STATUSES) {
            Log.i(TAG, "$what hit GATT status ${e.status} - bonding and retrying once")
            ensureBonded()
            op()
        } else {
            throw e
        }
    }

    private suspend fun ensureBonded() {
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> return
            BluetoothDevice.BOND_BONDING -> Unit // pairing dialog already up - just wait
            else -> {
                if (!device.createBond()) {
                    throw Ble1810BondingException(
                        "device refused bonding - pair the blood-pressure monitor in system Bluetooth settings"
                    )
                }
            }
        }
        val bonded = withTimeoutOrNull(BOND_TIMEOUT_MS) { awaitBonded() }
        if (bonded != true) {
            throw Ble1810BondingException(
                "pairing did not complete within ${BOND_TIMEOUT_MS}ms - confirm the pairing dialog on both devices"
            )
        }
    }

    /**
     * true once BOND_BONDED is broadcast; false if pairing collapses back to BOND_NONE.
     * The receiver is unregistered on EVERY exit path - broadcast, tail bond-state
     * check and cancellation - guarded by an atomic once-flag (the
     * tefal2garmin-983 M2/m7 lesson from OmronGattConnection).
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
            ContextCompat.RECEIVER_NOT_EXPORTED
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

    private fun onIndication(uuid: UUID, value: ByteArray) {
        if (uuid != Ble1810Protocol.MEASUREMENT_CHARACTERISTIC_UUID) return // 0x2A36 ICP etc. - §2.2, out of scope
        indications.trySend(value)
    }

    private fun failPending(t: Throwable) {
        pendingWrite.fail(t)
        pendingRead.fail(t)
        connectResult?.completeExceptionally(t)
        servicesResult?.completeExceptionally(t)
    }

    /** "00002a35-..." -> "2a35" - the 16-bit short form used in logs. */
    private fun shortUuid(uuid: UUID): String = uuid.toString().substring(4, 8)
}

/** GATT status codes the glue branches on (see docs/ble-1810-protocol.md §4.2). */
private object Ble1810GattStatuses {
    /** Remote User Terminated Connection - the normal self-disconnect after a transfer. */
    const val REMOTE_USER_TERMINATED = 19
}

/**
 * Single-slot registry for the one GATT operation allowed in flight per kind
 * (write / read), with every-exit-path cleanup - the
 * [com.zamotkin.extrahealthgarmin.omron.ble.PendingWriteSlot] pattern,
 * re-implemented here so the two drivers stay independent.
 */
private class PendingOp<T> {

    private var deferred: CompletableDeferred<T>? = null
    private val lock = Any()

    /** Registers the single in-flight operation; throws IllegalStateException on double registration. */
    fun begin(): CompletableDeferred<T> = synchronized(lock) {
        check(deferred == null) { "concurrent GATT operations - serialization violated" }
        CompletableDeferred<T>().also { deferred = it }
    }

    /** Completes and clears the slot (GATT callback thread); no-op when idle. */
    fun complete(value: T) {
        val pending = synchronized(lock) { deferred.also { deferred = null } }
        pending?.complete(value)
    }

    /** Fails and clears the slot; no-op when idle. */
    fun fail(t: Throwable) {
        val pending = synchronized(lock) { deferred.also { deferred = null } }
        pending?.completeExceptionally(t)
    }

    /**
     * Registers the slot, invokes [invoke] (the actual GATT call) and awaits
     * the callback for at most [timeoutMs]. The slot is freed on EVERY exit:
     * invoke-throw, timeout, external cancellation of the awaiting coroutine.
     */
    suspend fun await(timeoutMs: Long, what: String, invoke: () -> Unit): T {
        val pending = begin()
        try {
            invoke()
        } catch (t: Throwable) {
            fail(Ble1810Exception("$what could not be started: ${t.message}"))
            throw t
        }
        try {
            return withTimeoutOrNull(timeoutMs) { pending.await() }
                ?: throw Ble1810TimeoutException(what, "$what timed out after ${timeoutMs}ms")
        } finally {
            // complete() already cleared the slot on success; every other
            // exit (timeout, cancellation) leaves it occupied - free it.
            synchronized(lock) { deferred = null }
        }
    }
}

/**
 * [Ble1810ScanTransport] over the Android BLE scanner: bonded devices with
 * their cached UUIDs (surfaced immediately by the pure scanner), then a
 * service-UUID-filtered live scan (spec §4.1 step 1 - match on the service
 * UUID, never the advertised name).
 */
@SuppressLint("MissingPermission")
class AndroidBle1810ScanTransport(context: Context) : Ble1810ScanTransport {

    private val appContext = context.applicationContext
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    private fun adapter() =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override fun bondedAdvertisements(): List<Ble1810Advertisement> {
        if (missingBlePermissions(appContext).isNotEmpty()) return emptyList()
        val adapter = adapter() ?: return emptyList()
        return adapter.bondedDevices.orEmpty().map { device ->
            Ble1810Advertisement(
                name = device.name,
                address = device.address,
                rssi = null,
                serviceUuids = device.uuids.orEmpty().map { parcelUuid -> parcelUuid.uuid }
            )
        }
    }

    override fun startScan(
        onAdvertisement: (Ble1810Advertisement) -> Unit,
        onScanFailed: (reason: String) -> Unit
    ) {
        val missing = missingBlePermissions(appContext)
        if (missing.isNotEmpty()) {
            onScanFailed("Missing permissions for Bluetooth: ${missing.joinToString()}")
            return
        }
        val scanner = adapter()?.bluetoothLeScanner
        if (scanner == null) {
            onScanFailed("BLE scanning unavailable - is Bluetooth on?")
            return
        }
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                onAdvertisement(
                    Ble1810Advertisement(
                        name = record?.deviceName ?: result.device.name,
                        address = result.device.address,
                        rssi = result.rssi,
                        serviceUuids = record?.serviceUuids.orEmpty().map { it.uuid }
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                onScanFailed("BLE scan failed with error $errorCode")
            }
        }
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(Ble1810Protocol.BLOOD_PRESSURE_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            onScanFailed("BLE scan could not start: ${e.message}")
            return
        }
        this.scanner = scanner
        this.callback = scanCallback
    }

    override fun stopScan() {
        val scanner = this.scanner ?: return
        val callback = this.callback ?: return
        try {
            scanner.stopScan(callback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan threw", e)
        }
        this.scanner = null
        this.callback = null
    }

    private companion object {
        const val TAG = "Ble1810ScanTransport"
    }
}

/**
 * Best-effort system-bond removal when a 0x1810 source is removed (spec
 * §4.2 quirk note): `removeBond()` is hidden API, reflection is the only
 * third-party path. Failure is logged and returned, never thrown - the
 * user can always unpair in system Bluetooth settings.
 */
object Ble1810Bonds {

    /**
     * @return true when the bond is gone (or never existed); false when the
     *   removal could not be performed
     */
    @SuppressLint("MissingPermission")
    fun removeBond(context: Context, address: String): Boolean {
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: return false
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) return true
        return try {
            val removeBond = device.javaClass.getMethod("removeBond")
            removeBond.invoke(device) == true
        } catch (e: Exception) {
            Log.w(TAG, "removeBond failed for $address: ${e.message}")
            false
        }
    }

    private const val TAG = "Ble1810Bonds"
}
