package com.zamotkin.extrahealthgarmin.devices.ble1810

import kotlinx.coroutines.CancellationException

/**
 * What one [Ble1810Transport.awaitNextMeasurement] call resolved to. The
 * two non-payload variants both end the session: a 0x1810 monitor
 * disconnects itself right after its transfer burst (GATT status 19 — the
 * NORMAL end, never an error, never a reason to reconnect;
 * docs/ble-1810-protocol.md §4.2), and a silent window means the burst is
 * over (§4.1 step 6).
 */
sealed class Ble1810NextMeasurement {
    /** One 0x2A35 indication payload, exactly as received. */
    data class Indication(val payload: ByteArray) : Ble1810NextMeasurement()
    object IdleTimeout : Ble1810NextMeasurement()
    object DeviceDisconnected : Ble1810NextMeasurement()
}

/**
 * Seam between the pure session logic and the Android GATT glue — the
 * [com.zamotkin.extrahealthgarmin.omron.ble.GattChannelConnection] pattern
 * for the standard service. The production implementation
 * ([Ble1810GattConnection], the only file touching `android.bluetooth`)
 * owns connect/bond/discover, the CCCD write and the callback bridging;
 * unit tests implement it with fakes.
 *
 * Implementations must buffer indications from the moment the connection
 * is up: the profile quirk that indications may arrive BEFORE the CCCD
 * write confirms (docs/ble-1810-protocol.md §4.2) is absorbed here, so the
 * session never loses an early record.
 */
interface Ble1810Transport : AutoCloseable {

    /**
     * Connects to the stored device, completes system bonding when needed,
     * discovers services and enables indications on 0x2A35 (CCCD 0x2902 =
     * `0x0002`). Throws the typed [Ble1810Exception] hierarchy on failure.
     */
    suspend fun connectAndSubscribe()

    /**
     * The Blood Pressure Feature bitmap (0x2A49, spec §2.1) — informational
     * diagnostics; null when the device omits it or the read fails.
     */
    suspend fun readFeature(): Int?

    /**
     * A friendly description from the Device Information Service (0x180A,
     * e.g. "Beurer BM85"); null when absent — never fatal.
     */
    suspend fun readDeviceDescription(): String?

    /**
     * Suspends until the next 0x2A35 indication. Buffered indications come
     * out first even after a disconnect; [Ble1810NextMeasurement] carries
     * the two session-ending outcomes.
     */
    suspend fun awaitNextMeasurement(idleTimeoutMs: Long): Ble1810NextMeasurement
}

/**
 * Drives one read session of a standard Blood Pressure Service monitor
 * (docs/ble-1810-protocol.md §4.1): subscribe -> receive the indication
 * burst -> end on self-disconnect or idle timeout. Mirrors
 * [com.zamotkin.extrahealthgarmin.omron.protocol.OmronSession]: pure JVM,
 * connect failures propagate as exceptions for the client to fold, and
 * every frame defect is absorbed (skipped/counted), never fatal.
 *
 * Session quirks handled here (§4.2):
 *  - a burst may contain the NaN sentinel — counted in
 *    [ReadResult.skippedSentinels], never stored;
 *  - a malformed frame — counted in [ReadResult.malformedFrames], the
 *    session continues;
 *  - a byte-identical re-delivery of the previous indication — counted in
 *    [ReadResult.duplicateFrames], stored once;
 *  - records without a device clock time get the arrival second, made
 *    unique and monotonic within the pass (two records of one burst never
 *    share a read timestamp, so the storage's
 *    `(timestamp, userSlot, sourceId)` unique index keeps every record).
 */
class Ble1810Session(
    private val transport: Ble1810Transport,
    private val firstMeasurementTimeoutMs: Long = Ble1810Protocol.FIRST_MEASUREMENT_TIMEOUT_MS,
    private val idleTimeoutMs: Long = Ble1810Protocol.IDLE_TIMEOUT_MS,
    /** Seconds since the epoch — injected so the arrival stamping is testable. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) {

    enum class End { DEVICE_DISCONNECTED, IDLE_TIMEOUT }

    data class ReadResult(
        val measurements: List<Ble1810Measurement>,
        val feature: Int?,
        val deviceDescription: String?,
        val skippedSentinels: Int,
        val malformedFrames: Int,
        val duplicateFrames: Int,
        val endedBy: End
    )

    /** Runs one session; throws the typed [Ble1810Exception] hierarchy on connect/subscribe failures. */
    suspend fun read(): ReadResult {
        transport.connectAndSubscribe()
        // Diagnostics are best-effort: a monitor without 0x2A49 or 0x180A is
        // profile-broken but still transfers readings.
        val feature = bestEffort { transport.readFeature() }
        val deviceDescription = bestEffort { transport.readDeviceDescription() }

        val measurements = mutableListOf<Ble1810Measurement>()
        var skippedSentinels = 0
        var malformedFrames = 0
        var duplicateFrames = 0
        var previousPayload: ByteArray? = null
        var receivedAnything = false

        var endedBy = End.IDLE_TIMEOUT
        session@ while (true) {
            // The first-indication window is generous: some monitors push
            // only after a button press on the device (§4.2); once anything
            // arrived, the tight inter-indication window applies.
            val timeoutMs = if (receivedAnything) idleTimeoutMs else firstMeasurementTimeoutMs
            when (val next = transport.awaitNextMeasurement(timeoutMs)) {
                is Ble1810NextMeasurement.Indication -> {
                    receivedAnything = true
                    if (next.payload.contentEquals(previousPayload)) {
                        duplicateFrames++
                    } else {
                        previousPayload = next.payload.copyOf()
                        when (val parsed = Ble1810MeasurementParser.parse(next.payload)) {
                            is Ble1810ParseResult.Parsed ->
                                measurements += stampArrivalTime(parsed.measurement)
                            is Ble1810ParseResult.SkippedSentinel -> skippedSentinels++
                            is Ble1810ParseResult.Malformed -> malformedFrames++
                        }
                    }
                }
                Ble1810NextMeasurement.IdleTimeout -> {
                    endedBy = End.IDLE_TIMEOUT
                    break@session
                }
                Ble1810NextMeasurement.DeviceDisconnected -> {
                    endedBy = End.DEVICE_DISCONNECTED
                    break@session
                }
            }
        }
        return ReadResult(
            measurements = measurements,
            feature = feature,
            deviceDescription = deviceDescription,
            skippedSentinels = skippedSentinels,
            malformedFrames = malformedFrames,
            duplicateFrames = duplicateFrames,
            endedBy = endedBy
        )
    }

    /** Arrival stamp of the last clock-less record of the running pass. */
    private var lastArrivalSecond = Long.MIN_VALUE

    /**
     * Arrival-time attribution for clock-less records: the read second, and
     * when the clock stands still (or repeats) within one burst, one second
     * per record so every record keeps its own storage index slot.
     */
    private fun stampArrivalTime(measurement: Ble1810Measurement): Ble1810Measurement {
        if (measurement.timestampFromDeviceClock) return measurement
        val arrivalSecond = maxOf(clock(), lastArrivalSecond + 1)
        lastArrivalSecond = arrivalSecond
        return measurement.copy(timestampEpochSec = arrivalSecond)
    }

    /**
     * A diagnostics read that fails costs only the diagnostic — but a
     * cancelled caller still propagates (runCatching alone would swallow the
     * CancellationException and let the session run on in a dead scope).
     */
    private suspend fun <T> bestEffort(op: suspend () -> T): T? = try {
        op()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
