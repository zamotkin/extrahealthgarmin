package com.zamotkin.extrahealthgarmin.omron.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SFloatDecoder] — IEEE 11073-20601 SFLOAT (16-bit, LE).
 *
 * IMPORTANT (docs/omron-protocol.md §6): the EEPROM record path does NOT use SFLOAT —
 * these values are plain integers. This decoder exists only for the standard
 * 0x2A35 Blood Pressure Measurement characteristic and is unused by default.
 * Example vector: bytes 0x88 0xF4 -> word 0xF488 -> exponent -1, mantissa 1160 -> 116.0.
 */
class SFloatDecoderTest {

    @Test
    fun `spec_example_88_f4_decodes_to_116`() {
        // docs/omron.md SFLOAT example: systolic 116.0 mmHg
        assertEquals(116.0, SFloatDecoder.decode(0x88, 0xF4), 0.0001)
    }

    @Test
    fun `positive_exponent_scales_up`() {
        // word 0x1007: exponent 1, mantissa 7 -> 70.0
        assertEquals(70.0, SFloatDecoder.decode(0x07, 0x10), 0.0001)
    }

    @Test
    fun `negative_mantissa_decodes`() {
        // word 0x8801: exponent 0x8 = -8, mantissa 0x801 = -2047 -> -2047 * 1e-8
        assertEquals(-2047e-8, SFloatDecoder.decode(0x01, 0x88), 1e-12)
    }

    @Test
    fun `nan_mantissa_pattern_returns_nan`() {
        // IEEE 11073: mantissa 0x800 (-2048, the NaN sentinel) -> NaN regardless of exponent
        val decoded = SFloatDecoder.decode(0x00, 0x88)
        assertTrue(decoded.isNaN())
    }

    @Test
    fun `zero_decodes_to_zero`() {
        assertEquals(0.0, SFloatDecoder.decode(0x00, 0x00), 0.0)
    }
}
