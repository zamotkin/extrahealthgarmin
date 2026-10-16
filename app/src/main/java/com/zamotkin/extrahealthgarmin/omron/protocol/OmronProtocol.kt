package com.zamotkin.extrahealthgarmin.omron.protocol

/**
 * Protocol constants for the Omron M7 Intelli IT (HEM-7361T-ALRU).
 *
 * All values are transcribed from docs/omron-protocol.md — amend the spec first,
 * then this file. Constants that are still [UNVERIFIED-on-device] in the spec are
 * marked below and must not be relied on until the capture task settles them.
 *
 * Source baseline: omblepy @ f323d5ae58693cc737c81c1a1e7ff6ac0679a4664.
 * This object is pure JVM — no Android types.
 */
object OmronProtocol {

    // ------------------------------------------------------------------ //
    //  GATT — proprietary Omron data channel (spec §1.2) [SRC]           //
    // ------------------------------------------------------------------ //

    const val PARENT_SERVICE_UUID = "ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b"
    const val UNLOCK_CHARACTERISTIC_UUID = "b305b680-aee7-11e1-a730-0002a5d5c51b"

    val RX_CHANNEL_UUIDS: List<String> = listOf(
        "49123040-aee8-11e1-a74d-0002a5d5c51b",
        "4d0bf320-aee8-11e1-a0d9-0002a5d5c51b",
        "5128ce60-aee8-11e1-b84b-0002a5d5c51b",
        "560f1420-aee8-11e1-8184-0002a5d5c51b",
    )

    val TX_CHANNEL_UUIDS: List<String> = listOf(
        "db5b55e0-aee7-11e1-965e-0002a5d5c51b",
        "e0b8a060-aee7-11e1-92f4-0002a5d5c51b",
        "0ae12b00-aee8-11e1-a192-0002a5d5c51b",
        "10e1ba60-aee8-11e1-89e5-0002a5d5c51b",
    )

    // ------------------------------------------------------------------ //
    //  Unlock characteristic protocol (spec §3) [SRC]                    //
    // ------------------------------------------------------------------ //

    const val UNLOCK_OPCODE_PROGRAM_MODE = 0x02
    const val UNLOCK_OPCODE_PROGRAM_KEY = 0x00
    const val UNLOCK_OPCODE_UNLOCK = 0x01
    const val UNLOCK_PROGRAM_MODE_RESPONSE = 0x8200
    const val UNLOCK_KEY_PROGRAMMED_RESPONSE = 0x8000
    const val UNLOCK_OK_RESPONSE = 0x8100
    const val PAIRING_KEY_BYTES = 16
    const val UNLOCK_PROGRAM_MODE_MAX_ATTEMPTS = 10

    // ------------------------------------------------------------------ //
    //  Session frame types (spec §4) [SRC]                               //
    // ------------------------------------------------------------------ //

    const val TYPE_SESSION_START = 0x0000
    const val TYPE_READ = 0x0100
    const val TYPE_WRITE = 0x01c0
    const val TYPE_SESSION_END = 0x0f00

    const val RESP_SESSION_START = 0x8000
    const val RESP_READ = 0x8100
    const val RESP_WRITE = 0x81c0
    const val RESP_SESSION_END = 0x8f00

    const val READ_BLOCK_SIZE = 0x10
    const val WRITE_BLOCK_SIZE = 0x08
    const val COMMAND_MAX_RETRIES = 5
    const val COMMAND_TIMEOUT_MS = 1000L
    const val CHANNEL_CHUNK_BYTES = 16

    // ------------------------------------------------------------------ //
    //  EEPROM memory map (spec §5) [SRC]                                 //
    // ------------------------------------------------------------------ //

    const val USER1_RECORDS_ADDRESS = 0x0098
    const val USER2_RECORDS_ADDRESS = 0x06d8
    const val RECORDS_PER_USER = 100
    const val RECORD_BYTE_SIZE = 0x10
    const val SETTINGS_READ_ADDRESS = 0x0010
    const val SETTINGS_WRITE_ADDRESS = 0x0054
    const val UNREAD_COUNTER_RESET_CODE = 0x8000

    /** Offset of the unread-info section inside the settings block (spec §5). */
    const val UNREAD_SECTION_OFFSET = 0x00

    /** Size of the unread-info section: settings[0x00, 0x10) — 16 bytes. */
    const val UNREAD_SECTION_SIZE = 0x10

    /** User slot record regions, indexed by (userSlot - 1). */
    val USER_RECORD_ADDRESSES: IntArray = intArrayOf(USER1_RECORDS_ADDRESS, USER2_RECORDS_ADDRESS)

    // ------------------------------------------------------------------ //
    //  Record bit fields (spec §6) [SRC] — bit 0 = LSB of byte 0         //
    // ------------------------------------------------------------------ //

    const val BIT_MINUTE_FIRST = 68
    const val BIT_MINUTE_LAST = 73
    const val BIT_SECOND_FIRST = 74
    const val BIT_SECOND_LAST = 79
    const val BIT_MOV = 80
    const val BIT_IHB = 81
    const val BIT_MONTH_FIRST = 82
    const val BIT_MONTH_LAST = 85
    const val BIT_DAY_FIRST = 86
    const val BIT_DAY_LAST = 90
    const val BIT_HOUR_FIRST = 91
    const val BIT_HOUR_LAST = 95
    const val BIT_YEAR_FIRST = 98
    const val BIT_YEAR_LAST = 103
    const val BIT_PULSE_FIRST = 104
    const val BIT_PULSE_LAST = 111
    const val BIT_DIASTOLIC_FIRST = 112
    const val BIT_DIASTOLIC_LAST = 119
    const val BIT_SYSTOLIC_FIRST = 120
    const val BIT_SYSTOLIC_LAST = 127
    const val SYSTOLIC_OFFSET = 25
    const val YEAR_OFFSET = 2000
    const val SECOND_MAX_VALID = 59
}
