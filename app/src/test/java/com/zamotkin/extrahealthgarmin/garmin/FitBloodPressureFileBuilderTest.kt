package com.zamotkin.extrahealthgarmin.garmin

import com.garmin.fit.BloodPressureMesg
import com.garmin.fit.Decoder
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Mesg
import com.garmin.fit.MesgNum
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import org.junit.Assert.assertEquals
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
