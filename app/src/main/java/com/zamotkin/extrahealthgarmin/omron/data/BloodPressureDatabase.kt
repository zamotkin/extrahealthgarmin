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

    companion object {
        /**
         * Database file name (databases/ dir). Wiring must use this constant so
         * the backup-exclusion rules (res/xml/backup_rules.xml,
         * res/xml/data_extraction_rules.xml — task tefal2garmin-lnm) match the
         * actual file: medical rows stay out of cloud backup by design.
         */
        const val DATABASE_NAME = "blood_pressure.db"
    }
}
