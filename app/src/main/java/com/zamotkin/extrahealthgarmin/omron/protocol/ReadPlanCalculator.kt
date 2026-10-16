package com.zamotkin.extrahealthgarmin.omron.protocol

/**
 * Ring-buffer read-plan geometry for the Omron EEPROM record regions
 * (docs/omron-protocol.md §5.1, omblepy calcRingBufferRecordReadLocations and
 * resetUnreadRecordsCounter).
 *
 * The unread-info section is 16 bytes (spec §5: settings[0x00, 0x10)); the counters
 * live in its first 8 bytes. Per user i (little-endian device):
 * lastWrittenSlot = HIGH byte of the 16-bit field at [2i : 2i+2],
 * unread count    = HIGH byte of the 16-bit field at [2i+4 : 2i+6].
 *
 * Pure JVM — no Android types. Stateless, thread-safe.
 */
class ReadPlanCalculator {

    data class ReadCommand(val address: Int, val size: Int)

    /**
     * Computes the EEPROM reads needed to fetch exactly the unread records of [userIdx]
     * without re-reading old ones. Wrap-around rings produce two reads (head + tail).
     */
    fun planForUser(unreadInfoSection: ByteArray, userIdx: Int): List<ReadCommand> {
        require(unreadInfoSection.size >= 8) { "unread info section must be at least 8 bytes" }
        require(userIdx in 0..1) { "userIdx must be 0 or 1, is $userIdx" }

        val lastWrittenSlot = fieldHighByte(unreadInfoSection, 2 * userIdx)
        val unread = fieldHighByte(unreadInfoSection, 2 * userIdx + 4)
        val base = OmronProtocol.USER_RECORD_ADDRESSES[userIdx]
        val ring = OmronProtocol.RECORDS_PER_USER
        val recordSize = OmronProtocol.RECORD_BYTE_SIZE

        if (unread == 0) return emptyList()
        return if (lastWrittenSlot < unread) {
            // wrap-around: newest `lastWrittenSlot` records sit at the buffer head,
            // the remaining `unread - lastWrittenSlot` at the tail
            listOf(
                ReadCommand(base, recordSize * lastWrittenSlot),
                ReadCommand(base + recordSize * (ring + lastWrittenSlot - unread), recordSize * (unread - lastWrittenSlot)),
            )
        } else {
            listOf(ReadCommand(base + recordSize * (lastWrittenSlot - unread), recordSize * unread))
        }
    }

    /**
     * Returns a copy of the unread-info section (16 bytes, spec §5) with both users'
     * unread counters — bytes [4:8] — set to LE 0x8000 each ("no new records" code).
     * All other bytes, including the last-written-slot fields, are preserved verbatim.
     */
    fun resetUnreadCounters(section: ByteArray): ByteArray {
        require(section.size >= 8) { "unread info section must be at least 8 bytes" }
        val copy = section.copyOf()
        // Both users' unread fields ([4:6] and [6:8]) get the LE-encoded
        // "no new records" code — derived from the spec constant, never inline.
        val code = OmronProtocol.UNREAD_COUNTER_RESET_CODE
        val low = (code and 0xFF).toByte()
        val high = (code shr 8).toByte()
        copy[4] = low; copy[5] = high
        copy[6] = low; copy[7] = high
        return copy
    }

    /** High byte of the 16-bit little-endian field starting at [fieldOffset]. */
    private fun fieldHighByte(section: ByteArray, fieldOffset: Int): Int =
        section[fieldOffset + 1].toInt() and 0xFF
}
