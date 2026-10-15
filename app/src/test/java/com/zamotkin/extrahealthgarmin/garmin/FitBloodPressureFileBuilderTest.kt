package com.zamotkin.extrahealthgarmin.garmin

import com.garmin.fit.BloodPressureMesg
import com.garmin.fit.Decoder
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Mesg
import com.garmin.fit.MesgNum
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * FIT roundtrip tests for [FitBloodPressureFileBuilder] (task
 * tefal2garmin-l7h), decoding with the official SDK [Decoder] — same
 * pattern as FitWeightFileBuilderExtendedFieldsTest, on the raw builder
 * bytes. The FIT profile types exercised here: blood_pressure is message
 * 51 (MesgNum.BLOOD_PRESSURE) inside a file_id of type BLOOD_PRESSURE;
 * field numbers per the profile: timestamp 253, systolic_pressure 0,
 * diastolic_pressure 1, heart_rate 6, user_profile_index 9.
 *
 * Upper-bound roundtrip, mov/ihb non-encoding and the userSlot guard added
 * by task tefal2garmin-ji0.
 */
class FitBloodPressureFileBuilderTest {

    @Test
    fun `single_record_roundtrip_through_decoder`() {
        val bytes = FitBloodPressureFileBuilder.build(
            listOf(reading(systolic = 120, diastolic = 80, pulse = 62, userSlot = 1))
        )

        val mesg = decodeSingle(bytes)
        assertEquals(120, mesg.systolicPressure)
        assertEquals(80, mesg.diastolicPressure)
        assertEquals(62, mesg.heartRate?.toInt())
        assertEquals("userSlot 1 maps to zero-based userProfileIndex 0", 0, mesg.userProfileIndex)
        // FIT timestamps are whole seconds since 1989-12-31; whole-second
        // input survives the roundtrip exactly.
        assertEquals(EPOCH, mesg.timestamp.getDate().time / 1000L)
    }

    @Test
    fun `file_id_type_is_blood_pressure`() {
        val bytes = FitBloodPressureFileBuilder.build(listOf(reading()))

        assertEquals(File.BLOOD_PRESSURE, fileTypeOf(bytes))
    }

    @Test
    fun `multi_record_order_and_count_preserved`() {
        val bytes = FitBloodPressureFileBuilder.build(
            listOf(
                reading(epochSecondsUtc = EPOCH, systolic = 135, diastolic = 90, userSlot = 1),
                reading(epochSecondsUtc = EPOCH + 60, systolic = 128, diastolic = 84, userSlot = 2),
                reading(epochSecondsUtc = EPOCH + 120, systolic = 119, diastolic = 76, userSlot = 1)
            )
        )

        val mesgs = decodeAll(bytes)
        assertEquals(3, mesgs.size)
        assertEquals(listOf(EPOCH, EPOCH + 60, EPOCH + 120),
            mesgs.map { it.timestamp.getDate().time / 1000L })
        assertEquals("file order must match input order",
            listOf(135, 128, 119), mesgs.map { it.systolicPressure })
    }

    @Test
    fun `user_slot_maps_to_zero_based_profile_index`() {
        val bytes = FitBloodPressureFileBuilder.build(
            listOf(
                reading(epochSecondsUtc = EPOCH, userSlot = 1),
                reading(epochSecondsUtc = EPOCH + 60, userSlot = 2)
            )
        )

        val mesgs = decodeAll(bytes)
        assertEquals(0, mesgs[0].userProfileIndex)
        assertEquals(1, mesgs[1].userProfileIndex)
    }

    @Test
    fun `edge_values_survive_roundtrip`() {
        // sys 75 is a real low-end hypotension reading; pulse 0 is the
        // profile's uint16 lower bound.
        val bytes = FitBloodPressureFileBuilder.build(
            listOf(reading(systolic = 75, diastolic = 48, pulse = 0))
        )

        val mesg = decodeSingle(bytes)
        assertEquals(75, mesg.systolicPressure)
        assertEquals(48, mesg.diastolicPressure)
        assertEquals(0, mesg.heartRate?.toInt())
    }

    /**
     * The UPPER bounds reachable from the device decoder (task
     * tefal2garmin-ji0): systolic raw 255 + 25 = 280 and diastolic raw 255
     * are uint16 fields (FIT invalid sentinel 65535) and round-trip exactly;
     * pulse raw 255 (bits 104-111 — the max pulse BY CONSTRUCTION, safe
     * through the builder's `pulse.toShort()` narrowing) hits the uint8
     * heart_rate field's FIT invalid sentinel 0xFF: the SDK drops it and it
     * reads back as NO heart rate — verified against com.garmin:fit
     * 21.176.0. Pulse 254, the largest representable pulse, round-trips
     * exactly.
     */
    @Test
    fun `decoder_upper_bounds_survive_roundtrip`() {
        val bytes = FitBloodPressureFileBuilder.build(
            listOf(reading(systolic = 280, diastolic = 255, pulse = 254))
        )

        val mesg = decodeSingle(bytes)
        assertEquals("systolic raw 255 + 25 must decode back exactly", 280, mesg.systolicPressure)
        assertEquals(255, mesg.diastolicPressure)
        assertEquals("pulse 254 is the largest representable pulse", 254, mesg.heartRate?.toInt())

        // Pulse 255 is the uint8 invalid sentinel: FIT-correctly dropped —
        // the measurement still uploads, without a heart rate.
        val sentinel = decodeSingle(
            FitBloodPressureFileBuilder.build(listOf(reading(pulse = 255)))
        )
        assertNull("pulse 255 = 0xFF uint8 sentinel must read back as no heart rate", sentinel.heartRate)
        assertEquals("the rest of the sentinel reading survives", 120, sentinel.systolicPressure)
    }

