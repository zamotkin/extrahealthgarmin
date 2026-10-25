package com.zamotkin.extrahealthgarmin.omron.ble

import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading

/**
 * Terminal outcome of one Omron M7 BLE readout ([OmronBleClient.readNewRecords]).
 * Every non-success variant carries a human-readable reason/state — raw English
 * strings for now; the UI epic will lift them into R.string resources.
 *
 * Pure JVM — no Android types.
 */
sealed class OmronBleResult {

    /** New readings transferred; an empty list means "nothing new on the device". */
    data class Success(val readings: List<BloodPressureReading>) : OmronBleResult()

    /** OS-level bonding is missing or failed — the user must pair the device. */
    data class BondingRequired(val reason: String) : OmronBleResult()

    /** The application-level key was rejected — device and app disagree on the pairing key. */
    data class AuthFailed(val reason: String) : OmronBleResult()

    /** A BLE/session step timed out; [state] names the step it stalled in. */
    data class Timeout(val state: String) : OmronBleResult()

    /** The device (or link) dropped mid-session; [state] names the step it died in. */
    data class Disconnected(val state: String) : OmronBleResult()

    /** Any other failure. */
    data class Failed(val reason: String) : OmronBleResult()
}

/** Base of the BLE-layer exception hierarchy (thrown by the GATT glue, mapped by the client). */
open class OmronBleException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A GATT operation completed with a non-success status code. */
class GattStatusException(val status: Int, what: String) : OmronBleException("$what failed with GATT status $status")

/** A BLE step exceeded its deadline; [state] is a short human-readable name of the step. */
class GattTimeoutException(val state: String, message: String) : OmronBleException(message)

/** The link went down mid-operation; [state] is a short human-readable name of the step. */
class GattDisconnectedException(val state: String, message: String) : OmronBleException(message)

/** Bonding is required but could not be established automatically (pairing refused/failed). */
class BondingRequiredException(message: String) : OmronBleException(message)
