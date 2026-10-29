package com.zamotkin.extrahealthgarmin.devices.ble1810

import java.util.UUID

/**
 * Every constant of the standard BLE Blood Pressure Service driver — the
 * [com.zamotkin.extrahealthgarmin.omron.protocol.OmronProtocol] counterpart
 * for docs/ble-1810-protocol.md (amend-the-spec-first contract: each value
 * cites its section there). All UUIDs follow the Bluetooth base UUID
 * `0000xxxx-0000-1000-8000-00805f9b34fb`.
 *
 * Pure JVM — no Android types.
 */
object Ble1810Protocol {

    /** Blood Pressure Service (spec §2). */
    val BLOOD_PRESSURE_SERVICE_UUID: UUID =
        UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")

    /** Blood Pressure Measurement — Indicate, the record payload (spec §2, §3). */
    val MEASUREMENT_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("00002a35-0000-1000-8000-00805f9b34fb")

    /** Blood Pressure Feature — Read, the feature bitmap (spec §2.1). */
    val FEATURE_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("00002a49-0000-1000-8000-00805f9b34fb")

    /** Client Characteristic Configuration Descriptor (spec §2). */
    val CCC_DESCRIPTOR_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Device Information Service — mandatory per the profile (spec §2.4). */
    val DEVICE_INFORMATION_SERVICE_UUID: UUID =
        UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")

    /** Manufacturer Name String of the Device Information Service. */
    val MANUFACTURER_NAME_UUID: UUID =
        UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")

    /** Model Number String of the Device Information Service. */
    val MODEL_NUMBER_UUID: UUID =
        UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")

    /**
     * CCCD value enabling indications: little-endian `0x0002` (spec §4.1
     * step 4 — `0x0100` would subscribe to notifications, which 0x2A35
     * does not carry).
     */
    val CCCD_ENABLE_INDICATIONS: ByteArray = byteArrayOf(0x02, 0x00)

    /**
     * SFLOAT (medfloat16) NaN word — the sentinel failed readings carry in
     * Systolic/Diastolic (spec §3.2, §4.2); raw `0x07FF`, decimal 2047.
     */
    const val SFLOAT_NAN_WORD = 0x07FF

    /** mmHg-per-kPa conversion factor (units bit, spec §3.1): 1 kPa = 7.50062 mmHg. */
    const val MMHG_PER_KPA = 7.50062

    /**
     * Window from a successful CCCD write to the first indication. Generous
     * on purpose: some monitors only push after a button press on the device
     * (spec §4.2), so this doubles as the "press the transfer button" window.
     */
    const val FIRST_MEASUREMENT_TIMEOUT_MS = 30_000L

    /** Silence between two indications after which the session ends (spec §4.1 step 6). */
    const val IDLE_TIMEOUT_MS = 10_000L
}
