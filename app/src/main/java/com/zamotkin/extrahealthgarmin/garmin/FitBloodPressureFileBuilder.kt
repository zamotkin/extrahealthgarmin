package com.zamotkin.extrahealthgarmin.garmin

import com.garmin.fit.BloodPressureMesg
import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import java.util.Date

/**
 * Encodes Omron blood-pressure readings into a blood_pressure FIT file with
 * the official Garmin FIT SDK (`com.garmin:fit`), mirroring the
 * [FitWeightFileBuilder] wire format:
 *
 *  - one `file_id` message with type BLOOD_PRESSURE and `time_created` (UTC);
 *  - one `blood_pressure` message (FIT profile message 51) per reading.
 *    Setters and field numbers, verified against com.garmin:fit:21.176.0
 *    (uint16, scale 1 — plain mmHg/bpm integers):
 *     - timestamp (field 253);
 *     - systolic_pressure (field 0, mmHg) — `setSystolicPressure(Integer)`;
 *     - diastolic_pressure (field 1, mmHg) — `setDiastolicPressure(Integer)`;
 *     - heart_rate (field 6, bpm) — `setHeartRate(Short)`;
 *     - user_profile_index (field 9) — Omron `userSlot` - 1 (USER1 -> 0,
 *       USER2 -> 1).
 *
 * Omron's mov/ihb quality flags have no standard FIT blood_pressure field
 * and are deliberately not encoded (FIT `status` means measurement-error
 * state, not movement/irregular heartbeat — a mistranslated flag would be
 * worse than none). Device-decoded values are small non-negative integers,
 * far inside the uint16 wire range, so unlike the float weight fields no
 * pre-encoding range validation is needed. Empty input is rejected the
 * same way as [FitWeightFileBuilder]: the `time_created` lookup over an
 * empty list throws. Each record's `userSlot` must be 1 or 2 (guarded per
 * record with `require` before any encoding, task tefal2garmin-ji0): a
 * public caller must never be able to emit userProfileIndex -1 or
 * wrong-user medical-data attribution — previously this was guarded only
 * by [com.zamotkin.extrahealthgarmin.omron.protocol.OmronSession] on a
 * different code path.
 */
object FitBloodPressureFileBuilder {

    /** Returns the encoded FIT bytes. */
    fun build(records: List<BloodPressureReading>): ByteArray {
        records.forEach { record ->
            require(record.userSlot in 1..2) {
                "userSlot ${record.userSlot} outside Omron slots 1..2 — would encode " +
                    "userProfileIndex ${record.userSlot - 1} (wrong-user attribution)"
            }
        }
        val encoder = BufferEncoder()
        encoder.write(fileIdMessage(records))
        records.forEach { encoder.write(bloodPressureMessage(it)) }
        return encoder.close()
    }

    private fun fileIdMessage(records: List<BloodPressureReading>): FileIdMesg =
        FileIdMesg().apply {
            type = File.BLOOD_PRESSURE
            timeCreated = DateTime(Date(latestTimestampMs(records)))
        }

    private fun bloodPressureMessage(record: BloodPressureReading): BloodPressureMesg =
        BloodPressureMesg().apply {
            // DateTime(Date) converts Unix epoch ms -> FIT seconds (UTC).
            timestamp = DateTime(Date(record.timestampEpochSec * MILLIS_PER_SECOND))
            systolicPressure = record.systolic
            diastolicPressure = record.diastolic
            heartRate = record.pulse.toShort()
            userProfileIndex = record.userSlot - 1
        }

    /** Newest reading timestamp, used for file_id.time_created (UTC). */
    private fun latestTimestampMs(records: List<BloodPressureReading>): Long =
        (records.maxOf { it.timestampEpochSec } * MILLIS_PER_SECOND)

    private const val MILLIS_PER_SECOND = 1000L
}