    /**
     * mov/ihb have no standard FIT blood_pressure field and are deliberately
     * dropped (builder KDoc decision note — a mistranslated flag would be
     * worse than none): two readings identical except the flags must encode
     * to IDENTICAL bytes and decode to identical fields (task
     * tefal2garmin-ji0).
     */
    @Test
    fun `mov_ihb_flags_are_not_encoded`() {
        val clean = FitBloodPressureFileBuilder.build(
            listOf(reading(mov = false, ihb = false))
        )
        val flagged = FitBloodPressureFileBuilder.build(
            listOf(reading(mov = true, ihb = true))
        )

        assertTrue(
            "mov/ihb must not influence the encoded bytes",
            clean.contentEquals(flagged)
        )
        val cleanMesg = decodeSingle(clean)
        val flaggedMesg = decodeSingle(flagged)
        assertEquals(cleanMesg.systolicPressure, flaggedMesg.systolicPressure)
        assertEquals(cleanMesg.diastolicPressure, flaggedMesg.diastolicPressure)
        assertEquals(cleanMesg.heartRate, flaggedMesg.heartRate)
        assertEquals(cleanMesg.userProfileIndex, flaggedMesg.userProfileIndex)
        assertEquals(
            cleanMesg.timestamp.getDate().time,
            flaggedMesg.timestamp.getDate().time
        )
    }

    /**
     * A public caller must never emit userProfileIndex -1 (slot 0) or a
     * wrong-user attribution (slot 3+) — until task tefal2garmin-ji0 this was
     * guarded only by OmronSession on a different code path; the builder now
     * guards itself. Slots 1 and 2 stay encodable, zero-based 0/1.
     */
    @Test
    fun `user_slot outside 1 and 2 is rejected`() {
        listOf(0, 3).forEach { slot ->
            try {
                FitBloodPressureFileBuilder.build(listOf(reading(userSlot = slot)))
                fail("expected IllegalArgumentException for userSlot $slot")
            } catch (_: IllegalArgumentException) {
            }
        }

        val bytes = FitBloodPressureFileBuilder.build(
            listOf(
                reading(epochSecondsUtc = EPOCH, userSlot = 1),
                reading(epochSecondsUtc = EPOCH + 60, userSlot = 2)
            )
        )
        assertEquals(
            "slots 1 and 2 remain the only encodable ones, zero-based",
            listOf(0, 1),
            decodeAll(bytes).map { it.userProfileIndex }
        )
    }

    @Test
    fun `empty_input_rejected_like_weight_builder`() {
        try {
            FitBloodPressureFileBuilder.build(emptyList())
            fail("expected rejection for empty input")
        } catch (_: NoSuchElementException) {
            // Mirrors FitWeightFileBuilder: the file_id time_created lookup
            // (maxOf) over an empty list throws NoSuchElementException
            // (message-less on this Kotlin version).
        }
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    private fun reading(
        epochSecondsUtc: Long = EPOCH,
        systolic: Int = 120,
        diastolic: Int = 80,
        pulse: Int = 62,
        mov: Boolean = false,
        ihb: Boolean = false,
        userSlot: Int = 1
    ): BloodPressureReading = BloodPressureReading(
        timestampEpochSec = epochSecondsUtc,
        systolic = systolic,
        diastolic = diastolic,
        pulse = pulse,
        mov = mov,
        ihb = ihb,
        userSlot = userSlot
    )

    /** Decodes the builder output and returns every blood_pressure message. */
    private fun decodeAll(bytes: ByteArray): List<BloodPressureMesg> {
        val decoder = Decoder(bytes)
        val messages = mutableListOf<BloodPressureMesg>()
        decoder.addListener { mesg: Mesg ->
            if (mesg.num == MesgNum.BLOOD_PRESSURE) messages += BloodPressureMesg(mesg)
        }
        decoder.read()
        return messages
    }

    /** Decodes the builder output and returns the single blood_pressure message. */
    private fun decodeSingle(bytes: ByteArray): BloodPressureMesg {
        val messages = decodeAll(bytes)
        assertEquals(1, messages.size)
        return messages.single()
    }

    /** file_id type of the builder output (File.BLOOD_PRESSURE expected). */
    private fun fileTypeOf(bytes: ByteArray): File {
        val decoder = Decoder(bytes)
        val fileIds = mutableListOf<FileIdMesg>()
        decoder.addListener { mesg: Mesg ->
            if (mesg.num == MesgNum.FILE_ID) fileIds += FileIdMesg(mesg)
        }
        decoder.read()
        assertEquals(1, fileIds.size)
        return fileIds.single().type
    }

    companion object {
        /** 2025-10-09T09:46:40Z. */
        private const val EPOCH = 1_760_000_000L
    }
}
