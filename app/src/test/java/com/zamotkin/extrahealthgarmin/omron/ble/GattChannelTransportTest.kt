package com.zamotkin.extrahealthgarmin.omron.ble

import com.zamotkin.extrahealthgarmin.omron.protocol.OmronProtocol
import com.zamotkin.extrahealthgarmin.omron.protocol.OmronSessionException
import com.zamotkin.extrahealthgarmin.omron.protocol.SessionFrameCodec
import com.zamotkin.extrahealthgarmin.omron.protocol.UnlockCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [GattChannelTransport] over a scripted [GattChannelConnection] fake.
 * The fake mirrors the device: it answers a completed command by notifying the
 * scripted response split into 16-byte units across the RX channels (spec §4.5).
 */
class GattChannelTransportTest {

    private val key = ByteArray(16) { 0x5A }

    /** Channel-indexed TX writes, in order. */
    private val FakeConnection.txWrites: List<Pair<Int, ByteArray>>
        get() = txWritesRaw

    /**
     * Scriptable connection. Each completed command (all chunks written, as counted
     * against the frame's size byte) consumes one entry of [commandResponses];
     * each unlock write consumes one entry of [unlockResponses].
     * `null` script entries and an exhausted queue mean: no response (timeout).
     */
    private class FakeConnection(
        val commandResponses: ArrayDeque<ByteArray?>,
        val unlockResponses: ArrayDeque<ByteArray?>,
    ) : GattChannelConnection {
        val txWritesRaw = mutableListOf<Pair<Int, ByteArray>>()
        val unlockWrites = mutableListOf<ByteArray>()
        var enableNotificationsCalls = 0
        var handler: ((uuid: String, bytes: ByteArray) -> Unit)? = null
        private var commandBytesSeen = 0
        private var commandTotal = -1

        /** When set, one zero-length unit is delivered on RX 0 before the next response. */
        var emitEmptyUnitBeforeResponse = false

        override suspend fun enableNotifications() {
            enableNotificationsCalls++
        }

        override suspend fun writeCharacteristic(uuid: String, payload: ByteArray): Boolean {
            if (uuid == OmronProtocol.UNLOCK_CHARACTERISTIC_UUID) {
                unlockWrites.add(payload.copyOf())
                deliverUnlock(unlockResponses.removeFirstOrNull())
                return true
            }
            val channelIndex = OmronProtocol.TX_CHANNEL_UUIDS.indexOf(uuid)
            if (channelIndex < 0) return false
            txWritesRaw.add(channelIndex to payload.copyOf())
            if (commandTotal < 0) commandTotal = payload[0].toInt() and 0xFF
            commandBytesSeen += payload.size
            if (commandBytesSeen >= commandTotal) {
                deliverOnRxChannels(commandResponses.removeFirstOrNull())
                commandBytesSeen = 0
                commandTotal = -1
            }
            return true
        }

        override fun onChannelData(handler: (uuid: String, bytes: ByteArray) -> Unit) {
            this.handler = handler
        }

        private fun deliverOnRxChannels(response: ByteArray?) {
            if (response == null) return
            if (emitEmptyUnitBeforeResponse) {
                emitEmptyUnitBeforeResponse = false
                handler?.invoke(OmronProtocol.RX_CHANNEL_UUIDS[0], ByteArray(0))
            }
            val units = response.toList().chunked(OmronProtocol.CHANNEL_CHUNK_BYTES) { it.toByteArray() }
            units.forEachIndexed { index, unit ->
                handler?.invoke(OmronProtocol.RX_CHANNEL_UUIDS[index], unit)
            }
        }

        private fun deliverUnlock(response: ByteArray?) {
            if (response != null) handler?.invoke(OmronProtocol.UNLOCK_CHARACTERISTIC_UUID, response)
        }
    }

    /** Builds a valid data-carrying response frame of the given type/address (CRC fixed). */
    private fun responseFrame(type: Int, address: Int, data: ByteArray): ByteArray {
        val frame = SessionFrameCodec.buildWriteCommand(address, data)
        frame[1] = (type shr 8).toByte()
        frame[2] = type.toByte()
        var xor = 0
        for (i in 0 until frame.size - 1) xor = xor xor (frame[i].toInt() and 0xFF)
        frame[frame.size - 1] = xor.toByte()
        return frame
    }

    /** Flips the CRC byte — whole-frame XOR becomes non-zero -> FrameCrcException on parse. */
    private fun corrupt(frame: ByteArray): ByteArray = frame.copyOf().also {
        it[it.size - 1] = (it[it.size - 1].toInt() xor 0x55).toByte()
    }

