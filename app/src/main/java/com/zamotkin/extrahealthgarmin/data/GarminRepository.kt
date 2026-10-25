package com.zamotkin.extrahealthgarmin.data

import android.content.Context
import android.util.Log
import com.zamotkin.extrahealthgarmin.garmin.BpUploadJournal
import com.zamotkin.extrahealthgarmin.garmin.GarminAuthClient
import com.zamotkin.extrahealthgarmin.garmin.GarminAuthError
import com.zamotkin.extrahealthgarmin.garmin.GarminConnectionError
import com.zamotkin.extrahealthgarmin.garmin.GarminExistingWeighIn
import com.zamotkin.extrahealthgarmin.garmin.GarminExistingWeighIns
import com.zamotkin.extrahealthgarmin.garmin.GarminProfileHeight
import com.zamotkin.extrahealthgarmin.garmin.GarminTooManyRequestsError
import com.zamotkin.extrahealthgarmin.garmin.GarminTokenStore
import com.zamotkin.extrahealthgarmin.garmin.GarminTokens
import com.zamotkin.extrahealthgarmin.garmin.GarminUploadClient
import com.zamotkin.extrahealthgarmin.garmin.GarminValidationError
import com.zamotkin.extrahealthgarmin.garmin.GarminWeighInRecord
import com.zamotkin.extrahealthgarmin.garmin.KeystoreAesCrypto
import com.zamotkin.extrahealthgarmin.garmin.LoginResult
import com.zamotkin.extrahealthgarmin.garmin.MfaClientState
import com.zamotkin.extrahealthgarmin.garmin.SharedPreferencesTokenPersistence
import com.zamotkin.extrahealthgarmin.garmin.UploadJournal
import com.zamotkin.extrahealthgarmin.garmin.UploadResult
import com.zamotkin.extrahealthgarmin.garmin.bpJournalKey
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Status of a Garmin login attempt, for the UI layer. [Failed.reason] is a
 * human-readable message (English; the UI decides on presentation).
 */
sealed class LoginStatus {
    object Success : LoginStatus()
    object NeedsMfa : LoginStatus()
    data class Failed(val reason: String) : LoginStatus()
}

/**
 * The UI-facing contract for the Garmin API import feature. Implementations
 * never throw: every failure maps onto [LoginStatus.Failed] or an
 * [UploadOutcome] variant.
 */
interface GarminUploadOrchestrator {
    suspend fun login(email: String, password: String, mfaCode: String? = null): LoginStatus

    /**
     * Records-based upload: deduplicates [records] against the journal and
     * against the weigh-ins Garmin Connect already holds, uploads the rest as
     * one batch and reports an [UploadOutcome]. No
     * residual file is produced — unconfirmed records simply stay
     * un-journaled and are retried by the next call. Never throws.
     */
    suspend fun uploadNewRecords(records: List<GarminWeighInRecord>): UploadOutcome

    fun hasStoredToken(): Boolean
}

/**
 * Network seam over the task-1/2 clients so [GarminRepository] can be tested
 * with plain fakes (the real clients are final classes doing live HTTP).
 */
internal interface GarminRemote {
    suspend fun login(email: String, password: String): LoginResult

    suspend fun resumeLogin(clientState: MfaClientState, code: String): GarminTokens

    suspend fun uploadWeighIns(
        records: List<GarminWeighInRecord>,
        tokens: GarminTokens
    ): UploadResult

    /** Weigh-ins Garmin Connect already holds for [from]..[to] (local dates, inclusive). */
    suspend fun fetchExistingWeighIns(
        from: LocalDate,
        to: LocalDate,
        tokens: GarminTokens
    ): ExistingWeighIns

    /** Height (metres) from the Garmin user profile, null when unset. */
    suspend fun fetchProfileHeightMeters(tokens: GarminTokens): ProfileHeight
    suspend fun uploadBloodPressure(
        records: List<BloodPressureReading>,
        tokens: GarminTokens
    ): UploadResult

