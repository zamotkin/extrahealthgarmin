package com.zamotkin.extrahealthgarmin.ui.screens.devices

import com.zamotkin.extrahealthgarmin.devices.api.SourceCapabilities
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode

/**
 * What the source-detail screen renders for the sync-mode block (task
 * tefal2garmin-app-redesign-v3-xlg.5, epic R5) - the capability-driven
 * rendering decision, kept out of the Composable so it is unit-testable:
 *
 *  - [Toggle] - the type supports BOTH modes (the Omron shape): the
 *    segmented History/Current control, selection = the stored mode.
 *  - [Fixed] - the type supports exactly ONE mode (Health Connect is
 *    Current-only, the standard 0x1810 driver is History-only): a
 *    read-only info line naming that mode. The wording comes from the
 *    capabilities - never a hardcoded vendor name.
 *  - [None] - unknown type or no supported modes: nothing to render.
 */
sealed interface SyncModeSection {

    /** Both modes supported: [modes] is the render order, History first. */
    data class Toggle(
        val modes: List<SyncMode>,
        val selected: SyncMode,
    ) : SyncModeSection

    /** Exactly one mode supported: the source always runs it. */
    data class Fixed(val mode: SyncMode) : SyncModeSection

    /** Nothing to render (unknown type, no supported modes). */
    object None : SyncModeSection

    companion object {

        /**
         * Decides the block from the type's capabilities and the stored
         * mode. [storedMode] only seeds [Toggle.selected] - a single-mode
         * source shows the mode its TYPE supports, which may differ from a
         * stale stored value.
         */
        fun from(capabilities: SourceCapabilities?, storedMode: SyncMode): SyncModeSection {
            val supported = capabilities?.supportedSyncModes ?: return None
            return when {
                SyncMode.HISTORY in supported && SyncMode.CURRENT in supported -> Toggle(
                    modes = listOf(SyncMode.HISTORY, SyncMode.CURRENT),
                    selected = storedMode
                )

                SyncMode.CURRENT in supported -> Fixed(SyncMode.CURRENT)
                SyncMode.HISTORY in supported -> Fixed(SyncMode.HISTORY)
                else -> None
            }
        }
    }
}
