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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.zamotkin.extrahealthgarmin.devices.api.SyncMode
import com.zamotkin.extrahealthgarmin.devices.api.UserAttribution
import com.zamotkin.extrahealthgarmin.ui.kit.components.EmptyState
import com.zamotkin.extrahealthgarmin.ui.kit.components.LabeledValue
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionCard
import java.text.DateFormat
import java.util.Date

/**
 * One connected source (task tefal2garmin-app-redesign-v3-xlg.3): the
 * detail card (type, address, connected since) and the destructive remove
 * action behind a confirmation dialog. Removal drops ONLY the connection -
 * stored readings stay in Room. Task xlg.4 added the user-attribution
 * block: sources with device user slots show their picked user READ-ONLY
 * (changing it is remove-and-re-add by design), sources without attribution
 * state that every reading is the app owner's. Task xlg.5 added the
 * sync-mode block ([SyncModeSection]): sources supporting both modes get
 * the segmented History/Current toggle (persisted through
 * [SourcesViewModel.setSyncMode]); single-mode sources get an info line
 * naming their one supported mode.
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
                // User attribution (task xlg.4): the picked monitor user is
                // read-only here - re-picking means removing and re-adding
                // the source; sources without attribution get the info line
                // instead, never a slot row.
                when (type?.capabilities?.userAttribution) {
                    UserAttribution.SLOTS -> {
                        LabeledValue(
                            label = stringResource(R.string.source_detail_user_slot),
                            value = source.userSlot
                                ?.let { slot -> stringResource(R.string.source_user_slot_name, slot) }
                                ?: stringResource(R.string.source_detail_user_slot_unset),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    UserAttribution.NONE -> {
                        Text(
                            text = stringResource(R.string.source_detail_owner_attribution),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    null -> Unit // unknown type - no attribution row
                }
                LabeledValue(
                    label = stringResource(R.string.source_detail_connected),
                    value = remember(source.createdAt) { formatDate(source.createdAt) },
                )
            }
            // Sync mode (task xlg.5): the segmented History/Current control
            // renders ONLY for sources whose capabilities include both
            // modes; single-mode sources get an info line naming their one
            // supported mode, unknown types nothing.
            when (val modeSection = SyncModeSection.from(type?.capabilities, source.syncMode)) {
                is SyncModeSection.Toggle -> {
                    Spacer(modifier = Modifier.height(24.dp))
                    SectionCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.source_detail_sync_mode),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            modeSection.modes.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = mode == modeSection.selected,
                                    onClick = { viewModel.setSyncMode(source.id, mode) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = modeSection.modes.size,
                                    ),
                                ) {
                                    Text(text = stringResource(mode.labelRes()))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(modeSection.selected.explanationRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is SyncModeSection.Fixed -> {
                    Spacer(modifier = Modifier.height(24.dp))
                    SectionCard(modifier = Modifier.fillMaxWidth()) {
                        LabeledValue(
                            label = stringResource(R.string.source_detail_sync_mode),
                            value = stringResource(modeSection.mode.labelRes()),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(modeSection.mode.fixedInfoRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SyncModeSection.None -> Unit
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

/** Mode -> its short label: the segmented row's option / the detail value. */
private fun SyncMode.labelRes(): Int = when (this) {
    SyncMode.HISTORY -> R.string.source_sync_mode_history
    SyncMode.CURRENT -> R.string.source_sync_mode_current
}

/** Mode -> the one-line explanation shown under the toggle. */
private fun SyncMode.explanationRes(): Int = when (this) {
    SyncMode.HISTORY -> R.string.source_sync_mode_history_hint
    SyncMode.CURRENT -> R.string.source_sync_mode_current_hint
}

/** Mode -> the info line of a source that cannot switch modes. */
private fun SyncMode.fixedInfoRes(): Int = when (this) {
    SyncMode.HISTORY -> R.string.source_sync_mode_history_only
    SyncMode.CURRENT -> R.string.source_sync_mode_current_only
}
