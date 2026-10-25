package com.zamotkin.extrahealthgarmin.ui.omron

import androidx.annotation.StringRes

/**
 * UI state machine of the Omron blood-pressure screen
 * (task tefal2garmin-mnb, epic tefal2garmin-939). The ViewModel maps every
 * [com.zamotkin.extrahealthgarmin.omron.ble.OmronBleResult] variant onto one
 * of these; the Compose screens render them — never the BLE types directly.
 *
 * Same convention as ui.MainUiState: the state carries NO user-visible text,
 * only markers — every message is resolved from an R.string by the screen.
 */
sealed interface OmronUiState {

    /** Resting state — the transfer can be started. */
    data object Idle : OmronUiState

    /**
     * Runtime Bluetooth permissions are missing; [permissions] is what the
     * activity should request (mirrors
     * [com.zamotkin.extrahealthgarmin.omron.ble.requiredBlePermissions]).
     */
    data class PermissionsMissing(val permissions: List<String>) : OmronUiState

    /**
     * Device/system pairing attention is needed before a transfer can run.
     * [step] names which [com.zamotkin.extrahealthgarmin.omron.ble.OmronBleResult]
     * stage asked for it (only BondingRequired does today; every other
     * non-success stage is an [Error]).
     */
    data class Pairing(val step: OmronPairingStep) : OmronUiState

    /** A BLE readout pass is in flight (connect + unlock + EEPROM transfer). */
    data object Reading : OmronUiState

    /** The pass finished; [readingsCount] measurements arrived from the device. */
    data class Success(val readingsCount: Int) : OmronUiState

    /**
     * The pass failed. [messageRes] is the localized primary message;
     * [detail] is an optional non-localized diagnostic (raw reason/state from
     * the failing layer) shown beneath it.
     */
    data class Error(@StringRes val messageRes: Int, val detail: String? = null) : OmronUiState
}

/** Pairing attention steps surfaced from the BLE result stages. */
enum class OmronPairingStep {

    /**
     * [com.zamotkin.extrahealthgarmin.omron.ble.OmronBleResult.BondingRequired]:
     * the OS-level bond with the monitor is missing — the user must pair it
     * in Android Bluetooth settings and retry.
     */
    SYSTEM_BONDING
}
