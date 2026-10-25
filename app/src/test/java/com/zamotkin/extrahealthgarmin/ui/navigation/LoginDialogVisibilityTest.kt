package com.zamotkin.extrahealthgarmin.ui.navigation

import com.zamotkin.extrahealthgarmin.ui.MainUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the login-dialog visibility state machine — the Compose port of the
 * duplicate-emission guards from the View-based MainActivity — against the
 * MainUiState contract: which emissions open/close the dialog, that equal
 * repeated emissions never re-trigger it (the flicker/reopen guard), and
 * that a locally requested dialog survives unrelated emissions but closes
 * on a successful login (rising garminConnected) or a user cancel.
 *
 * Plain JUnit: [LoginDialogVisibility.visible] reads and writes like a
 * normal field outside composition.
 */
class LoginDialogVisibilityTest {

    private fun state(
        showLoginDialog: Boolean = false,
        garminConnected: Boolean = false,
    ) = MainUiState(
        showLoginDialog = showLoginDialog,
        garminConnected = garminConnected,
    )

    @Test
    fun `opens when the view model asks`() {
        val visibility = LoginDialogVisibility()
        visibility.onState(state(showLoginDialog = true))
        assertTrue(visibility.visible)
    }

    @Test
    fun `initial stored_token emission with no dialog is a no_op`() {
        // The sticky first emission of a signed-in user: garminConnected
        // starts true, which reads as a rising edge — it must not matter
        // because no dialog is on screen.
        val visibility = LoginDialogVisibility()
        visibility.onState(state(garminConnected = true))
        assertFalse(visibility.visible)
    }

    @Test
    fun `user request opens and survives unrelated emissions`() {
        val visibility = LoginDialogVisibility()
        visibility.onState(state()) // first emission: nothing connected
        visibility.onUserOpen()
        assertTrue(visibility.visible)
        // Unrelated re-render (HC status refresh): showLoginDialog stays
        // false — the locally requested dialog must stay up.
        visibility.onState(state())
        assertTrue(visibility.visible)
    }

    @Test
    fun `duplicate ask emissions keep the dialog up without retrigger`() {
        val visibility = LoginDialogVisibility()
        visibility.onState(state(showLoginDialog = true))
        assertTrue(visibility.visible)
        // NeedsMfa / Failed style emissions: showLoginDialog stays true.
        visibility.onState(
            MainUiState(showLoginDialog = true, loginNeedsMfa = true)
        )
        assertTrue(visibility.visible)
        visibility.onState(
            MainUiState(showLoginDialog = true, loginError = "boom")
        )
        assertTrue(visibility.visible)
    }

    @Test
    fun `closes on the falling edge of show_login_dialog`() {
        val visibility = LoginDialogVisibility()
        visibility.onState(state(showLoginDialog = true))
        assertTrue(visibility.visible)
        visibility.onState(state(showLoginDialog = false))
        assertFalse(visibility.visible)
    }

    @Test
    fun `closes when garmin_connected rises after a local request`() {
        val visibility = LoginDialogVisibility()
        visibility.onState(state())
        visibility.onUserOpen()
        assertTrue(visibility.visible)
        // Successful login: garminConnected false -> true.
        visibility.onState(state(garminConnected = true))
        assertFalse(visibility.visible)
    }

    @Test
    fun `closes when garmin_connected rises while the view model asks`() {
        val visibility = LoginDialogVisibility()
        visibility.onState(state(showLoginDialog = true))
        assertTrue(visibility.visible)
        // Success after a LoginRequired flow: showLoginDialog drops and
        // garminConnected rises in the same emission.
        visibility.onState(state(showLoginDialog = false, garminConnected = true))
        assertFalse(visibility.visible)
    }

    @Test
    fun `user dismiss closes and a later login_required reopens`() {
        val visibility = LoginDialogVisibility()
        visibility.onState(state(showLoginDialog = true))
        assertTrue(visibility.visible)
        visibility.onUserDismiss()
        assertFalse(visibility.visible)
        // The cancel emission (falling edge): stays closed.
        visibility.onState(state(showLoginDialog = false))
        assertFalse(visibility.visible)
        // A later sync hitting LoginRequired asks again: reopens.
        visibility.onState(state(showLoginDialog = true))
        assertTrue(visibility.visible)
    }

    @Test
    fun `equal states are idempotent across repeated on_state calls`() {
        val visibility = LoginDialogVisibility()
        visibility.onState(state(showLoginDialog = true))
        visibility.onState(state(showLoginDialog = true))
        assertTrue(visibility.visible)
        visibility.onState(state(showLoginDialog = false))
        visibility.onState(state(showLoginDialog = false))
        assertFalse(visibility.visible)
        // No edge can fire twice for the same value: a repeat of the close
        // emission must not reopen anything.
        visibility.onState(state(showLoginDialog = false))
        assertFalse(visibility.visible)
    }

    @Test
    fun `view_model_ask after a local request keeps the dialog up`() {
        val visibility = LoginDialogVisibility()
        visibility.onState(state())
        visibility.onUserOpen()
        // A sync outcome asks for the dialog while it is already up.
        visibility.onState(state(showLoginDialog = true))
        assertTrue(visibility.visible)
        // ...and the matching close (upload retried after login, success):
        visibility.onState(state(showLoginDialog = false, garminConnected = true))
        assertFalse(visibility.visible)
    }
}
