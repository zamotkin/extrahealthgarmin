package com.zamotkin.extrahealthgarmin.omron.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Persistence seam for blood-pressure records (task tefal2garmin-4uy).
 * An interface on purpose: [OmronBpRepository] and its unit tests depend on
 * it directly, so the repository is testable with a plain in-memory fake and
 * no Android framework at all.
 */
@Dao
interface BloodPressureDao {

    /**
     * Inserts every record, ignoring rows whose (timestampEpochSec, userSlot)
     * already exists (unique index on [BloodPressureRecordEntity]). Returns
     * one result per input row, in order: the new row id, or -1L for ignored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(records: List<BloodPressureRecordEntity>): List<Long>

    /** Un-synced records of one user slot, oldest first. */
    @Query(
        "SELECT * FROM blood_pressure_records " +
            "WHERE userSlot = :userSlot AND synced = 0 " +
            "ORDER BY timestampEpochSec ASC"
    )
    suspend fun unsyncedForUser(userSlot: Int): List<BloodPressureRecordEntity>

    /** Un-synced records of every user slot, oldest first. */
    @Query(
        "SELECT * FROM blood_pressure_records " +
            "WHERE synced = 0 " +
            "ORDER BY timestampEpochSec ASC"
    )
    suspend fun unsyncedAll(): List<BloodPressureRecordEntity>

    /** Marks the record with the given (timestampEpochSec, userSlot) as synced. */
    @Query(
        "UPDATE blood_pressure_records SET synced = 1 " +
            "WHERE timestampEpochSec = :timestampEpochSec AND userSlot = :userSlot"
    )
    suspend fun markSynced(timestampEpochSec: Long, userSlot: Int)

    /** Newest records of one user slot, newest first, at most [limit] rows. */
    @Query(
        "SELECT * FROM blood_pressure_records " +
            "WHERE userSlot = :userSlot " +
            "ORDER BY timestampEpochSec DESC LIMIT :limit"
    )
    suspend fun latest(userSlot: Int, limit: Int): List<BloodPressureRecordEntity>
}
