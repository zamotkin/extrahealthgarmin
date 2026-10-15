package com.zamotkin.extrahealthgarmin.omron.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for Omron blood-pressure records (task tefal2garmin-4uy,
 * epic tefal2garmin-939). Version 1, schema not exported — the schema lives
 * in code review, not in migration JSONs, for as long as there is exactly
 * one version. All Room annotations stay on the entity/DAO/database; the
 * repository depends only on the [BloodPressureDao] interface, so nothing
 * downstream needs Android to compile or test.
 */
@Database(
    entities = [BloodPressureRecordEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BloodPressureDatabase : RoomDatabase() {

    /** The single DAO of this database. */
    abstract fun dao(): BloodPressureDao
}