    /**
     * Discards any pending MFA flow on the underlying auth client, exactly
     * like [com.zamotkin.extrahealthgarmin.garmin.GarminAuthClient.logout]. Needed
     * before a fresh [login] can restart an MFA flow (the "resend code" path).
     */
    fun logout()
}

/** [GarminRemote.fetchExistingWeighIns] result plus the (possibly refreshed) tokens it used. */
internal data class ExistingWeighIns(
    val weighIns: List<GarminExistingWeighIn>,
    val tokens: GarminTokens
)

/** [GarminRemote.fetchProfileHeightMeters] result plus the (possibly refreshed) tokens it used. */
internal data class ProfileHeight(
    val heightMeters: Double?,
    val tokens: GarminTokens
)

/** Production [GarminRemote]: the real HTTP clients over one shared auth session. */
internal class HttpGarminRemote(
    private val authClient: GarminAuthClient,
    private val uploadClient: GarminUploadClient
) : GarminRemote {

    override suspend fun login(email: String, password: String): LoginResult =
        authClient.login(email, password)

    override suspend fun resumeLogin(clientState: MfaClientState, code: String): GarminTokens =
        authClient.resumeLogin(clientState, code)

    override suspend fun uploadWeighIns(
        records: List<GarminWeighInRecord>,
        tokens: GarminTokens
    ): UploadResult = uploadClient.uploadWeighIns(records, tokens)

    override suspend fun fetchExistingWeighIns(
        from: LocalDate,
        to: LocalDate,
        tokens: GarminTokens
    ): ExistingWeighIns {
        val result = authClient.apiGet(tokens, GarminExistingWeighIns.rangePath(from, to))
        return ExistingWeighIns(GarminExistingWeighIns.parse(result.body), result.tokens)
    }

    override suspend fun fetchProfileHeightMeters(tokens: GarminTokens): ProfileHeight {
        val result = authClient.apiGet(tokens, GarminProfileHeight.PATH)
        return ProfileHeight(GarminProfileHeight.parseMeters(result.body), result.tokens)
    }
    override suspend fun uploadBloodPressure(
        records: List<BloodPressureReading>,
        tokens: GarminTokens
    ): UploadResult = uploadClient.uploadBloodPressure(records, tokens)

    override fun logout() = authClient.logout()
}

/**
 * Data-layer orchestration behind [GarminUploadOrchestrator]: deduplicates
 * weigh-in records against the local [UploadJournal] and against Garmin
 * Connect's own weigh-in list ([GarminExistingWeighIns]), and blood-pressure
 * readings against [BpUploadJournal] (see [uploadBloodPressure]); drives
 * login/MFA-resume/upload through the task-1/2 clients, and reports every
 * failure as a typed outcome.
 *
 * Never throws across the interface: exceptions become [LoginStatus.Failed]
 * or [UploadOutcome] variants. Retry/backoff is owned by the clients — this
 * class makes at most one upload attempt per call.
 */
