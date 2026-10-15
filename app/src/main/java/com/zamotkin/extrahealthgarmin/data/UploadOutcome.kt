package com.zamotkin.extrahealthgarmin.data

import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading

/**
 * Outcome of uploading not-yet-journaled [com.zamotkin.extrahealthgarmin.garmin.GarminWeighInRecord]s
 * via [GarminUploadOrchestrator.uploadNewRecords], and of blood-pressure
 * batches via [GarminRepository.uploadBloodPressure].
 *
 * There is no residual file here: records that were not confirmed imported
 * simply stay un-journaled, so the next call retries exactly them.
 *
 *  - [NotNeeded] — the journal already covers every record; zero network.
 *  - [Success] — every new record was imported ([uploadedCount] of them).
 *  - [Partial] — some records were confirmed imported ([uploadedCount]);
 *    [failureMessages] describes the rest, which stay retriable.
 *  - [BpUploaded] — blood-pressure batch whose confirmed readings are listed
 *    by identity, so the caller can mark exactly them synced in its own store.
 *  - [Retryable] — nothing was uploaded and nothing journaled (rate limit,
 *    transport failure, validation violation); the caller may retry later.
 *  - [LoginRequired] — no usable stored tokens (or they were rejected and
 *    cleared); the UI must run [GarminUploadOrchestrator.login] first.
 */
sealed class UploadOutcome {
    object NotNeeded : UploadOutcome()
    data class Success(val uploadedCount: Int) : UploadOutcome()
    data class Partial(
        val uploadedCount: Int,
        val failureMessages: List<String>
    ) : UploadOutcome()

    /**
     * Blood-pressure batch result (one variant covers full and partial
     * success): [confirmedRecords] are exactly the readings whose Garmin
     * import is confirmed — the repository has already journaled them, and
     * the caller should mark them synced in its own store. [failureMessages]
     * is empty when the whole batch imported; non-empty means the remaining
     * records were rejected and stay retriable (un-journaled).
     */
    data class BpUploaded(
        val confirmedRecords: List<BloodPressureReading>,
        val failureMessages: List<String> = emptyList()
    ) : UploadOutcome()
    data class Retryable(val reason: String) : UploadOutcome()
    object LoginRequired : UploadOutcome()
}
