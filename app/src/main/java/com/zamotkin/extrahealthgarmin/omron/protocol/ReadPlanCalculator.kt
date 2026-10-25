package com.zamotkin.extrahealthgarmin.omron.protocol

/**
 * Ring-buffer read-plan geometry for the Omron EEPROM record regions
 * (docs/omron-protocol.md §5.1, omblepy calcRingBufferRecordReadLocations and
 * resetUnreadRecordsCounter).
 *
 * The unread-info section is 16 bytes (spec §5: settings[0x00, 0x10)); the counters
 * live in its first 8 bytes. Per user i, in omblepy bit numbering (bit k = integer bit 127-k, same
 * as the record decoder): lastWrittenSlot = the FIRST byte of the pair
 * [2i : 2i+2]; unread count = the FIRST byte of [2i+4 : 2i+6]. Empirically
 * settled on the real HEM-7361T-ALRU (2026-10-25 capture): unread reads
 * back 01 00 after one new measurement and 00 80 after the 0x8000 reset -
 * the 0x80 lives in the SECOND byte and must be ignored (reading it as the
 * count yields the bogus 128 and plans a full-ring mega-read).
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

        val lastWrittenSlot = fieldCounterByte(unreadInfoSection, 2 * userIdx)
        val unread = fieldCounterByte(unreadInfoSection, 2 * userIdx + 4)
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

    /**
     * Counter byte of the 16-bit little-endian field starting at [fieldOffset]:
     * omblepy reads bits 8-15 of the LE value — the FIRST byte of the pair.
     */
    private fun fieldCounterByte(section: ByteArray, fieldOffset: Int): Int =
        section[fieldOffset].toInt() and 0xFF
}
