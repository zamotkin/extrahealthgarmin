package com.zamotkin.extrahealthgarmin.omron.protocol

import kotlin.math.pow

/**
 * IEEE 11073-20601 SFLOAT (16-bit, little-endian) decoder.
 *
 * IMPORTANT (docs/omron-protocol.md §6): the Omron EEPROM record path stores
 * measurements as plain bit-packed integers, NOT SFLOAT. This decoder exists
 * only for the standard 0x2A35 Blood Pressure Measurement characteristic and
 * is unused by default.
 *
 * Pure JVM — no Android types.
 */
object SFloatDecoder {

    /**
     * Decodes one SFLOAT from two bytes given as unsigned ints.
     * Word layout: mantissa = bits 0-11 (two's complement), exponent = bits 12-15
     * (two's complement); value = mantissa * 10^exponent.
     * The mantissa 0x800 (-2048, NaN sentinel) yields [Double.NaN] per IEEE 11073.
     */
    fun decode(lowByte: Int, highByte: Int): Double {
        val word = (highByte and 0xFF) shl 8 or (lowByte and 0xFF)
        var mantissa = word and 0x0FFF
        var exponent = word shr 12
        if (exponent >= 0x08) exponent -= 0x10
        if (mantissa >= 0x0800) mantissa -= 0x1000
        if (mantissa == -0x0800) return Double.NaN
        return mantissa * 10.0.pow(exponent)
    }
}
