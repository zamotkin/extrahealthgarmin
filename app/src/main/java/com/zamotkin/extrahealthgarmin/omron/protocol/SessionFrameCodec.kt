package com.zamotkin.extrahealthgarmin.omron.protocol

/**
 * Thrown when a received frame violates the whole-frame XOR == 0 invariant
 * (docs/omron-protocol.md §4.2). The BLE layer maps this onto a command retry.
 */
class FrameCrcException(message: String) : Exception(message)

/**
 * Builder/parser for the Omron proprietary session frames (spec §4).
 *
 * Frame layout (N = total size):
 * ```
 * byte 0      : total frame size N (header 6 + data + crc 2)
 * bytes 1-2   : type, big-endian (0000/0100/01c0/0f00 requests; 8000/8100/81c0/8f00 responses)
 * bytes 3-4   : EEPROM address, big-endian
 * byte  5     : data byte count M
 * bytes 6..   : M data bytes
 * bytes N-2..N: 0x00 pad, then CRC = XOR of all preceding bytes (whole-frame XOR == 0)
 * ```
 *
 * Per-frame variants (omblepy reference, spec §4.1/§4.3/§4.4):
 * - session start is a FIXED 8-byte frame whose count field carries magic 0x10 (not data)
 * - read command: count field IS the read size; no data bytes (8 bytes total)
 * - write command: count = data size, N = 6 + M + 2
 *
 * Pure JVM — no Android types. Thread-safe (stateless).
 */
object SessionFrameCodec {

    /** Session start, spec §4.1 vector `08 00 00 00 00 10 00 18` (count field = magic 0x10). */
    val SESSION_START_FRAME: ByteArray =
        byteArrayOf(0x08, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x18)

    /** Session end, spec §4.1 vector `08 0f 00 00 00 00 00 07`. */
    val SESSION_END_FRAME: ByteArray =
        byteArrayOf(0x08, 0x0f.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x07)

    data class ParsedFrame(
        val size: Int,
        val type: Int,
        val address: Int,
        val dataLength: Int,
        val data: ByteArray,
        /** Device status byte, present only for RESP_SESSION_END (8f00) frames, else null. */
        val status: Int?,
    )

    /** Read request: `08 01 00 <addrHi> <addrLo> <size> 00 <xor-crc>` (spec §4.3). */
    fun buildReadCommand(address: Int, size: Int): ByteArray {
        require(address in 0x0000..0xFFFF) { "address out of range: $address" }
        require(size in 0x00..0xFF) { "size out of range: $size" }
        val frame = ByteArray(8)
        frame[0] = 0x08
        frame[1] = (OmronProtocol.TYPE_READ shr 8).toByte()
        frame[2] = OmronProtocol.TYPE_READ.toByte()
        frame[3] = (address shr 8).toByte()
        frame[4] = address.toByte()
        frame[5] = size.toByte()
        frame[6] = 0x00
        frame[7] = xorOf(frame, 7).toByte()
        return frame
    }

    /** Write command: size = M + 8, type 01c0, count = M, data, 0x00 pad, XOR CRC (spec §4.4). */
    fun buildWriteCommand(address: Int, data: ByteArray): ByteArray {
        require(address in 0x0000..0xFFFF) { "address out of range: $address" }
        val size = data.size + 8
        require(size <= 0xFF) { "frame too large: $size" }

        val frame = ByteArray(size)
        frame[0] = size.toByte()
        frame[1] = (OmronProtocol.TYPE_WRITE shr 8).toByte()
        frame[2] = OmronProtocol.TYPE_WRITE.toByte()
        frame[3] = (address shr 8).toByte()
        frame[4] = address.toByte()
        frame[5] = data.size.toByte()
        data.copyInto(frame, destinationOffset = 6)
        frame[size - 2] = 0x00
        frame[size - 1] = xorOf(frame, size - 1).toByte()
        return frame
    }

    /**
     * Parses and validates a received frame. Throws [FrameCrcException] on CRC mismatch
     * and [IllegalArgumentException] on malformed framing.
     *
     * Corruption-tolerant data handling per the reference implementation: if [dataLength]
     * claims more bytes than the frame carries, the missing tail is padded with 0xFF.
     */
    fun parseFrame(bytes: ByteArray): ParsedFrame {
        require(bytes.size >= 8) { "frame too short: ${bytes.size}" }
        val size = bytes[0].toInt() and 0xFF
        require(size == bytes.size) { "size byte $size != actual ${bytes.size}" }

        var xor = 0
        for (b in bytes) xor = xor xor (b.toInt() and 0xFF)
        if (xor != 0) throw FrameCrcException("whole-frame XOR = $xor, expected 0")

        val type = (bytes[1].toInt() and 0xFF) shl 8 or (bytes[2].toInt() and 0xFF)
        val address = (bytes[3].toInt() and 0xFF) shl 8 or (bytes[4].toInt() and 0xFF)
        val dataLength = bytes[5].toInt() and 0xFF
        val data = ByteArray(dataLength) { idx ->
            val src = 6 + idx
            if (src < size - 2) bytes[src] else 0xFF.toByte()
        }
        val status = if (type == OmronProtocol.RESP_SESSION_END && dataLength >= 1) {
            data[0].toInt() and 0xFF
        } else {
            null
        }
        return ParsedFrame(size, type, address, dataLength, data, status)
    }

    private fun xorOf(bytes: ByteArray, count: Int): Int {
        var xor = 0
        for (i in 0 until count) xor = xor xor (bytes[i].toInt() and 0xFF)
        return xor
    }
}
