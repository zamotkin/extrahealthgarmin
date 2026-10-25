package com.zamotkin.extrahealthgarmin.ui.screens.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zamotkin.extrahealthgarmin.ui.MainUiState

/**
 * Login-dialog visibility across [MainUiState] LiveData emissions — the
 * Compose port of the duplicate-emission guards of the View-based
 * MainActivity (its lastShowLoginDialog / lastGarminConnected pair).
 *
 * The dialog opens when the ViewModel asks ([MainUiState.showLoginDialog],
 * set by a LoginRequired sync/upload outcome, NeedsMfa or a Failed login)
 * or on a local user request ([onUserOpen] — the Connect Garmin button).
 * It closes on the falling edge of showLoginDialog, on the rising edge of
 * garminConnected (a successful login) and on a user cancel
 * ([onUserDismiss]).
 *
 * Edge detection is what keeps repeated equal emissions from re-triggering
 * the one-shot dialog lifecycle (flicker/reopen); [onState] is idempotent
 * for equal consecutive states, so recomposition with an unchanged state
 * is a no-op.
 *
 * [visible] is snapshot state so Compose recomposes when callbacks mutate
 * it; in plain unit tests it reads and writes like a normal field.
 */
class LoginDialogVisibility {

    /** Whether the login dialog should be on screen right now. */
    var visible: Boolean by mutableStateOf(false)
        private set

    private var lastShowLoginDialog = false
    private var lastGarminConnected = false

    /** Local open request (the Connect Garmin button). */
    fun onUserOpen() {
        visible = true
    }

    /**
     * User cancelled (back / outside touch / the Cancel button). The caller
     * also tells the ViewModel (onLoginCancelled) so a late login result can
     * never flip state; this only drops the local visibility.
     */
    fun onUserDismiss() {
        visible = false
    }

    /**
     * One LiveData emission. Ports the original renderLoginDialog order:
     * close on the falling edge of showLoginDialog or the rising edge of
     * garminConnected first, then open whenever the ViewModel asks.
     */
    fun onState(state: MainUiState) {
        val closeDueToState = (lastShowLoginDialog && !state.showLoginDialog) ||
            (state.garminConnected && !lastGarminConnected)
        if (closeDueToState) {
            visible = false
        }
        if (state.showLoginDialog) {
            visible = true
        }
        lastShowLoginDialog = state.showLoginDialog
        lastGarminConnected = state.garminConnected
    }
}
