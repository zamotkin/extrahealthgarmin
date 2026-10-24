package com.zamotkin.extrahealthgarmin.omron.data

import com.zamotkin.extrahealthgarmin.data.UploadOutcome
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import kotlinx.coroutines.CancellationException

/**
 * One blood-pressure direct-upload pass (epic tefal2garmin-939, "direct sync
 * only" — no export/share-sheet anywhere), scoped to ONE monitor user slot
 * (task tefal2garmin-db5: the M7's two device slots are different people and
 * the Garmin account is one person, so the other slot's readings never reach
 * Garmin): takes the un-synced readings of that slot from [OmronBpRepository],
 * hands the whole batch to [upload] (the data-layer
 * [com.zamotkin.extrahealthgarmin.data.GarminRepository.uploadBloodPressure]
 * entry point, injected as a function so this class stays free of the data
 * layer's collaborators) and, for every record the upload CONFIRMED, marks it
 * synced back in the repository. The Garmin-side dedup journal lives behind
 * the upload entry point — this class never re-uploads, it only mirrors the
 * confirmation into the local sync flag.
 *
 * Never throws, mirroring [OmronBpRepository]: every failure is reported
 * through the constructor-injected [onError] hook (the platform wiring points
 * it at its logger of choice) and mapped onto an [UploadOutcome].
 *
 * Self-healing: an [UploadOutcome.NotNeeded] over a non-empty pending batch
 * means every pending record is already journaled as uploaded on the Garmin
 * side (e.g. a previous pass confirmed the upload but its markSynced was
 * lost to a DAO failure) — all pending records of the slot are then marked
 * synced, so a confirmed upload can never get stuck un-synced.
 */
class BpSyncCoordinator(
    private val repository: OmronBpRepository,
    private val upload: suspend (List<BloodPressureReading>) -> UploadOutcome,
    private val onError: (Throwable) -> Unit = {}
) {

    /**
     * Runs one pass over the un-synced readings of [userSlot] and returns
     * the upload outcome verbatim — [UploadOutcome.NotNeeded] also when
     * there was nothing pending for that slot.
     */
    suspend fun syncOnce(userSlot: Int): UploadOutcome {
        // Never throws by contract; empty on DAO failure (reported to the
        // repository's own onError) — an empty batch skips the upload.
        val pending = repository.unsynced(userSlot)
        if (pending.isEmpty()) {
            return UploadOutcome.NotNeeded
        }
        return try {
            when (val outcome = upload(pending)) {
                is UploadOutcome.BpUploaded -> {
                    markSynced(outcome.confirmedRecords)
                    outcome
                }
                is UploadOutcome.NotNeeded -> {
                    // The Garmin journal already covers the whole batch — see
                    // the class KDoc self-heal note.
                    markSynced(pending)
                    outcome
                }
                else -> outcome
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            UploadOutcome.Retryable(reasonOf(e))
        }
    }

    /**
     * Best-effort per-record marking: a false answer (DAO failure) leaves the
     * record un-synced — a later pass re-marks it, and the Garmin journal
     * already prevents a re-upload.
     */
    private suspend fun markSynced(records: List<BloodPressureReading>) {
        for (record in records) {
            repository.markSynced(record.timestampEpochSec, record.userSlot)
        }
    }
}

private fun reasonOf(e: Throwable): String =
    e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
