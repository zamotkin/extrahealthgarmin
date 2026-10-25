package com.zamotkin.extrahealthgarmin.ui.screens.devices

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.ui.kit.components.EmptyState
import com.zamotkin.extrahealthgarmin.ui.kit.components.LabeledValue
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionCard
import java.text.DateFormat
import java.util.Date

/**
 * One connected source (task tefal2garmin-app-redesign-v3-xlg.3): the
 * detail card (type, address, connected since) and the destructive remove
 * action behind a confirmation dialog. Removal drops ONLY the connection -
 * stored readings stay in Room. The user-slot row lands with task xlg.4
 * and the sync-mode toggle with xlg.5; only what exists today is shipped.
 */
@Composable
fun SourceDetailScreen(
    sourceId: String,
    onBack: () -> Unit,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: SourcesViewModel = viewModel(factory = SourcesViewModelFactory(application))
    val sources by viewModel.sources.observeAsState()
    val source = sources?.firstOrNull { it.id == sourceId }
    val type = source?.let { viewModel.typeOf(it.sourceTypeId) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    val title = when {
        source == null -> stringResource(R.string.source_detail_title)
        source.address == null ->
            // Singleton sources: the type's display name is the source name.
            type?.let { stringResource(it.displayNameRes) } ?: source.name
        else -> source.name
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        BackHeader(title = title, onBack = onBack)
        Spacer(modifier = Modifier.height(16.dp))
        if (source == null) {
            // Removed elsewhere or a stale navigation - honest empty state.
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    title = stringResource(R.string.source_not_found),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                if (type != null) {
                    LabeledValue(
                        label = stringResource(R.string.source_detail_type),
                        value = stringResource(type.displayNameRes),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                source.address?.let { address ->
                    LabeledValue(
                        label = stringResource(R.string.source_detail_address),
                        value = address,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                LabeledValue(
                    label = stringResource(R.string.source_detail_connected),
                    value = remember(source.createdAt) { formatDate(source.createdAt) },
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { confirmRemove = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.source_remove))
            }
        }
    }

    if (confirmRemove && source != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(text = stringResource(R.string.source_remove_confirm_title)) },
            text = { Text(text = stringResource(R.string.source_remove_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    if (viewModel.remove(source.id)) onRemoved()
                }) {
                    Text(text = stringResource(R.string.source_remove_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(text = stringResource(R.string.source_remove_cancel))
                }
            },
        )
    }
}

/** Epoch millis -> a medium localized date (screen-only formatting). */
private fun formatDate(epochMs: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
