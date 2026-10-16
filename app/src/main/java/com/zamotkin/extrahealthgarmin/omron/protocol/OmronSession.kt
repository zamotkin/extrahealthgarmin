package com.zamotkin.extrahealthgarmin.omron.protocol

import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading

/**
 * Thrown when a session step fails (unlock rejected, unexpected response, device
 * end-status != 0). The BLE layer maps this onto OmronBleResult. Open for the
 * typed [UnlockRejectedException] subclass (task tefal2garmin-983).
 */
open class OmronSessionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The device rejected the application key at unlock (spec §3.3) — the key is
 * not registered on the device. Typed (task tefal2garmin-983) so the BLE layer
 * maps it onto OmronBleResult.AuthFailed without string-matching the message.
 */
class UnlockRejectedException(message: String) : OmronSessionException(message)

/**
 * Transport seam between the pure-JVM session logic and the Android BLE stack.
 * Implementations own: GATT serialization, 16-byte channel chunking (spec §4.5),
 * per-command retries and timeouts.
 */
interface OmronTransport {
    /** Writes a 17-byte payload to the unlock characteristic, returns the notify response. */
    suspend fun writeUnlock(payload: ByteArray): ByteArray

    /** Writes one command frame, returns the parsed response frame. */
    suspend fun writeCommand(frame: ByteArray): SessionFrameCodec.ParsedFrame
}

/**
 * Drives the complete EEPROM readout per docs/omron-protocol.md §3-§5:
 *
 * unlock -> session start -> read unread-info section @0x0010 -> ring-buffer record reads
 * -> unread-counter reset -> write-back @0x0054 -> session end (status must be 0)
 *
 * Time sync (spec §5.2) is deliberately NOT performed here — deferred to a later task.
 *
 * Pure JVM — no Android types.
 */
object OmronSession {

    // Section geometry lives in OmronProtocol (amend-the-spec-first contract).

    data class ReadOutResult(
        val readings: List<BloodPressureReading>,
        val skippedRecords: Int,
    )

    suspend fun readOut(transport: OmronTransport, key: ByteArray, userSlot: Int): ReadOutResult {
        require(userSlot == 1 || userSlot == 2) {
            "userSlot must be 1 or 2, is $userSlot — wrong slot would mix medical data between users"
        }
        val userIdx = userSlot - 1
        val calculator = ReadPlanCalculator()
        val recordDecoder = RecordDecoder()
        var sessionOpen = false
        var skipped = 0

        try {
            // 1. unlock with the application-level key (spec §3.3)
            val unlockResponse = UnlockCodec.classify(transport.writeUnlock(UnlockCodec.unlock(key)))
            if (unlockResponse != UnlockCodec.Response.Unlocked) {
                throw UnlockRejectedException("unlock rejected: $unlockResponse — key not registered on device?")
            }

            // 2. session start (spec §4.1)
            val start = transport.writeCommand(SessionFrameCodec.SESSION_START_FRAME)
            if (start.type != OmronProtocol.RESP_SESSION_START) {
                throw OmronSessionException("unexpected session start response: ${start.type.toString(16)}")
            }
            sessionOpen = true

            // 3. unread-info section @0x0010 (spec §5.1)
            val settings = transport.writeCommand(
                SessionFrameCodec.buildReadCommand(
                    OmronProtocol.SETTINGS_READ_ADDRESS + OmronProtocol.UNREAD_SECTION_OFFSET,
                    OmronProtocol.UNREAD_SECTION_SIZE,
                ),
            )
            if (settings.type != OmronProtocol.RESP_READ || settings.data.size != OmronProtocol.UNREAD_SECTION_SIZE) {
                throw OmronSessionException("unexpected settings read response: type=${settings.type.toString(16)} bytes=${settings.data.size}")
            }

            // 4. ring-buffer record reads (spec §5.1)
            val readings = mutableListOf<BloodPressureReading>()
            for (command in calculator.planForUser(settings.data, userIdx)) {
                val bytes = readContinuous(transport, command.address, command.size)
                for (offset in bytes.indices step OmronProtocol.RECORD_BYTE_SIZE) {
                    val record = bytes.copyOfRange(offset, offset + OmronProtocol.RECORD_BYTE_SIZE)
                    try {
                        val reading = recordDecoder.decode(record, userSlot)
                        if (reading != null) readings.add(reading) else skipped++
                    } catch (e: RecordFormatException) {
                        skipped++   // decision log #6: skip + log, never fatal
                    }
                }
            }

            // 5. unread-counter reset, written back @0x0054 (NOT 0x0010 — spec discrepancy #7)
            val reset = calculator.resetUnreadCounters(settings.data)
            val resetResponse = transport.writeCommand(
                SessionFrameCodec.buildWriteCommand(
                    OmronProtocol.SETTINGS_WRITE_ADDRESS + OmronProtocol.UNREAD_SECTION_OFFSET,
                    reset,
                ),
            )
            if (resetResponse.type != OmronProtocol.RESP_WRITE) {
                throw OmronSessionException("unexpected unread-reset response: ${resetResponse.type.toString(16)}")
            }

            // 6. session end + device status (spec §4.1)
            val end = transport.writeCommand(SessionFrameCodec.SESSION_END_FRAME)
            if (end.type != OmronProtocol.RESP_SESSION_END) {
                throw OmronSessionException("unexpected session end response: ${end.type.toString(16)}")
            }
            if (end.status != 0) {
                throw OmronSessionException("device reported end-status ${end.status}")
            }
            sessionOpen = false

            return ReadOutResult(readings, skipped)
        } catch (e: OmronSessionException) {
            throw e
        } catch (e: Exception) {
            throw OmronSessionException("session failed: ${e.message}", e)
        } finally {
            // never leave the device session open — a dangling session is the classic
            // cause of subsequent GATT 133 connection failures
            if (sessionOpen) {
                try {
                    val end = transport.writeCommand(SessionFrameCodec.SESSION_END_FRAME)
                    if (end.status != 0) {
                        // swallow: the original exception carries the real failure
                    }
                } catch (ignored: Exception) {
                    // swallow: preserve the original failure
                }
            }
        }
    }

    /** Reads [size] bytes in READ_BLOCK_SIZE chunks, concatenating the payloads. */
    private suspend fun readContinuous(transport: OmronTransport, address: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val block = minOf(OmronProtocol.READ_BLOCK_SIZE, size - offset)
            val response = transport.writeCommand(
                SessionFrameCodec.buildReadCommand(address + offset, block),
            )
            if (response.type != OmronProtocol.RESP_READ) {
                throw OmronSessionException("unexpected read response: ${response.type.toString(16)}")
            }
            response.data.copyInto(out, destinationOffset = offset)
            offset += block
        }
        return out
    }
}
