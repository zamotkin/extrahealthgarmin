package com.zamotkin.extrahealthgarmin.devices.store

import android.util.Log
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * One connected blood-pressure source, as persisted in
 * [ConnectedSourceStore] (task tefal2garmin-app-redesign-v3-xlg.2). The
 * add-source flow (task .3) creates rows; the id and [sourceTypeId] link a
 * stored reading back to its source.
 *
 * @property id UUID of the connected source instance
 * @property sourceTypeId registry key of the source type (omron-ble, ...)
 * @property name user-visible name (the device name for BLE sources)
 * @property address BLE device MAC address (BLE sources only)
 * @property pairingKeyRef identifier of the stored app pairing key (sources
 *   that need one); how per-source keys are stored is owned by the pairing
 *   flow
 * @property userSlot the picked device user slot (Omron only; null when the
 *   source has no user attribution)
 * @property syncMode the configured read mode; see
 *   [com.zamotkin.extrahealthgarmin.devices.api.SyncMode]
 * @property lastReadAnchor the last successful read anchor (task .5;
 *   null until the first successful Current read)
 * @property createdAt when the source was connected, epoch ms
 */
data class ConnectedSource(
    val id: String,
    val sourceTypeId: String,
    val name: String,
    val address: String? = null,
    val pairingKeyRef: String? = null,
    val userSlot: Int? = null,
    val syncMode: SyncMode = SyncMode.HISTORY,
    val lastReadAnchor: Long? = null,
    val createdAt: Long
)

/** Load/save seam for [ConnectedSource] - faked in unit tests. */
interface ConnectedSourceStore {
    /** The persisted sources; empty when nothing was ever stored. */
    fun load(): List<ConnectedSource>

    /**
     * Persists [sources] atomically. True when the file was written; false
     * when the write failed (logged, never thrown - the old file survives).
     */
    fun save(sources: List<ConnectedSource>): Boolean
}

/**
 * [ConnectedSourceStore] backed by one JSON file, written atomically like
 * [com.zamotkin.extrahealthgarmin.health.FileSyncStateStore] (temp file +
 * rename, serialized on an internal lock). A missing, unreadable or
 * corrupted file loads as an empty list with a log - the worst case is the
 * user reconnecting a source, never a crash.
 */
class FileConnectedSourceStore(private val file: File) : ConnectedSourceStore {

    private val lock = Any()

    override fun load(): List<ConnectedSource> = synchronized(lock) {
        val text = try {
            if (!file.isFile) return emptyList()
            file.readText()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read ${file.name}; starting from empty", e)
            return emptyList()
        }
        try {
            fromJson(JSONObject(text))
        } catch (e: JSONException) {
            Log.w(TAG, "Corrupted ${file.name}; starting from empty", e)
            emptyList()
        }
    }

    override fun save(sources: List<ConnectedSource>): Boolean = synchronized(lock) {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.writeText(toJson(sources).toString())
            if (!temp.renameTo(file)) {
                // Cross-filesystem rename failed (or the target was locked);
                // fall back to copy-then-delete so the final file is still
                // written whole, never appended to.
                temp.copyTo(file, overwrite = true)
                if (!temp.delete()) {
                    throw IOException("Could not clean up ${temp.path}")
                }
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to persist sources to ${file.name}", e)
            // Best-effort cleanup; a stale temp never participates in a load.
            temp.delete()
            false
        }
    }

    // ------------------------------------------------------------------ //
    //  JSON                                                               //
    // ------------------------------------------------------------------ //

    private fun fromJson(json: JSONObject): List<ConnectedSource> {
        val array = json.optJSONArray(KEY_SOURCES) ?: return emptyList()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ConnectedSource(
                id = item.getString(KEY_ID),
                sourceTypeId = item.getString(KEY_SOURCE_TYPE_ID),
                name = item.getString(KEY_NAME),
                address = item.optString(KEY_ADDRESS).ifEmpty { null },
                pairingKeyRef = item.optString(KEY_PAIRING_KEY_REF).ifEmpty { null },
                userSlot = item.optNullableInt(KEY_USER_SLOT),
                syncMode = parseSyncMode(item.optString(KEY_SYNC_MODE)),
                lastReadAnchor = item.optNullableLong(KEY_LAST_READ_ANCHOR),
                createdAt = item.getLong(KEY_CREATED_AT)
            )
        }
    }

    private fun toJson(sources: List<ConnectedSource>): JSONObject = JSONObject().apply {
        put(
            KEY_SOURCES,
            JSONArray(sources.map { source ->
                JSONObject()
                    .put(KEY_ID, source.id)
                    .put(KEY_SOURCE_TYPE_ID, source.sourceTypeId)
                    .put(KEY_NAME, source.name)
                    .put(KEY_ADDRESS, source.address ?: JSONObject.NULL)
                    .put(KEY_PAIRING_KEY_REF, source.pairingKeyRef ?: JSONObject.NULL)
                    .put(KEY_USER_SLOT, source.userSlot ?: JSONObject.NULL)
                    .put(KEY_SYNC_MODE, source.syncMode.name)
                    .put(KEY_LAST_READ_ANCHOR, source.lastReadAnchor ?: JSONObject.NULL)
                    .put(KEY_CREATED_AT, source.createdAt)
            })
        )
    }

    /** A stored mode string that no longer parses keeps the default. */
    private fun parseSyncMode(raw: String): SyncMode =
        runCatching { SyncMode.valueOf(raw) }.getOrDefault(SyncMode.HISTORY)

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private companion object {
        const val TAG = "FileConnectedSourceStore"
        const val KEY_SOURCES = "sources"
        const val KEY_ID = "id"
        const val KEY_SOURCE_TYPE_ID = "sourceTypeId"
        const val KEY_NAME = "name"
        const val KEY_ADDRESS = "address"
        const val KEY_PAIRING_KEY_REF = "pairingKeyRef"
        const val KEY_USER_SLOT = "userSlot"
        const val KEY_SYNC_MODE = "syncMode"
        const val KEY_LAST_READ_ANCHOR = "lastReadAnchor"
        const val KEY_CREATED_AT = "createdAt"
    }
}
