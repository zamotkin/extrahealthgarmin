package com.zamotkin.extrahealthgarmin.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zamotkin.extrahealthgarmin.R

/**
 * Garmin sign-in dialog (Compose port of the former programmatic
 * AlertDialog): email + password, a verification-code field once the
 * ViewModel reports NeedsMfa, and an inline error line. Credentials live
 * only in these fields — the state is dropped with the dialog (plain
 * remember, not saved across instances), exactly like the former
 * dropLoginDialog clearing.
 *
 * The submit/resend buttons never dismiss the dialog themselves: NeedsMfa
 * and Failed keep it open with the entered credentials intact; the
 * ViewModel-driven close is handled by [LoginDialogVisibility].
 */
@Composable
fun GarminLoginDialog(
    needsMfa: Boolean,
    error: String?,
    onSubmit: (email: String, password: String, mfaCode: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mfaCode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.login_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.login_email_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.login_password_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (needsMfa) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = mfaCode,
                        onValueChange = { mfaCode = it },
                        label = { Text(stringResource(R.string.mfa_code_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.login_failed, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val code = mfaCode.trim().takeIf { it.isNotEmpty() }
                    onSubmit(email.trim(), password, code)
                }
            ) {
                Text(stringResource(R.string.login_submit))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.login_cancel))
                }
                if (needsMfa) {
                    Spacer(modifier = Modifier.width(8.dp))
                    // Resend = re-run login with the same credentials, no code.
                    TextButton(
                        onClick = { onSubmit(email.trim(), password, null) }
                    ) {
                        Text(stringResource(R.string.resend_code))
                    }
                }
            }
        },
    )
}
