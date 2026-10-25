package com.zamotkin.extrahealthgarmin.omron.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for blood-pressure records (task tefal2garmin-4uy, epic
 * tefal2garmin-939). Version 2 (task tefal2garmin-app-redesign-v3-xlg.2)
 * adds [BloodPressureRecordEntity.sourceId] and widens the unique index to
 * (timestampEpochSec, userSlot, sourceId). Schema not exported — the schema
 * lives in code review, not in migration JSONs.
 *
 * Version 2 has NO migration path by product-owner decision (2026-10-25,
 * epic tefal2garmin-app-redesign-v3 R11): blood-pressure data is re-read
 * fresh from the sources, so legacy rows are not preserved — the database
 * builder falls back to a destructive migration instead. All Room
 * annotations stay on the entity/DAO/database; the repository depends only
 * on the [BloodPressureDao] interface, so nothing downstream needs Android
 * to compile or test.
 */
@Database(
    entities = [BloodPressureRecordEntity::class],
    version = 2,
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
