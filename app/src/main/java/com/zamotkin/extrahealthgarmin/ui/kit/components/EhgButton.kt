package com.zamotkin.extrahealthgarmin.ui.kit.components

import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app's standard button. [tonal] picks the secondary-emphasis filled
 * tonal style (secondary actions such as "Backfill" and "Connect Garmin");
 * the default is the primary filled style. Label comes from R.string at
 * the call site — no formatting logic here; sizing stays with the caller
 * via [modifier].
 */
@Composable
fun EhgButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
) {
    if (tonal) {
        FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Text(text)
        }
    } else {
        Button(onClick = onClick, modifier = modifier, enabled = enabled) {
            Text(text)
        }
    }
}
