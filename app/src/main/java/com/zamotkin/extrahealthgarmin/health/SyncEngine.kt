package com.zamotkin.extrahealthgarmin.health

import android.util.Log
import com.zamotkin.extrahealthgarmin.data.UploadOutcome
import com.zamotkin.extrahealthgarmin.garmin.GarminWeighInRecord
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.time.Duration
import java.time.Instant

/** Terminal outcome of one [SyncEngine.sync] pass. */
sealed class SyncResult {
    /** Nothing new to upload (token/height state may still have advanced). */
    object Idle : SyncResult()

    /** All new measurements were imported into Garmin Connect. */
    data class Uploaded(val uploadedCount: Int) : SyncResult()

    /**
     * Some measurements were imported; [failureMessages] describes the rest.
     * Everything is marked processed — the failures are non-retriable on the
     * Garmin side (e.g. duplicates the server already knows).
     */
    data class PartialUploaded(
        val uploadedCount: Int,
        val failureMessages: List<String>
    ) : SyncResult()

    /** Nothing persisted; run the whole pass again later. */
    object Retryable : SyncResult()

    /** Garmin login needed first; nothing persisted. */
    object LoginRequired : SyncResult()
}

/**
 * Orchestrates one Health Connect -> Garmin sync pass (Strangler phase 2a).
 *
 * Flow per [sync]:
 *  1. Fetch — first run (no stored token) reads the last
 *     [INITIAL_SYNC_WINDOW] days; later runs pull changes by token. An
 *     expired token falls back to a range re-read since
 *     [SyncState.lastSyncInstantEpochMs] (or the initial window when that is
 *     unknown) — dedup then relies on [SyncState.processedRecordIds].
 *  2. Cache — the newest height record seen becomes the BMI height.
 *  3. Aggregate — [BodyMeasurementAggregator] correlates companions into
 *     [WeighInMeasurement]s; young lone anchors are deferred, not uploaded.
 *  4. Dedup — anchors already in [SyncState.processedRecordIds] drop out.
 *  5. Upload — the gateway ([UploadOutcome]) decides the outcome.
 *  6. Persist — ONLY terminal-success outcomes ([UploadOutcome.Success],
 *     [Partial], [NotNeeded]) or an empty batch advance the token, timestamp
 *     and processed set. [Retryable] and [LoginRequired] leave the stored
 *     state untouched so the same data is retried.
 *
 * When anchors were deferred, [SyncState.lastSyncInstantEpochMs] stays put:
 * changes tokens are one-shot, so the deferred (now matured) anchors can only
 * come back through a later expired-token fallback re-read of that window.
 */
