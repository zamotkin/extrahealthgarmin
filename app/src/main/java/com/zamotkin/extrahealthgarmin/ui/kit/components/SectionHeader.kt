package com.zamotkin.extrahealthgarmin.ui.kit.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign

/**
 * Title line of a screen or a section within it. Purely presentational.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        textAlign = if (centered) TextAlign.Center else null,
        modifier = if (centered) modifier.fillMaxWidth() else modifier,
    )
}
