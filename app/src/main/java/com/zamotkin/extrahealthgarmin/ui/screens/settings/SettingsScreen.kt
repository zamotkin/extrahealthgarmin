package com.zamotkin.extrahealthgarmin.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.ui.kit.components.EhgButton
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionCard
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionHeader
import com.zamotkin.extrahealthgarmin.ui.kit.components.StatusRow

/**
 * Settings tab (redesign v3): the first real group — Connections, with the
 * connected-sources row and the add-source flow entry (task xlg.3) — over
 * an honest note that the Garmin / Health Connect / About groups land with
 * task xlg.7.
 */
@Composable
fun SettingsScreen(
    onOpenSources: () -> Unit,
    onAddSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionHeader(text = stringResource(R.string.nav_settings))
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_connections_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        SectionCard(modifier = Modifier.fillMaxWidth()) {
            StatusRow(
                label = stringResource(R.string.settings_connections_sources),
                value = stringResource(R.string.settings_connections_sources_value),
                onClick = onOpenSources,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        EhgButton(
            text = stringResource(R.string.add_source),
            onClick = onAddSource,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_interim_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
