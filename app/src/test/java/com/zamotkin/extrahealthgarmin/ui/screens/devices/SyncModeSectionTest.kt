package com.zamotkin.extrahealthgarmin.ui.screens.devices

import com.zamotkin.extrahealthgarmin.devices.api.SourceCapabilities
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SyncModeSection] tests (task tefal2garmin-app-redesign-v3-xlg.5): the
 * capability-driven rendering decision of the source detail's sync-mode
 * block - the History/Current toggle exists ONLY for sources supporting
 * both modes (epic R5), single-mode sources state their one supported
 * mode (wording from capabilities, never a vendor name), anything else
 * renders nothing. Plain JUnit: the decision is pure
 * capabilities -> section, which is what keeps the business rule out of
 * the Composable (epic anti-pattern).
 */
class SyncModeSectionTest {

    private fun capabilities(vararg modes: SyncMode) = SourceCapabilities(
        userAttribution = UserAttribution.NONE,
        supportedSyncModes = modes.toSet(),
        requiresPairingKey = false
    )

    @Test
    fun `both modes render the toggle with the stored selection`() {
        assertEquals(
            SyncModeSection.Toggle(
                modes = listOf(SyncMode.HISTORY, SyncMode.CURRENT),
                selected = SyncMode.CURRENT
            ),
            SyncModeSection.from(
                capabilities(SyncMode.HISTORY, SyncMode.CURRENT),
                storedMode = SyncMode.CURRENT
            )
        )
    }

    @Test
    fun `current-only sources render the fixed current line`() {
        // A stale stored mode (the store default is History) must not
        // leak into the info line: the section names the mode the TYPE
        // supports.
        assertEquals(
            SyncModeSection.Fixed(SyncMode.CURRENT),
            SyncModeSection.from(capabilities(SyncMode.CURRENT), storedMode = SyncMode.HISTORY)
        )
    }

    @Test
    fun `history-only sources render the fixed history line`() {
        assertEquals(
            SyncModeSection.Fixed(SyncMode.HISTORY),
            SyncModeSection.from(capabilities(SyncMode.HISTORY), storedMode = SyncMode.HISTORY)
        )
    }

    @Test
    fun `an unknown type renders nothing`() {
        assertEquals(SyncModeSection.None, SyncModeSection.from(null, storedMode = SyncMode.HISTORY))
    }

    @Test
    fun `a type without supported modes renders nothing`() {
        assertEquals(
            SyncModeSection.None,
            SyncModeSection.from(capabilities(), storedMode = SyncMode.HISTORY)
        )
    }
}
