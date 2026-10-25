package com.zamotkin.extrahealthgarmin.ui.omron

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure slot-coercion logic of [SharedPreferencesOmronSettingsStore]
 * (task tefal2garmin-mnb): the Omron M7 knows exactly users 1 and 2, so any
 * other persisted/entered value falls back to the default slot. Plain JUnit —
 * no SharedPreferences (the persistence layer stays untested here by design;
 * it is a trivial put/get pair).
 */
class OmronSettingsStoreTest {

    @Test
    fun validSlotsPassThrough() {
        assertEquals(1, coerceUserSlot(1))
        assertEquals(2, coerceUserSlot(2))
    }

    @Test
    fun invalidSlotsFallBackToDefault() {
        assertEquals(DEFAULT_USER_SLOT, coerceUserSlot(0))
        assertEquals(DEFAULT_USER_SLOT, coerceUserSlot(3))
        assertEquals(DEFAULT_USER_SLOT, coerceUserSlot(-1))
        assertEquals(DEFAULT_USER_SLOT, coerceUserSlot(99))
    }
}
