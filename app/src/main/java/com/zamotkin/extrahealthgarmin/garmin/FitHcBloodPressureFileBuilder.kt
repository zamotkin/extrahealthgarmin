package com.zamotkin.extrahealthgarmin.garmin

import com.garmin.fit.BloodPressureMesg
import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import java.util.Date

/**
 * Encodes blood pressure readings into a FIT file of type BLOOD_PRESSURE —
 * the format Garmin's own Index BPM cuff syncs, accepted by the same
 * `/upload-service/upload` endpoint as weight. (The JSON
 * `/bloodpressure-service/bloodpressure` POST answers 403 for this client.)
 *
 * One `file_id` plus one `blood_pressure` message per reading: timestamp
 * (UTC, like weight), systolic_pressure, diastolic_pressure, heart_rate.
 */
object FitHcBloodPressureFileBuilder {

    fun build(readings: List<GarminBloodPressureReading>): ByteArray {
        val encoder = BufferEncoder()
        encoder.write(FileIdMesg().apply {
            type = File.BLOOD_PRESSURE
            timeCreated = fitTime(readings.maxOf { it.epochSeconds })
        })
        readings.forEach { reading ->
            encoder.write(BloodPressureMesg().apply {
                timestamp = fitTime(reading.epochSeconds)
                systolicPressure = reading.systolic
                diastolicPressure = reading.diastolic
                heartRate = reading.pulse.toShort()
            })
        }
        return encoder.close()
    }

    private fun fitTime(epochSeconds: Long) = DateTime(Date(epochSeconds * 1000L))
}
