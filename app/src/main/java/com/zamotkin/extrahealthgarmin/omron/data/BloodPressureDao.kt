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
     * Inserts every record, ignoring rows whose (timestampEpochSec, userSlot,
     * sourceId) already exists (unique index on [BloodPressureRecordEntity]).
     * Returns one result per input row, in order: the new row id, or -1L for
     * ignored.
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

    /**
     * Marks the records with the given (timestampEpochSec, userSlot) as
     * synced — every source's row with that key, mirroring the composite key
     * of [com.zamotkin.extrahealthgarmin.garmin.BpUploadJournal]: once Garmin
     * holds one reading of that (timestamp, userSlot), no source's copy of
     * it can upload again, so all of them are done.
     */
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

    /** Every stored record of one user slot, newest first (no row limit). */
    @Query(
        "SELECT * FROM blood_pressure_records " +
            "WHERE userSlot = :userSlot " +
            "ORDER BY timestampEpochSec DESC"
    )
    suspend fun allForUser(userSlot: Int): List<BloodPressureRecordEntity>

    /**
     * Every stored record of one source, newest first (no row limit) — of
     * one user slot of it when [userSlot] is given (the epic-R4 display
     * filter: pre-existing rows of other slots stay invisible once a source
     * has its picked user), of every slot otherwise (the
     * no-user-attribution shape). Task tefal2garmin-app-redesign-v3-xlg.4.
     */
    @Query(
        "SELECT * FROM blood_pressure_records " +
            "WHERE sourceId = :sourceId " +
            "AND (:userSlot IS NULL OR userSlot = :userSlot) " +
            "ORDER BY timestampEpochSec DESC"
    )
    suspend fun allForSource(sourceId: String, userSlot: Int?): List<BloodPressureRecordEntity>

    /**
     * Un-synced records of one source, oldest first — of one user slot of
     * it when [userSlot] is given, of every slot otherwise. The upload-side
     * epic-R4 filter (task tefal2garmin-app-redesign-v3-xlg.4; the upload
     * wiring itself lands with task xlg.6).
     */
    @Query(
        "SELECT * FROM blood_pressure_records " +
            "WHERE sourceId = :sourceId AND synced = 0 " +
            "AND (:userSlot IS NULL OR userSlot = :userSlot) " +
            "ORDER BY timestampEpochSec ASC"
    )
    suspend fun unsyncedForSource(sourceId: String, userSlot: Int?): List<BloodPressureRecordEntity>

    /**
     * Every stored record of the given sources, newest first (no row
     * limit) — the unified cross-source query of the Measurements page
     * (task tefal2garmin-app-redesign-v3-xlg.6): rows of every connected
     * source in one pass, each still carrying its [sourceId] (the source
     * attribution) and [synced] flag. The epic-R4 per-source slot filter
     * is applied by the caller, which knows each source's picked user.
     * Empty [sourceIds] never reaches Room (the repository short-circuits
     * it), so the SQL never sees an empty IN list.
     */
    @Query(
        "SELECT * FROM blood_pressure_records " +
            "WHERE sourceId IN (:sourceIds) " +
            "ORDER BY timestampEpochSec DESC"
    )
    suspend fun allForSources(sourceIds: List<String>): List<BloodPressureRecordEntity>

    /** Deletes every stored record (the settings "delete all readings" action). */
    @Query("DELETE FROM blood_pressure_records")
    suspend fun deleteAll()

    /**
     * Deletes every stored record of one user slot (the settings "delete
     * profile readings" action) — the other slot's rows are untouched.
     */
    @Query("DELETE FROM blood_pressure_records WHERE userSlot = :userSlot")
    suspend fun deleteForUser(userSlot: Int)
}
