package com.zamotkin.extrahealthgarmin.omron.ble

import com.zamotkin.extrahealthgarmin.omron.protocol.OmronProtocol

/**
 * Splits/rejoins command frames across the Omron multi-channel data path
 * (docs/omron-protocol.md §4.5): chunk number *i* travels on TX/RX channel *i*,
 * 16 bytes per channel unit. The reassembled RX stream is cut back to the
 * total length carried in byte 0 of the frame.
 *
 * Pure JVM - no Android types. Thread-safe (stateless).
 */
object ChannelChunker {

    /** Channel unit size, 16 bytes per spec §4.5. */
    const val CHUNK_SIZE: Int = OmronProtocol.CHANNEL_CHUNK_BYTES

    /**
     * Splits [frame] into [CHUNK_SIZE]-byte chunks for TX channels 0..n.
     * The frame's byte 0 must equal its actual length (session-frame invariant).
     *
     * @throws IllegalArgumentException on an empty frame or a size-byte mismatch
     */
    fun split(frame: ByteArray): List<ByteArray> {
        require(frame.isNotEmpty()) { "frame must not be empty" }
        require(frame.size == (frame[0].toInt() and 0xFF)) {
            "size byte ${frame[0].toInt() and 0xFF} != actual frame size ${frame.size}"
        }
        return frame.toList().chunked(CHUNK_SIZE) { it.toByteArray() }
    }

    /**
     * Concatenates [chunks] (in channel order) and truncates to the size byte
     * carried by the first chunk, dropping channel padding beyond it.
     *
     * @throws IllegalArgumentException on empty input or fewer bytes than the size byte claims
     */
    fun reassemble(chunks: List<ByteArray>): ByteArray {
        require(chunks.isNotEmpty()) { "chunks must not be empty" }
        val total = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(total, destinationOffset = offset)
            offset += chunk.size
        }
        val size = total[0].toInt() and 0xFF
        require(total.size >= size) {
            "reassembled ${total.size} bytes but size byte claims $size - response incomplete"
        }
        return total.copyOf(size)
    }
}
