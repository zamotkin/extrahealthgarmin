package com.zamotkin.extrahealthgarmin.ui.kit.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zamotkin.extrahealthgarmin.ui.UploadSummary

/**
 * The shared upload/sync outcome line (task
 * tefal2garmin-app-redesign-v3-xlg.7): resolves an [UploadSummary]'s
 * template with its arguments and appends the failure detail as a second,
 * quieter line. Home renders it centered inside its status card; the
 * Settings Garmin group renders it start-aligned as "last upload". Purely
 * presentational — the mapping onto [UploadSummary] lives in the UI state.
 */
@Composable
fun UploadSummaryText(
    summary: UploadSummary,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    Column(
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(summary.messageRes, *summary.args.toTypedArray()),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = if (centered) TextAlign.Center else null,
            modifier = Modifier.fillMaxWidth(),
        )
        summary.failureMessage?.let { failure ->
            Text(
                text = failure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (centered) TextAlign.Center else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
        }
    }
}
