package com.zamotkin.extrahealthgarmin.omron.data

import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import kotlinx.coroutines.CancellationException

/**
 * Never-throws gateway over [BloodPressureDao] for Omron blood-pressure
 * records (task tefal2garmin-4uy, epic tefal2garmin-939) — the omron/data
 * counterpart of the data-layer rule in
 * [com.zamotkin.extrahealthgarmin.data.GarminRepository]: every DAO failure
 * maps onto an empty/failed result instead of an exception.
 *
 * Depends ONLY on the DAO interface, so unit tests run as plain JUnit with an
 * in-memory fake — no Android framework, hence no android.util.Log either:
 * failures are reported through the constructor-injected [onError] hook the
 * platform wiring can point at its logger of choice.
 *
 * Dedup contract: a reading is identified by (timestampEpochSec, userSlot,
 * sourceId) — the DAO's unique index. Re-reads of the same EEPROM block
 * insert nothing and are counted as duplicates; the same measurement read
 * through two different sources, or served for two different user slots, is
 * two distinct records. The Garmin-side journal still dedups per
 * (timestamp, userSlot) — see [BloodPressureDao.markSynced].
 */
class OmronBpRepository(
    private val dao: BloodPressureDao,
    private val onError: (Throwable) -> Unit = {}
) {

    /**
     * One stored reading plus its persistence context (task
     * tefal2garmin-app-redesign-v3-xlg.6): what
     * [BloodPressureRecordEntity] holds but [BloodPressureReading] drops —
     * the [sourceId] the reading came through (the Measurements page's
     * source attribution) and the [synced] upload flag (its
     * synced/unsynced state). Returned by the unified cross-source query
     * [allForSources].
     */
    data class StoredBpReading(
        val timestampEpochSec: Long,
        val systolic: Int,
        val diastolic: Int,
        val pulse: Int,
        val mov: Boolean,
        val ihb: Boolean,
        val userSlot: Int,
        val sourceId: String,
        val synced: Boolean
    )

    /**
     * Outcome of a [storeReadings] batch.
     *
     * @property insertedCount rows actually written (their (timestamp,
     *   userSlot) was not in the table yet)
     * @property duplicatesCount rows skipped because the key already existed
     * @property success whether the DAO batch ran — the disambiguation task
     *   tefal2garmin-app-redesign-v3-xlg.15 added, because zero inserted
     *   alone cannot tell "all duplicates / empty batch" (the rows ARE
     *   durably in Room) from "DAO failed, nothing written". True means the
     *   counts are authoritative; false means a DAO failure — nothing was
     *   written and the counts carry no meaning. The never-throw convention
     *   is unchanged: failures surface on the result, not as exceptions.
     */
    data class StoreResult(
        val insertedCount: Int,
        val duplicatesCount: Int,
        val success: Boolean = true
    )

    /**
     * Persists [readings] of [sourceId] via insert-ignore: every
     * (timestampEpochSec, userSlot, sourceId) already present is counted as a
     * duplicate and left untouched, everything else is stored as a new
     * un-synced row. On DAO failure reports [StoreResult] with zero counts
     * and [StoreResult.success] false — nothing was written, and a caller
     * holding deferred dedup state (the 0x1810 fingerprint journal) must NOT
     * commit it on that outcome.
     */
    suspend fun storeReadings(
        readings: List<BloodPressureReading>,
        sourceId: String = BloodPressureRecordEntity.LEGACY_SOURCE_ID
    ): StoreResult =
        runCatching {
            val rowIds = dao.insertIgnoreAll(readings.map { it.toEntity(sourceId) })
            StoreResult(
                insertedCount = rowIds.count { it != IGNORED_ROW_ID },
                duplicatesCount = rowIds.count { it == IGNORED_ROW_ID }
            )
        }.getOrElse { e ->
            failed(e) { StoreResult(insertedCount = 0, duplicatesCount = 0, success = false) }
        }

    /**
     * Un-synced readings, oldest first — of one [userSlot] when given, of
     * every slot otherwise. Empty on DAO failure.
     */
    suspend fun unsynced(userSlot: Int? = null): List<BloodPressureReading> =
        runCatching {
            val rows = if (userSlot != null) dao.unsyncedForUser(userSlot) else dao.unsyncedAll()
            rows.map { it.toDomain() }
        }.getOrElse { e -> failed(e) { emptyList() } }

    /**
     * Marks the records with the given (timestampEpochSec, userSlot) as
     * synced — every source's row with that key, mirroring the Garmin
     * journal's composite key (see [BloodPressureDao.markSynced]). True when
     * the update ran; false on DAO failure (whether a row matched is not
     * observable through the DAO and does not change this — re-marking a
     * synced record is a no-op either way).
     */
    suspend fun markSynced(timestampEpochSec: Long, userSlot: Int): Boolean =
        runCatching {
            dao.markSynced(timestampEpochSec, userSlot)
            true
        }.getOrElse { e -> failed(e) { false } }

    /**
     * The newest [limit] readings of one [userSlot], newest first.
     * Empty on DAO failure.
     */
    suspend fun latest(userSlot: Int, limit: Int = 20): List<BloodPressureReading> =
        runCatching {
            dao.latest(userSlot, limit).map { it.toDomain() }
        }.getOrElse { e -> failed(e) { emptyList() } }

    /**
     * Every stored reading of one [userSlot], newest first — the full history
     * of the slot, with no row limit. Empty on DAO failure.
     */
    suspend fun allForUser(userSlot: Int): List<BloodPressureReading> =
        runCatching {
            dao.allForUser(userSlot).map { it.toDomain() }
        }.getOrElse { e -> failed(e) { emptyList() } }

    /**
     * Every stored reading of one source, newest first — of one user slot of
     * it when given (the epic-R4 display filter: rows of other slots stay
     * invisible once the source has its picked user), of every slot of the
     * source otherwise (task tefal2garmin-app-redesign-v3-xlg.4). Empty on
     * DAO failure.
     */
    suspend fun allForSource(
        sourceId: String,
        userSlot: Int? = null,
    ): List<BloodPressureReading> =
        runCatching {
            dao.allForSource(sourceId, userSlot).map { it.toDomain() }
        }.getOrElse { e -> failed(e) { emptyList() } }

    /**
     * Un-synced readings of one source, oldest first — of one user slot of
     * it when given, of every slot otherwise. The upload-side R4 filter for
     * task xlg.6. Empty on DAO failure.
     */
    suspend fun unsyncedForSource(
        sourceId: String,
        userSlot: Int? = null,
    ): List<BloodPressureReading> =
        runCatching {
            dao.unsyncedForSource(sourceId, userSlot).map { it.toDomain() }
        }.getOrElse { e -> failed(e) { emptyList() } }

    /**
     * Every stored reading of the given sources, newest first — the
     * unified cross-source query of the Measurements page (task
     * tefal2garmin-app-redesign-v3-xlg.6), each row tagged with its source
     * id and upload state. An empty [sourceIds] answers empty without
     * touching the DAO (Room would otherwise build an empty IN list).
     * Empty on DAO failure.
     */
    suspend fun allForSources(sourceIds: List<String>): List<StoredBpReading> {
        if (sourceIds.isEmpty()) return emptyList()
        return runCatching {
            dao.allForSources(sourceIds).map { it.toStoredReading() }
        }.getOrElse { e -> failed(e) { emptyList() } }
    }

    /**
     * Deletes every stored reading (the settings "delete all" action).
     * True when the delete ran; false on DAO failure.
     */
    suspend fun deleteAll(): Boolean =
        runCatching {
            dao.deleteAll()
            true
        }.getOrElse { e -> failed(e) { false } }

    /**
     * Deletes every stored reading of one [userSlot] (the settings "delete
     * profile readings" action, task tefal2garmin-7m2) — the other slot's
     * rows are untouched. True when the delete ran; false on DAO failure.
     */
    suspend fun deleteForUser(userSlot: Int): Boolean =
        runCatching {
            dao.deleteForUser(userSlot)
            true
        }.getOrElse { e -> failed(e) { false } }

    /** Reports [e] via [onError] and answers with the [fallback] value. */
    private fun <T> failed(e: Throwable, fallback: () -> T): T {
        if (e is CancellationException) throw e
        onError(e)
        return fallback()
    }

    private companion object {
        /** Row id Room's @Insert(IGNORE) reports for a skipped duplicate. */
        const val IGNORED_ROW_ID = -1L
    }
}

/** Domain -> row: persistence state starts at defaults on every insert. */
private fun BloodPressureReading.toEntity(sourceId: String) = BloodPressureRecordEntity(
    timestampEpochSec = timestampEpochSec,
    systolic = systolic,
    diastolic = diastolic,
    pulse = pulse,
    mov = mov,
    ihb = ihb,
    userSlot = userSlot,
    sourceId = sourceId
)

/** Row -> domain: drops the row id and the sync flag. */
private fun BloodPressureRecordEntity.toDomain() = BloodPressureReading(
    timestampEpochSec = timestampEpochSec,
    systolic = systolic,
    diastolic = diastolic,
    pulse = pulse,
    mov = mov,
    ihb = ihb,
    userSlot = userSlot
)

/** Row -> stored-reading domain: keeps the source tag and the sync flag. */
private fun BloodPressureRecordEntity.toStoredReading() = OmronBpRepository.StoredBpReading(
    timestampEpochSec = timestampEpochSec,
    systolic = systolic,
    diastolic = diastolic,
    pulse = pulse,
    mov = mov,
    ihb = ihb,
    userSlot = userSlot,
    sourceId = sourceId,
    synced = synced
)
