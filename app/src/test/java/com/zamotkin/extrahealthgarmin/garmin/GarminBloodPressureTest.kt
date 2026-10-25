package com.zamotkin.extrahealthgarmin.garmin

import com.garmin.fit.BloodPressureMesg
import com.garmin.fit.Decoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Mesg
import com.garmin.fit.MesgNum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Garmin blood pressure parsing (live response shapes) and the FIT encoding. */
class GarminBloodPressureTest {

    // 2026-10-21 21:31:00 at -06:00 = 2026-10-22 03:31:00Z.
    private val utc0331 = 1792639860L
    private val mdt = -6 * 3600

    @Test
    fun `nearest heart rate picks the closest sample inside the window`() {
        val body = """{"heartRateValues":[[1000,60],[121000,65],[241000,null],[361000,80]]}"""
        assertEquals(65, GarminBloodPressureParsing.nearestHeartRate(body, 150_000, 300_000))
    }

    @Test
    fun `no heart rate when nothing is within the window or values are missing`() {
        val body = """{"heartRateValues":[[1000,60]]}"""
        assertNull(GarminBloodPressureParsing.nearestHeartRate(body, 1_000_000, 300_000))
        assertNull(GarminBloodPressureParsing.nearestHeartRate("""{"heartRateValues":null}""", 0, 300_000))
    }

    @Test
    fun `existing keys match the key of the same reading`() {
        val body = """{"measurementSummaries":[{"measurements":[{"systolic":120,"diastolic":80,
            "measurementTimestampLocal":"2026-10-21T21:31:00.0"}]}]}"""
        val reading = GarminBloodPressureReading(utc0331 + 25, mdt, 120, 80, 70)
        assertEquals(setOf(reading.key()), GarminBloodPressureParsing.existingKeys(body))
        assertEquals(emptySet<String>(), GarminBloodPressureParsing.existingKeys("""{"measurementSummaries":[]}"""))
    }

    @Test
    fun `fit file is a blood pressure file with timestamp, pressures and pulse`() {
        val bytes = FitHcBloodPressureFileBuilder.build(listOf(GarminBloodPressureReading(utc0331, mdt, 120, 80, 70)))
        val fileIds = mutableListOf<FileIdMesg>()
        val readings = mutableListOf<BloodPressureMesg>()
        Decoder(bytes).apply {
            addListener { mesg: Mesg ->
                when (mesg.num) {
                    MesgNum.FILE_ID -> fileIds += FileIdMesg(mesg)
                    MesgNum.BLOOD_PRESSURE -> readings += BloodPressureMesg(mesg)
                }
            }
            read()
        }
        assertEquals(com.garmin.fit.File.BLOOD_PRESSURE, fileIds.single().type)
        val bp = readings.single()
        assertEquals(utc0331, bp.timestamp.date.time / 1000)
        assertEquals(120, bp.systolicPressure)
        assertEquals(80, bp.diastolicPressure)
        assertEquals(70.toShort(), bp.heartRate)
    }
}
