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
        persist()
    }

    /** Number of journaled (uploaded) records. */
    fun uploadedCount(): Int = synchronized(lock) { uploadedKeys.size }

    // ------------------------------------------------------------------ //
    //  Persistence                                                        //
    // ------------------------------------------------------------------ //

    private fun loadFromPersistence() {
        val raw = try {
            persistence.load()
        } catch (e: Exception) {
            Log.w(TAG, "BP upload journal unreadable; starting fresh", e)
            null
        } ?: return
        try {
            val array = JSONArray(raw)
            val loaded = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                loaded += array.getString(i)
            }
            uploadedKeys += loaded
        } catch (e: Exception) {
            Log.w(TAG, "Corrupted BP upload journal; starting fresh: ${e.message}")
            uploadedKeys.clear()
        }
    }

    private fun persist() {
        try {
            persistence.save(JSONArray(uploadedKeys).toString())
        } catch (e: Exception) {
            Log.w(TAG, "BP upload journal not persisted: ${e.message}")
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