class GarminRepository internal constructor(
    private val remote: GarminRemote,
    private val tokenStore: GarminTokenStore,
    private val journal: UploadJournal,
    /** Zone that maps a weigh-in onto Garmin's local `calendarDate`. */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val bpJournal: BpUploadJournal
) : GarminUploadOrchestrator {

    constructor(context: Context) : this(
        remote = defaultRemote(),
        tokenStore = GarminTokenStore(
            SharedPreferencesTokenPersistence(context),
            KeystoreAesCrypto()
        ),
        journal = UploadJournal.inFilesDir(context.filesDir),
        bpJournal = BpUploadJournal.inFilesDir(context.filesDir)
    )

    /**
     * Client state of the MFA flow returned by the last [login] that answered
     * NeedsMfa, or null. Remembered here because the real client keeps the
     * flow alive in its own `pendingMfa` (a second `login` throws) — the
     * resume must reuse THIS state, and a resend must `logout()` first.
     */
    @Volatile
    private var pendingMfaClientState: MfaClientState? = null

    /**
     * Single-flight guard for [uploadBloodPressure] (task tefal2garmin-n3q):
     * held across the whole filterNew -> upload -> markUploaded sequence so
     * concurrent invocations serialize instead of racing the check-then-act
     * dedup — without it, two passes can both pass filterNew before either
     * marks the journal and the same reading uploads to Garmin twice.
     */
    private val bpUploadMutex = Mutex()

    // ------------------------------------------------------------------ //
    //  GarminUploadOrchestrator                                           //
    // ------------------------------------------------------------------ //

    override fun hasStoredToken(): Boolean = tokenStore.load() != null

    override suspend fun login(email: String, password: String, mfaCode: String?): LoginStatus =
        withContext(Dispatchers.IO) {
            try {
                val pending = pendingMfaClientState
                when {
                    // Code entry after MFA_REQUIRED: resume the STORED flow —
                    // calling login() again is deadlocked by the auth client's
                    // pendingMfa gate ("MFA login already in progress").
                    mfaCode != null && pending != null -> {
                        val tokens = remote.resumeLogin(pending, mfaCode)
                        pendingMfaClientState = null
                        tokenStore.save(tokens)
                        LoginStatus.Success
                    }
                    // Resend code with a pending flow: the client gate must be
                    // cleared first, then a fresh login restarts the MFA flow.
                    pending != null -> {
                        remote.logout()
                        pendingMfaClientState = null
                        freshLogin(email, password, mfaCode)
                    }
                    else -> freshLogin(email, password, mfaCode)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Login failed: ${reasonOf(e)}")
                LoginStatus.Failed(reasonOf(e))
            }
        }

    /**
     * Plain login attempt (no pending MFA flow). A NeedsMfa answer stores the
     * client state so a later code entry can resume this exact flow; a failed
     * resume keeps it, so the user may retry with a corrected code.
     */
    private suspend fun freshLogin(email: String, password: String, mfaCode: String?): LoginStatus {
        return when (val result = remote.login(email, password)) {
            is LoginResult.NeedsMfa -> {
                pendingMfaClientState = result.clientState
                if (mfaCode == null) {
                    LoginStatus.NeedsMfa
                } else {
                    // A code was supplied together with the credentials: resume
                    // the flow this very login just started.
                    val tokens = remote.resumeLogin(result.clientState, mfaCode)
                    pendingMfaClientState = null
                    tokenStore.save(tokens)
                    LoginStatus.Success
                }
            }
            is LoginResult.Success -> {
                pendingMfaClientState = null
                tokenStore.save(result.tokens)
                LoginStatus.Success
            }
        }
    }

    override suspend fun uploadNewRecords(records: List<GarminWeighInRecord>): UploadOutcome =
        withContext(Dispatchers.IO) {
            val newRecords = journal.filterNew(records)
            if (newRecords.isEmpty()) {
                Log.d(TAG, "Upload: journal covers every record - skipping network")
                return@withContext UploadOutcome.NotNeeded
            }

            val tokens = tokenStore.load()
            if (tokens == null) {
                Log.d(TAG, "Upload: no stored DI tokens - login required")
                return@withContext UploadOutcome.LoginRequired
            }

            // Range validation BEFORE any network call: the FIT uint16 field
            // scales would silently truncate out-of-range floats.
            try {
                GarminWeighInRecord.validateRanges(newRecords)
            } catch (e: GarminValidationError) {
                return@withContext UploadOutcome.Retryable(
                    e.message ?: "Value outside plausible range"
                )
            }

            try {
                // Server-side dedup BEFORE uploading: weigh-ins that reached
                // Garmin some other way are journaled, not re-sent. A failed
                // lookup uploads nothing (mapped below like an upload failure)
                // - never risk a duplicate.
                val days = newRecords.map { GarminExistingWeighIns.localDate(it, zone()) }
                val existing = remote.fetchExistingWeighIns(
                    days.min().minusDays(1), days.max().plusDays(1), tokens
                )
                val (alreadyInGarmin, toUpload) = newRecords.partition {
                    GarminExistingWeighIns.isAlreadyPresent(it, existing.weighIns, zone())
                }
                if (alreadyInGarmin.isNotEmpty()) {
                    Log.d(TAG, "Upload: ${alreadyInGarmin.size} record(s) already in Garmin Connect - journaled, not re-sent")
                    journal.markSent(alreadyInGarmin.map { it.journalKey() })
                }
                if (toUpload.isEmpty()) {
                    tokenStore.save(existing.tokens)
                    return@withContext UploadOutcome.NotNeeded
                }
                val (uploadBatch, uploadTokens) = withProfileBmi(toUpload, existing.tokens)
                when (val result = remote.uploadWeighIns(uploadBatch, uploadTokens)) {
                    is UploadResult.Success -> {
                        journal.markSent(toUpload.map { it.journalKey() })
                        // Persist the (possibly rotated) tokens the upload actually
                        // used — dropping them forces a re-login once the old
                        // refresh token expires (task 5 fix).
                        tokenStore.save(result.tokens)
                        Log.d(TAG, "Upload: ${toUpload.size} records confirmed by Garmin")
                        UploadOutcome.Success(toUpload.size)
                    }
                    is UploadResult.Partial -> {
                        // Journal ONLY the provably imported keys; everything
                        // else stays retriable.
                        journal.markSent(result.succeededKeys)
                        tokenStore.save(result.tokens)
                        val succeeded = result.succeededKeys.toHashSet()
                        UploadOutcome.Partial(
                            uploadedCount = toUpload.count { it.journalKey() in succeeded },
                            failureMessages = result.failureMessages
                        )
                    }
                }
            } catch (e: GarminAuthError) {
                Log.w(TAG, "Upload: auth rejected (${reasonOf(e)}) - clearing stored tokens")
                tokenStore.clear()
                UploadOutcome.LoginRequired
            } catch (e: GarminTooManyRequestsError) {
                UploadOutcome.Retryable(reasonOf(e))
            } catch (e: GarminConnectionError) {
                UploadOutcome.Retryable(reasonOf(e))
            } catch (e: GarminValidationError) {
                // Defensive: the client re-validates before encoding; nothing was sent.
                UploadOutcome.Retryable(reasonOf(e))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unreachable by construction — kept so the interface can
                // never throw.
                Log.w(TAG, "Upload failed unexpectedly", e)
                UploadOutcome.Retryable("Unexpected error: ${reasonOf(e)}")
            }
        }

    /**
     * Fills a missing BMI from the Garmin profile height (Health Connect often
     * has no height record). Best effort: when the profile read fails or has
     * no height, the records go up without BMI, exactly as before. Auth errors
     * propagate so the caller clears the tokens as for any other request.
     */
    private suspend fun withProfileBmi(
        records: List<GarminWeighInRecord>,
        tokens: GarminTokens
    ): Pair<List<GarminWeighInRecord>, GarminTokens> {
        if (records.none { it.bmi == null }) return records to tokens
        val profile = try {
            remote.fetchProfileHeightMeters(tokens)
        } catch (e: GarminConnectionError) {
            Log.w(TAG, "Upload: profile height unavailable (${reasonOf(e)}) - uploading without BMI")
            return records to tokens
        } catch (e: GarminTooManyRequestsError) {
            Log.w(TAG, "Upload: profile height rate limited - uploading without BMI")
            return records to tokens
        }
        val height = profile.heightMeters ?: return records to profile.tokens
        Log.d(TAG, "Upload: BMI from Garmin profile height ${"%.2f".format(height)} m")
        return records.map { GarminProfileHeight.withBmi(it, height) } to profile.tokens
    }

    /**
     * Blood-pressure counterpart of [uploadNewRecords] (epic tefal2garmin-939,
     * direct upload only — no export/share path anywhere). Deduplicates
     * [records] against the local [BpUploadJournal] (composite key =
     * timestampEpochSec + userSlot, so USER1 and USER2 never dedup each
     * other), uploads the new ones as ONE blood_pressure FIT batch through
     * the same client path and exception mapping as weigh-ins, and on a
     * confirmed import journals the confirmed records and returns them as
     * [UploadOutcome.BpUploaded.confirmedRecords] — repository-agnostic: this
     * class neither knows nor touches the caller's own sync bookkeeping (the
     * caller marks the confirmed records synced in its store). Never throws.
     *
     * Concurrency contract (task tefal2garmin-n3q): the whole
     * filterNew -> upload -> markUploaded sequence runs under a per-instance
     * single-flight mutex — a concurrent caller of THIS instance WAITS, then
     * sees the journal already updated and answers
     * [UploadOutcome.NotNeeded] with zero network, so a reading is never
     * uploaded twice. Cross-process or cross-instance concurrency (two
     * repositories over one journal file) is NOT serialized by this mutex;
     * it is defused at the persistence layer by [BpUploadJournal]'s
     * merge-on-save. Same-process callers should share one repository
     * instance (the manual wiring does) — mirroring
     * [com.zamotkin.extrahealthgarmin.health.SyncEngine]'s same-process
     * serialization contract.
     */
    suspend fun uploadBloodPressure(records: List<BloodPressureReading>): UploadOutcome =
        withContext(Dispatchers.IO) {
            bpUploadMutex.withLock {
                val newRecords = bpJournal.filterNew(records)
                if (newRecords.isEmpty()) {
                    Log.d(TAG, "BP upload: journal covers every record - skipping network")
                    return@withContext UploadOutcome.NotNeeded
                }

                val tokens = tokenStore.load()
                if (tokens == null) {
                    Log.d(TAG, "BP upload: no stored DI tokens - login required")
                    return@withContext UploadOutcome.LoginRequired
                }

                try {
                    when (val result = remote.uploadBloodPressure(newRecords, tokens)) {
                        is UploadResult.Success -> {
                            bpJournal.markUploaded(newRecords)
                            // Persist the (possibly rotated) tokens the upload actually used.
                            tokenStore.save(result.tokens)
                            Log.d(TAG, "BP upload: ${newRecords.size} records confirmed by Garmin")
                            UploadOutcome.BpUploaded(newRecords)
                        }
                        is UploadResult.Partial -> {
                            // Journal ONLY the provably imported records; everything
                            // else stays retriable.
                            val confirmedByKey = result.succeededKeys.toHashSet()
                            val confirmed = newRecords.filter { it.bpJournalKey() in confirmedByKey }
                            bpJournal.markUploaded(confirmed)
                            tokenStore.save(result.tokens)
                            UploadOutcome.BpUploaded(confirmed, result.failureMessages)
                        }
                    }
                } catch (e: GarminAuthError) {
                    Log.w(TAG, "BP upload: auth rejected (${reasonOf(e)}) - clearing stored tokens")
                    tokenStore.clear()
                    UploadOutcome.LoginRequired
                } catch (e: GarminTooManyRequestsError) {
                    UploadOutcome.Retryable(reasonOf(e))
                } catch (e: GarminConnectionError) {
                    UploadOutcome.Retryable(reasonOf(e))
                } catch (e: GarminValidationError) {
                    // Defensive: the client validates before encoding; nothing was sent.
                    UploadOutcome.Retryable(reasonOf(e))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Unreachable by construction — kept so the method can never throw.
                    Log.w(TAG, "BP upload failed unexpectedly", e)
                    UploadOutcome.Retryable("Unexpected error: ${reasonOf(e)}")
                }
            }
        }

    companion object {
        private const val TAG = "GarminRepository"

        /** One auth client shared by login and upload (the upload 401-refresh uses its session state). */
        private fun defaultRemote(): GarminRemote {
            val authClient = GarminAuthClient()
            return HttpGarminRemote(authClient, GarminUploadClient(authClient))
        }
    }
}

private fun reasonOf(e: Exception): String =
    e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
