package com.zamotkin.extrahealthgarmin.devices.api

import android.content.Context

/**
 * A BLE device as discovered by a scan - what the add-source flow knows
 * about a device before a source exists for it. Pure JVM: no
 * android.bluetooth types, so the seam stays unit-testable.
 *
 * @property name advertised device name
 * @property address MAC address (the persisted handle to the device)
 * @property rssi scan signal strength, dBm
 */
data class DiscoveredBleDevice(val name: String, val address: String, val rssi: Int)

/**
 * Factory and identity of one blood-pressure source type (task
 * tefal2garmin-app-redesign-v3-xlg.2): the [SourceRegistry] resolves
 * [typeId] to the factory, and the add-source flow renders
 * [displayNameRes] and [capabilities] before creating a source.
 *
 * Two shapes, split so the flow knows what connecting means per type:
 * [BleBpSourceType] creates one source per discovered BLE device;
 * [SingletonBpSourceType] has exactly one instance whose connect step is
 * not a scan (Health Connect: a permission grant).
 */
interface BpSourceType {

    /** Registry key of the type (omron-ble, health-connect, ...). */
    val typeId: String

    /** Display name of the type (an R.string resource). */
    val displayNameRes: Int

    /** What sources of this type can do; see [SourceCapabilities]. */
    val capabilities: SourceCapabilities
}

/** A source type whose instances are discovered BLE devices. */
interface BleBpSourceType : BpSourceType {

    /** Builds the source for one discovered [device]. */
    fun createFromDevice(context: Context, device: DiscoveredBleDevice): BpSource
}

/**
 * A source type with exactly one instance - its connect step is a
 * permission grant (or similar), not a device scan.
 */
interface SingletonBpSourceType : BpSourceType {

    /** Builds the one source of this type. */
    fun createSingleton(context: Context): BpSource
}