    @Test
    fun `write command splits into channel chunks and reassembles the rx response`() = runTest {
        // 24-byte write command (16 data bytes) -> 2 chunks on TX channels 0 and 1
        val command = SessionFrameCodec.buildWriteCommand(0x0054, ByteArray(16))
        val response = responseFrame(OmronProtocol.RESP_WRITE, 0x0054, ByteArray(16))
        val fake = FakeConnection(
            commandResponses = ArrayDeque(listOf(response)),
            unlockResponses = ArrayDeque(),
        )
        val transport = GattChannelTransport(fake)

        val parsed = transport.writeCommand(command)

        assertEquals(OmronProtocol.RESP_WRITE, parsed.type)
        assertEquals(0x0054, parsed.address)
        assertEquals(16, parsed.data.size)

        // command went out as chunk i on TX channel i (spec §4.5)
        assertEquals(2, fake.txWrites.size)
        assertEquals(0, fake.txWrites[0].first)
        assertEquals(16, fake.txWrites[0].second.size)
        assertArrayEquals(command.copyOfRange(0, 16), fake.txWrites[0].second)
        assertEquals(1, fake.txWrites[1].first)
        assertArrayEquals(command.copyOfRange(16, 24), fake.txWrites[1].second)
    }

    @Test
    fun `single chunk command works without channel one`() = runTest {
        val command = SessionFrameCodec.SESSION_START_FRAME   // 8 bytes -> 1 chunk
        val response = responseFrame(OmronProtocol.RESP_SESSION_START, 0x0000, ByteArray(0))
        val fake = FakeConnection(
            commandResponses = ArrayDeque(listOf(response)),
            unlockResponses = ArrayDeque(),
        )
        val transport = GattChannelTransport(fake)

        val parsed = transport.writeCommand(command.copyOf())

        assertEquals(OmronProtocol.RESP_SESSION_START, parsed.type)
        assertEquals(1, fake.txWrites.size)
        assertEquals(0, fake.txWrites[0].first)
    }

    @Test
    fun `crc corrupt response is retried and then succeeds`() = runTest {
        val command = SessionFrameCodec.SESSION_START_FRAME
        val good = responseFrame(OmronProtocol.RESP_SESSION_START, 0x0000, ByteArray(0))
        val fake = FakeConnection(
            commandResponses = ArrayDeque(listOf(corrupt(good), good)),
            unlockResponses = ArrayDeque(),
        )
        val transport = GattChannelTransport(fake)

        val parsed = transport.writeCommand(command.copyOf())

        assertEquals(OmronProtocol.RESP_SESSION_START, parsed.type)
        // two full command attempts
        assertEquals(2, fake.txWrites.size)
    }

    @Test
    fun `five missing responses exhaust retries and throw`() = runTest {
        val command = SessionFrameCodec.SESSION_START_FRAME
        val fake = FakeConnection(
            commandResponses = ArrayDeque(listOf<ByteArray?>()),   // device stays silent
            unlockResponses = ArrayDeque(),
        )
        val transport = GattChannelTransport(fake)

        try {
            transport.writeCommand(command.copyOf())
            fail("expected OmronSessionException")
        } catch (expected: OmronSessionException) {
            // 5 attempts x 1 chunk each
            assertEquals(OmronProtocol.COMMAND_MAX_RETRIES, fake.txWrites.size)
        }
    }

