package com.zamotkin.extrahealthgarmin.garmin

import android.util.Log
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import org.json.JSONArray
import java.io.File

/**
 * Composite dedup key of a blood-pressure reading:
 * `timestampEpochSec:userSlot`. The user slot is a mandatory part of the key
 * (medical-data isolation requirement): the same measurement time for USER1
 * and USER2 is two DIFFERENT records and must never dedup each other — one
 * user's uploaded reading must never suppress the other user's upload.
 */
fun BloodPressureReading.bpJournalKey(): String = "$timestampEpochSec:$userSlot"

/**
 * Local uploaded-records journal for blood-pressure readings — the
 * [UploadJournal] counterpart, keyed by [bpJournalKey]
 * (`timestampEpochSec:userSlot`, matching the DAO's unique index) instead of
 * the weigh-in `epoch:weight`. Decides which readings are new so re-processing
 * the same measurement never uploads it twice.
 *
 * [filterNew] dedupes against the journal AND within the batch itself — the
 * device can re-serve an already-read measurement. Only [markUploaded] may
 * grow the journal, and only the caller decides what counts as confirmed.
 *
 * Persistence rides the same [JournalPersistence] seam (a JSON array of
 * composite key strings; atomicity is owned by the persistence
 * implementation). All access is synchronized. A corrupted persisted journal
 * degrades to a fresh empty one with a Log.w — worst case is a
 * duplicate-detection response from Garmin, never a crash.
 *
 * Multi-instance story (task tefal2garmin-n3q): every save MERGES — the
 * persisted key set is re-read from the seam and written back as the UNION
 * with the in-memory additions, so a second journal instance holding a
 * stale in-memory set (e.g. a second repository built from the context
 * while one is alive) can never erase keys another instance persisted after
 * its construction. In-memory reads ([filterNew]) still answer from the
 * cached set — cross-instance freshness is reconciled only at construction
 * and save time. Merge-on-save protects the journal FILE, not the upload
 * flow: concurrent callers sharing one persistence must still serialize
 * their filter -> upload -> journal sequence themselves (see
 * [com.zamotkin.extrahealthgarmin.data.GarminRepository.uploadBloodPressure]).
 */
class BpUploadJournal(private val persistence: JournalPersistence) {

    private val lock = Any()
    private val uploadedKeys = mutableSetOf<String>()

    init {
        loadFromPersistence()
    }

    /**
     * Returns the records whose composite keys ([bpJournalKey]) are neither
     * journaled nor already present earlier in [records] (preserving input
     * order) — same timestamp for different user slots counts as new twice.
     */
    fun filterNew(records: List<BloodPressureReading>): List<BloodPressureReading> =
        synchronized(lock) {
            val seenInBatch = mutableSetOf<String>()
            records.filter { record ->
                val key = record.bpJournalKey()
                key !in uploadedKeys && seenInBatch.add(key)
            }
        }

    /** Marks the given records as uploaded (by composite key) and persists. */
    fun markUploaded(records: List<BloodPressureReading>) = synchronized(lock) {
        uploadedKeys += records.map { it.bpJournalKey() }
        persistMerged()
    }

    /** Number of journaled (uploaded) records. */
    fun uploadedCount(): Int = synchronized(lock) { uploadedKeys.size }

    // ------------------------------------------------------------------ //
    //  Persistence                                                        //
    // ------------------------------------------------------------------ //

    private fun loadFromPersistence() {
        uploadedKeys += readPersistedKeys() ?: return
    }

    /**
     * Merge-on-save (task tefal2garmin-n3q): re-read the persisted key set
     * and write its UNION with the in-memory additions, so a stale second
     * instance can never overwrite away keys persisted after its
     * construction. An unreadable or corrupted persisted state degrades to
     * saving the in-memory set alone — same fail-open philosophy as loading.
     */
    private fun persistMerged() {
        val union = (readPersistedKeys() ?: emptySet()) + uploadedKeys
        try {
            persistence.save(JSONArray(union).toString())
        } catch (e: Exception) {
            Log.w(TAG, "BP upload journal not persisted: ${e.message}")
        }
    }

    /**
     * Parses the persisted JSON array into a key set, or null when nothing
     * was ever stored or the content is unreadable/corrupted (both warn and
     * degrade, never throw).
     */
    private fun readPersistedKeys(): Set<String>? {
        val raw = try {
            persistence.load()
        } catch (e: Exception) {
            Log.w(TAG, "BP upload journal unreadable; treating as empty", e)
            null
        } ?: return null
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapTo(mutableSetOf()) { array.getString(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Corrupted BP upload journal; treating as empty: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "BpUploadJournal"
        private const val JOURNAL_FILE_NAME = "bp_upload_journal.json"

        /** Production factory: filesDir/bp_upload_journal.json. */
        fun inFilesDir(filesDir: File): BpUploadJournal =
            BpUploadJournal(FileJournalPersistence(File(filesDir, JOURNAL_FILE_NAME)))
    }
}
