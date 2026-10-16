package com.zamotkin.extrahealthgarmin.ui.omron

import android.content.SharedPreferences

/**
 * Settings seam for the Omron UI (task tefal2garmin-mnb): the monitor user
 * slot (1|2) and the auto-upload toggle. An interface on purpose so
 * [OmronViewModel] unit tests run against an in-memory fake; production uses
 * [SharedPreferencesOmronSettingsStore] (no Room for two booleans — the
 * records database stays in omron/data, UI prefs stay in plain prefs).
 */
interface OmronSettingsStore {

    /** The selected Omron user slot: 1 or 2 (coerced, see [coerceUserSlot]). */
    fun userSlot(): Int

    fun setUserSlot(slot: Int)

    /** Whether a finished transfer uploads to Garmin immediately. */
    fun autoSync(): Boolean

    fun setAutoSync(enabled: Boolean)
}

/** Fallback slot when a stored/entered value is out of range. */
internal const val DEFAULT_USER_SLOT = 1

/**
 * Clamps a user slot to the two the Omron M7 knows: 1 and 2 pass through,
 * anything else falls back to [DEFAULT_USER_SLOT]. Pure function —
 * unit-tested without SharedPreferences.
 */
internal fun coerceUserSlot(slot: Int): Int =
    if (slot == 1 || slot == 2) slot else DEFAULT_USER_SLOT

/**
 * Production [OmronSettingsStore] over one SharedPreferences file. Only the
 * coercion logic lives here; persistence is delegated to [prefs].
 */
class SharedPreferencesOmronSettingsStore(
    private val prefs: SharedPreferences
) : OmronSettingsStore {

    override fun userSlot(): Int = coerceUserSlot(prefs.getInt(KEY_USER_SLOT, DEFAULT_USER_SLOT))

    override fun setUserSlot(slot: Int) {
        prefs.edit().putInt(KEY_USER_SLOT, coerceUserSlot(slot)).apply()
    }

    override fun autoSync(): Boolean = prefs.getBoolean(KEY_AUTO_SYNC, true)

    override fun setAutoSync(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    private companion object {
        const val KEY_USER_SLOT = "user_slot"
        const val KEY_AUTO_SYNC = "auto_sync"
    }
}