class SyncEngine(
    private val source: HealthDataSource,
    private val stateStore: SyncStateStore,
    private val gateway: suspend (List<GarminWeighInRecord>) -> UploadOutcome,
    private val clock: () -> Instant,
    private val ownPackage: String,
    /**
     * Optional cross-process guard: when set, the whole pass runs under a
     * non-blocking file lock so a concurrent pass (e.g. SyncWorker while the
     * UI already syncs) skips as [SyncResult.Idle] instead of racing the same
     * pending set. Same-process callers are expected to serialize before
     * reaching here (the UI guards on hcSyncRunning) — this lock is the
     * belt-and-braces for the worker path.
     */
    private val lockFile: java.io.File? = null
) {

    suspend fun sync(): SyncResult {
        val lock = tryAcquireLock() ?: return SyncResult.Idle
        try {
            return syncUnderLock()
        } finally {
            releaseLock(lock)
        }
    }

    private fun tryAcquireLock(): HeldLock? {
        val file = lockFile ?: return HeldLock(null, null)
        return try {
            val raf = RandomAccessFile(file, "rw")
            val channel: FileChannel = raf.channel
            val lock: FileLock? = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                Log.d(TAG, "Sync lock held by another pass - skipping as Idle")
                channel.close()
                null
            } else {
                HeldLock(raf, lock)
            }
        } catch (e: IOException) {
            // Locking is best-effort: fail-open (a missed lock only widens the
            // existing small race; a fail-closed engine would stop syncing).
            Log.w(TAG, "Sync lock unavailable, proceeding unlocked", e)
            HeldLock(null, null)
        }
    }

    private fun releaseLock(held: HeldLock) {
        try {
            held.lock?.release()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to release sync lock", e)
        }
        try {
            held.channelRaf?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to close sync lock channel", e)
        }
    }

    /**
     * On-demand backfill: re-reads the last [window] and uploads every
     * weigh-in not yet in [SyncState.processedRecordIds]. The changes token
     * and [SyncState.lastSyncInstantEpochMs] stay exactly as they were, so the
     * regular [sync] flow is unaffected; the gateway's Garmin-side duplicate
     * check keeps a re-read window from re-sending what Garmin already holds.
     * Same lock and the same terminal-only persistence as [sync].
     */
    suspend fun backfill(window: Duration): SyncResult {
        val lock = tryAcquireLock() ?: return SyncResult.Idle
        try {
            val state = stateStore.load()
            val now = clock()
            val fetched = source.readRecords(now.minus(window), now)
            return uploadAndPersist(
                state, fetched, now,
                nextToken = { state.changesToken },
                advanceLastSync = false
            )
        } finally {
            releaseLock(lock)
        }
    }

    private data class HeldLock(
        val channelRaf: RandomAccessFile?,
        val lock: FileLock?
    )

    private suspend fun syncUnderLock(): SyncResult {
        val state = stateStore.load()
        val now = clock()

        val fetched: List<WeighInRawRecord>
        val nextToken: suspend () -> String?
        when (val token = state.changesToken) {
            null -> {
                fetched = source.readRecords(now.minus(INITIAL_SYNC_WINDOW), now)
                nextToken = { source.newToken() }
            }
            else -> {
                val page = source.pullChanges(token)
                if (page.tokenExpired) {
                    val since = state.lastSyncInstantEpochMs
                        ?.let(Instant::ofEpochMilli)
                        ?: now.minus(INITIAL_SYNC_WINDOW)
                    fetched = source.readRecords(since, now)
                    nextToken = { source.newToken() }
                } else {
                    fetched = page.records
                    nextToken = { page.nextToken }
                }
            }
        }
        return uploadAndPersist(state, fetched, now, nextToken, advanceLastSync = true)
    }

    /**
     * Steps 2-6 of the class flow for an already-fetched batch, shared by
     * [sync] and [backfill]. [advanceLastSync] false keeps the stored
     * last-sync timestamp no matter what.
     */
    private suspend fun uploadAndPersist(
        state: SyncState,
        fetched: List<WeighInRawRecord>,
        now: Instant,
        nextToken: suspend () -> String?,
        advanceLastSync: Boolean
    ): SyncResult {
        val keepLastSync = !advanceLastSync
        val newestHeightMeters = fetched
            .filter { it.type == RawMeasurementType.HEIGHT }
            .maxByOrNull { it.instant }
            ?.value
        val heightMeters = newestHeightMeters ?: state.cachedHeightMeters

        val aggregation = BodyMeasurementAggregator.aggregate(
            records = fetched,
            now = now,
            ownPackage = ownPackage,
            heightMeters = heightMeters
        )
        val pending = aggregation.measurements
            .filter { it.recordId !in state.processedRecordIds }

        if (pending.isEmpty()) {
            persistTerminal(
                state, nextToken(), heightMeters,
                newlyProcessedIds = emptyList(),
                keepLastSync = keepLastSync || aggregation.deferredAnchorIds.isNotEmpty(),
                now = now
            )
            return SyncResult.Idle
        }

        val records = pending.map { it.toGarminRecord() }
        return when (val outcome = gateway(records)) {
            is UploadOutcome.Success -> {
                persistTerminal(
                    state, nextToken(), heightMeters,
                    newlyProcessedIds = pending.map { it.recordId },
                    keepLastSync = keepLastSync || aggregation.deferredAnchorIds.isNotEmpty(),
                    now = now
                )
                SyncResult.Uploaded(outcome.uploadedCount)
            }
            is UploadOutcome.NotNeeded -> {
                persistTerminal(
                    state, nextToken(), heightMeters,
                    newlyProcessedIds = pending.map { it.recordId },
                    keepLastSync = keepLastSync || aggregation.deferredAnchorIds.isNotEmpty(),
                    now = now
                )
                SyncResult.Idle
            }
            is UploadOutcome.Partial -> {
                persistTerminal(
                    state, nextToken(), heightMeters,
                    newlyProcessedIds = pending.map { it.recordId },
                    keepLastSync = keepLastSync || aggregation.deferredAnchorIds.isNotEmpty(),
                    now = now
                )
                SyncResult.PartialUploaded(outcome.uploadedCount, outcome.failureMessages)
            }
            is UploadOutcome.BpUploaded -> {
                // Unreachable from the weigh-in flow (only the blood-pressure
                // pipeline reports this variant); mapped by closest semantics.
                persistTerminal(
                    state, nextToken(), heightMeters,
                    newlyProcessedIds = pending.map { it.recordId },
                    keepLastSync = aggregation.deferredAnchorIds.isNotEmpty(),
                    now = now
                )
                if (outcome.failureMessages.isEmpty()) {
                    SyncResult.Uploaded(outcome.confirmedRecords.size)
                } else {
                    SyncResult.PartialUploaded(
                        outcome.confirmedRecords.size,
                        outcome.failureMessages
                    )
                }
            }
            is UploadOutcome.Retryable -> SyncResult.Retryable
            UploadOutcome.LoginRequired -> SyncResult.LoginRequired
        }
    }

    /**
     * The single write path — everything terminal funnels through here so the
     * Retryable/LoginRequired "no write" discipline stays provable.
     */
    private fun persistTerminal(
        previous: SyncState,
        nextToken: String?,
        heightMeters: Double?,
        newlyProcessedIds: List<String>,
        keepLastSync: Boolean,
        now: Instant
    ) {
        stateStore.save(
            SyncState(
                changesToken = nextToken,
                lastSyncInstantEpochMs = if (keepLastSync) {
                    previous.lastSyncInstantEpochMs
                } else {
                    now.toEpochMilli()
                },
                processedRecordIds = previous.processedRecordIds + newlyProcessedIds,
                cachedHeightMeters = heightMeters
            )
        )
    }

    companion object {
        private const val TAG = "SyncEngine"

        /** First-run (and fallback-with-no-timestamp) read window. */
        val INITIAL_SYNC_WINDOW: Duration = Duration.ofDays(30)
    }
}
