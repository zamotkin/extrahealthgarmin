package com.zamotkin.extrahealthgarmin.devices.ble1810

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Value fingerprint of a timestamp-less measurement: the values + status +
 * User ID, WITHOUT the read time (which changes every pass). Occurrence
 * counting over these fingerprints IS the "relative order" of the task
 * contract: the n-th identical record of a burst matches the n-th stored
 * one, so ring-buffer shifts (an index-based fingerprint would break there)
 * and full-history re-pushes both dedup correctly.
 */
internal fun Ble1810Measurement.valueFingerprint(): String =
    "V|$systolicMmHg|$diastolicMmHg|${mapMmHg ?: "-"}|${pulseBpm ?: "-"}|" +
        "${status?.raw ?: "-"}|${userId ?: "-"}"

/**
 * Per-source dedup journal for timestamp-less 0x1810 measurements (task
 * tefal2garmin-app-redesign-v3-xlg.9, docs/ble-1810-protocol.md §4.2):
 * devices without a clock attribute the read time, so a HISTORY re-read
 * would duplicate every stored row. The journal counts, per value
 * fingerprint, how many identical readings were already delivered —
 * [filterUnseen] lets through only the occurrences past the journaled count.
 *
 * Records WITH a device timestamp never touch the journal: their identity
 * is the stable device time and the downstream pipeline already dedups them
 * (Room's unique `(timestamp, userSlot, sourceId)` index plus the Garmin
 * upload journal) — exactly how the Omron HISTORY re-read stays idempotent.
 *
 * Persistence rides one JSON file per source (`ble1810-<address>-fingerprints.json`,
 * atomic temp+rename like
 * [com.zamotkin.extrahealthgarmin.devices.store.FileConnectedSourceStore]).
 * A missing, unreadable or corrupted file loads as an empty journal with a
 * log — the worst case is one duplicated row on a re-read, never a crash.
 * Saves MERGE with the persisted state (count-wise max, the
 * [com.zamotkin.extrahealthgarmin.garmin.BpUploadJournal] multi-instance
 * lesson): a second journal instance can never shrink away counts another
 * instance persisted after its construction.
 *
 * Pure JVM apart from [android.util.Log] (stubbed in unit tests); no
 * android.bluetooth types.
 */
class Ble1810FingerprintJournal private constructor(private val file: File) {

    private val lock = Any()
    private val storedCounts = HashMap<String, Int>()

    init {
        loadFromDisk()
    }

    /**
     * The measurements to hand to the caller: timestamp-carrying ones pass
     * through untouched; timestamp-less ones only past their journaled
     * occurrence count (identical readings earlier in the same batch count
     * toward the occurrence index, so two real identical readings in one
     * burst are both new, and a re-read of both is not).
     */
    fun filterUnseen(measurements: List<Ble1810Measurement>): List<Ble1810Measurement> =
        synchronized(lock) {
            val batchOccurrences = HashMap<String, Int>()
            measurements.filter { measurement ->
                if (measurement.timestampFromDeviceClock) return@filter true
                val fingerprint = measurement.valueFingerprint()
                val occurrence = (batchOccurrences.merge(fingerprint, 1, Int::plus) ?: 1) - 1
                occurrence >= storedCounts.getOrDefault(fingerprint, 0)
            }
        }

    /**
     * Journals the timestamp-less measurements among [delivered] (the result
     * of [filterUnseen]) and persists. Only the caller decides what counts
     * as delivered — the journal never grows on its own.
     */
    fun markSeen(delivered: List<Ble1810Measurement>) = synchronized(lock) {
        for (measurement in delivered) {
            if (!measurement.timestampFromDeviceClock) {
                storedCounts.merge(measurement.valueFingerprint(), 1, Int::plus)
            }
        }
        persistMerged()
    }

    /** Number of distinct timestamp-less fingerprints journaled. */
    fun fingerprintCount(): Int = synchronized(lock) { storedCounts.size }

    // ------------------------------------------------------------------ //
    //  Persistence                                                        //
    // ------------------------------------------------------------------ //

    private fun loadFromDisk() {
        val text = try {
            if (!file.isFile) return
            file.readText()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read ${file.name}; starting from empty", e)
            return
        }
        try {
            val counts = JSONObject(text).getJSONObject(KEY_COUNTS)
            for (key in counts.keys()) storedCounts[key] = counts.getInt(key)
        } catch (e: Exception) {
            Log.w(TAG, "Corrupted ${file.name}; starting from empty", e)
            storedCounts.clear()
        }
    }

    /**
     * Merge-on-save: the persisted counts win wherever they are higher, so a
     * stale second instance can only add, never erase. A failed write keeps
     * the in-memory state — the next markSeen retries the whole union.
     */
    private fun persistMerged() {
        val union = LinkedHashMap(readPersistedCounts())
        for ((fingerprint, count) in storedCounts) {
            union[fingerprint] = maxOf(count, union[fingerprint] ?: 0)
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.writeText(toJson(union).toString())
            if (!temp.renameTo(file)) {
                // Cross-filesystem rename failed; fall back to copy-then-delete
                // so the final file is still written whole, never appended to.
                temp.copyTo(file, overwrite = true)
                if (!temp.delete()) throw IOException("Could not clean up ${temp.path}")
            }
            storedCounts.putAll(union)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to persist fingerprints to ${file.name}", e)
            temp.delete()
        }
    }

    private fun readPersistedCounts(): Map<String, Int> {
        return try {
            if (!file.isFile) return emptyMap()
            val counts = JSONObject(file.readText()).getJSONObject(KEY_COUNTS)
            val out = HashMap<String, Int>()
            for (key in counts.keys()) out[key] = counts.getInt(key)
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun toJson(counts: Map<String, Int>): JSONObject = JSONObject().apply {
        put(KEY_COUNTS, JSONObject(counts as Map<*, *>))
    }

    companion object {
        private const val TAG = "Ble1810FingerprintJournal"
        private const val KEY_COUNTS = "counts"

        /** One journal per connected source, keyed by the BLE address. */
        fun inFilesDir(filesDir: File, address: String): Ble1810FingerprintJournal =
            Ble1810FingerprintJournal(File(filesDir, fileNameFor(address)))

        /** MAC colons survive on the filesystem but read badly — dashes do both. */
        internal fun fileNameFor(address: String): String =
            "ble1810-${address.replace(':', '-')}-fingerprints.json"
    }
}
