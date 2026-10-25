package com.zamotkin.extrahealthgarmin.devices.api

/**
 * The add-source flow's scan seam (task
 * tefal2garmin-app-redesign-v3-xlg.3): the live list of
 * [DiscoveredBleDevice] behind the Scan step. It lives beside the source
 * types rather than inside [BleBpSourceType] so the seam shipped by task
 * xlg.2 stays untouched while the standard 0x1810 driver (task xlg.9) is
 * developed in parallel - the flow asks for a scanner per chosen BLE type,
 * and every BLE blood-pressure monitor advertises the standard Blood
 * Pressure Service, so one scanner serves them all (see
 * [com.zamotkin.extrahealthgarmin.devices.ble.BleServiceScanner]).
 *
 * Pure JVM: implementations must not leak android.bluetooth types here.
 */
interface BleSourceScanner {

    /**
     * Starts scanning. [onDevice] fires for every advertisement (the flow
     * dedups by address); [onError] fires at most once for a scan that
     * cannot run or has failed. Implementations deliver both on the main
     * thread and drop everything after [ScanSession.stop] - the flow guards
     * late callbacks a second time by session identity.
     *
     * Never throws: a scan that cannot start reports through [onError] and
     * returns an inert session.
     */
    fun start(
        onDevice: (DiscoveredBleDevice) -> Unit,
        onError: (String) -> Unit,
    ): ScanSession

    /** One started scan; [stop] ends it and is idempotent. */
    fun interface ScanSession {
        fun stop()
    }
}