    @Test
    fun `write unlock sends the payload and returns the notify`() = runTest {
        val fake = FakeConnection(
            commandResponses = ArrayDeque(),
            unlockResponses = ArrayDeque(listOf(byteArrayOf(0x81.toByte(), 0x00))),
        )
        val transport = GattChannelTransport(fake)

        val response = transport.writeUnlock(UnlockCodec.unlock(key))

        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x00), response)
        assertEquals(1, fake.unlockWrites.size)
        assertArrayEquals(UnlockCodec.unlock(key), fake.unlockWrites[0])
    }

    @Test
    fun `initialize enables notifications exactly once`() = runTest {
        val fake = FakeConnection(
            commandResponses = ArrayDeque(),
            unlockResponses = ArrayDeque(),
        )
        val transport = GattChannelTransport(fake)

        transport.initialize()
        transport.initialize()

        assertEquals(1, fake.enableNotificationsCalls)
    }

    @Test
    fun `frame needing more channels than exist is rejected`() = runTest {
        val fake = FakeConnection(
            commandResponses = ArrayDeque(),
            unlockResponses = ArrayDeque(),
        )
        val transport = GattChannelTransport(fake)
        // 80-byte write command -> 5 chunks, only 4 TX channels
        val tooBig = SessionFrameCodec.buildWriteCommand(0x0010, ByteArray(72))

        try {
            transport.writeCommand(tooBig)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("channels"))
        }
    }

    // ------------------------------------------------------------------ //
    //  task tefal2garmin-983 remediation tests                            //
    // ------------------------------------------------------------------ //

    @Test
    fun `zero-length rx notification is skipped and the command still succeeds`() = runTest {
        val command = SessionFrameCodec.SESSION_START_FRAME
        val response = responseFrame(OmronProtocol.RESP_SESSION_START, 0x0000, ByteArray(0))
        val fake = FakeConnection(
            commandResponses = ArrayDeque(listOf(response)),
            unlockResponses = ArrayDeque(),
        )
        fake.emitEmptyUnitBeforeResponse = true   // i8a: empty unit ahead of the real first unit
        val transport = GattChannelTransport(fake)

        val parsed = transport.writeCommand(command.copyOf())

        assertEquals(OmronProtocol.RESP_SESSION_START, parsed.type)
    }

    @Test
    fun `malformed framing is retried and then succeeds`() = runTest {
        val command = SessionFrameCodec.SESSION_START_FRAME
        val good = responseFrame(OmronProtocol.RESP_SESSION_START, 0x0000, ByteArray(0))
        // i8b: size byte claims one byte more than carried -> parseFrame's
        // require() fires (IllegalArgumentException) before any CRC check
        val malformed = good.copyOf().also { it[0] = (it.size + 1).toByte() }
        val fake = FakeConnection(
            commandResponses = ArrayDeque(listOf(malformed, good)),
            unlockResponses = ArrayDeque(),
        )
        val transport = GattChannelTransport(fake)

        val parsed = transport.writeCommand(command.copyOf())

        assertEquals(OmronProtocol.RESP_SESSION_START, parsed.type)
        assertEquals(2, fake.txWrites.size)   // malformed attempt + retry
    }

    /**
     * M1 through the transport seam: the fake mirrors OmronGattConnection's
     * write serialization via the real [PendingWriteSlot]. Attempt 1 hangs
     * (write accepted, callback never arrives); RetryCommandRunner cancels it
     * at its 1 s attempt timeout. Attempt 2 must NOT be rejected by the
     * "concurrent GATT writes" guard left stale by the cancellation.
     */
    @Test
    fun `retry after a cancelled hung write does not hit the serialization guard`() = runTest {
        val response = responseFrame(OmronProtocol.RESP_SESSION_START, 0x0000, ByteArray(0))
        val fake = SlotEmulatingConnection(ArrayDeque(listOf(response)))
        val transport = GattChannelTransport(fake)

        val parsed = transport.writeCommand(SessionFrameCodec.SESSION_START_FRAME.copyOf())

        assertEquals(OmronProtocol.RESP_SESSION_START, parsed.type)
        assertEquals(2, fake.writes)   // the hung attempt + the successful retry
    }

    /** Faithful mirror of OmronGattConnection's write path: one write at a time via [PendingWriteSlot]. */
    private class SlotEmulatingConnection(
        val commandResponses: ArrayDeque<ByteArray?>,
    ) : GattChannelConnection {
        val slot = PendingWriteSlot(timeoutMs = 60_000)   // the RETRY timeout must win, not the slot's own
        var writes = 0
        var handler: ((uuid: String, bytes: ByteArray) -> Unit)? = null

        override suspend fun enableNotifications() { /* not under test */ }

        override suspend fun writeCharacteristic(uuid: String, payload: ByteArray): Boolean {
            val status = slot.awaitStatus("write test") {
                writes++
                if (writes == 1) {
                    // first attempt: GATT accepts the write but the device never responds
                } else {
                    slot.complete(0)
                    deliver(commandResponses.removeFirstOrNull())
                }
            }
            return status == 0
        }

        override fun onChannelData(handler: (uuid: String, bytes: ByteArray) -> Unit) {
            this.handler = handler
        }

        private fun deliver(response: ByteArray?) {
            if (response == null) return
            val units = response.toList().chunked(OmronProtocol.CHANNEL_CHUNK_BYTES) { it.toByteArray() }
            units.forEachIndexed { index, unit ->
                handler?.invoke(OmronProtocol.RX_CHANNEL_UUIDS[index], unit)
            }
        }
    }
}
