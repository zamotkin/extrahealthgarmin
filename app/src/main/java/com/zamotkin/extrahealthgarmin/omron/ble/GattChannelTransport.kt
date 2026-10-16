package com.zamotkin.extrahealthgarmin.omron.ble

import com.zamotkin.extrahealthgarmin.omron.protocol.FrameCrcException
import com.zamotkin.extrahealthgarmin.omron.protocol.OmronProtocol
import com.zamotkin.extrahealthgarmin.omron.protocol.OmronTransport
import com.zamotkin.extrahealthgarmin.omron.protocol.SessionFrameCodec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Seam between the pure-JVM transport logic and the Android GATT glue.
 * Implementations (production: [OmronGattConnection]; tests: fakes) bridge
 * characteristic operations to `android.bluetooth` while this file stays
 * Android-free.
 */
interface GattChannelConnection {
    /** Enables notifications/indications on all RX channels and the unlock characteristic. */
    suspend fun enableNotifications()

    /**
     * Writes [payload] to the characteristic with [uuid] (write-with-response).
     * @return false if the write was rejected; throws on hard GATT failures
     */
    suspend fun writeCharacteristic(uuid: String, payload: ByteArray): Boolean

    /** Installs the sink for every value notification/indication. */
    fun onChannelData(handler: (uuid: String, bytes: ByteArray) -> Unit)
}

/**
 * [OmronTransport] over a [GattChannelConnection]: splits command frames into
 * 16-byte channel chunks (spec §4.5), writes chunk i to TX channel i, collects
 * the response units from the RX channels, reassembles and parses them.
 * Unlock writes go to the unlock characteristic whole (17 bytes, spec §3.3).
 *
 * Every operation - unlock, command, initialization - runs under one mutex:
 * the next GATT operation starts only after the previous one fully completed
 * (epic immutable requirement, decision log #3). Per-command retries and the
 * 1 s response timeout live in [RetryCommandRunner]; a CRC-corrupt response
 * maps to a null attempt so the whole command is retried.
 *
 * Pure JVM - no Android types.
 */
class GattChannelTransport(
    private val connection: GattChannelConnection,
    private val retryRunner: RetryCommandRunner = RetryCommandRunner(),
) : OmronTransport {

    private data class Notification(val uuid: String, val bytes: ByteArray)

    private val gattMutex = Mutex()
    private val notifications = Channel<Notification>(Channel.UNLIMITED)
    private var notificationsEnabled = false

    init {
        connection.onChannelData { uuid, bytes -> notifications.trySend(Notification(uuid, bytes)) }
    }

    /** Enables the CCCDs once; must run before the first unlock/command. Idempotent. */
    suspend fun initialize() = gattMutex.withLock {
        if (!notificationsEnabled) {
            connection.enableNotifications()
            notificationsEnabled = true
        }
    }

    override suspend fun writeUnlock(payload: ByteArray): ByteArray = gattMutex.withLock {
        retryRunner.run("unlock") {
            drainNotifications()
            if (!connection.writeCharacteristic(OmronProtocol.UNLOCK_CHARACTERISTIC_UUID, payload)) {
                return@run null
            }
            awaitNotificationOn(OmronProtocol.UNLOCK_CHARACTERISTIC_UUID)
        }
    }

    override suspend fun writeCommand(frame: ByteArray): SessionFrameCodec.ParsedFrame = gattMutex.withLock {
        val chunks = ChannelChunker.split(frame)
        require(chunks.size <= OmronProtocol.TX_CHANNEL_UUIDS.size) {
            "frame of ${frame.size} bytes needs ${chunks.size} channels, " +
                "only ${OmronProtocol.TX_CHANNEL_UUIDS.size} TX channels exist"
        }
        retryRunner.run("command 0x${frameTypeOf(frame).toString(16)}") {
            drainNotifications()
            for ((index, chunk) in chunks.withIndex()) {
                if (!connection.writeCharacteristic(OmronProtocol.TX_CHANNEL_UUIDS[index], chunk)) {
                    return@run null
                }
            }
            val reassembled = awaitResponseChunks() ?: return@run null
            try {
                SessionFrameCodec.parseFrame(reassembled)
            } catch (e: FrameCrcException) {
                null   // corrupt response - retry the whole command
            } catch (e: IllegalArgumentException) {
                null   // malformed framing - retryable, i8b
            } catch (e: IndexOutOfBoundsException) {
                null   // garbled reassembly - retryable, i8b
            }
        }
    }

    /**
     * Suspends until a notification for [uuid] arrives; other notifications
     * (e.g. a late RX unit) are dropped as stale. Cancellation (response
     * timeout) propagates from the retry runner.
     */
    private suspend fun awaitNotificationOn(uuid: String): ByteArray? {
        while (true) {
            val notification = notifications.receive()
            if (notification.uuid == uuid) return notification.bytes
        }
    }

    /**
     * Collects RX channel units until the channels starting at RX 0 carry at
     * least as many bytes as the frame size byte announces, then reassembles
     * them in channel order. Zero-length units are skipped: an empty first
     * unit would blow up on the size-byte read below (task tefal2garmin-983, i8a).
     */
    private suspend fun awaitResponseChunks(): ByteArray? {
        val unitsByChannel = HashMap<Int, ByteArray>()
        while (true) {
            val notification = notifications.receive()
            val channelIndex = OmronProtocol.RX_CHANNEL_UUIDS.indexOf(notification.uuid)
            if (channelIndex < 0) continue
            if (notification.bytes.isEmpty()) continue
            unitsByChannel.putIfAbsent(channelIndex, notification.bytes)

            val first = unitsByChannel[0] ?: continue
            val expectedSize = first[0].toInt() and 0xFF
            val orderedUnits = ArrayList<ByteArray>()
            var index = 0
            while (unitsByChannel.containsKey(index)) {
                orderedUnits.add(unitsByChannel[index]!!)
                index++
            }
            if (orderedUnits.sumOf { it.size } >= expectedSize) {
                return ChannelChunker.reassemble(orderedUnits)
            }
        }
    }

    /**
     * Drops notifications left over from a previous (timed-out) command cycle.
     *
     * Known bounded race, accepted (task tefal2garmin-983, i9): a late RX unit
     * that belonged to the timed-out command but arrives AFTER this drain is
     * consumed by the next attempt's [awaitResponseChunks] and can poison its
     * reassembly via the channel-order putIfAbsent. The blast radius is capped:
     * a poisoned attempt only wastes one command, the retry budget then
     * exhausts, OmronSession's finally-end is already best-effort, and
     * [OmronBleClient] closes the GATT connection immediately on every failure
     * path, so no further command shares the channel. A generation counter
     * would not close the window (units are stamped at arrival, not at
     * transmission), hence documented rather than guarded.
     */
    private fun drainNotifications() {
        while (notifications.tryReceive().isSuccess) { /* drop */ }
    }

    private fun frameTypeOf(frame: ByteArray): Int =
        (frame[1].toInt() and 0xFF shl 8) or (frame[2].toInt() and 0xFF)
}
