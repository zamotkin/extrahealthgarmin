package com.zamotkin.extrahealthgarmin.omron

/**
 * One decoded blood-pressure reading from the Omron M7 Intelli IT EEPROM.
 * Pure JVM domain record — no Android types.
 *
 * @property timestampEpochSec measurement time, seconds since Unix epoch (UTC)
 * @property systolic systolic pressure, mmHg (device raw value + 25 already applied)
 * @property diastolic diastolic pressure, mmHg
 * @property pulse heart rate, beats per minute
 * @property mov movement-during-measurement flag
 * @property ihb irregular-heartbeat flag
 * @property userSlot Omron user slot: 1 or 2
 */
data class BloodPressureReading(
    val timestampEpochSec: Long,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    val mov: Boolean,
    val ihb: Boolean,
    val userSlot: Int,
)
