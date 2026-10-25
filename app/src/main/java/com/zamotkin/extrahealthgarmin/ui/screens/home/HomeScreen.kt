package com.zamotkin.extrahealthgarmin.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.livedata.observeAsState
import com.zamotkin.extrahealthgarmin.BuildConfig
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.ui.GarminUploadStatus
import com.zamotkin.extrahealthgarmin.ui.MainUiState
import com.zamotkin.extrahealthgarmin.ui.MainViewModel
import com.zamotkin.extrahealthgarmin.ui.kit.components.EhgButton
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionCard
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionHeader
import com.zamotkin.extrahealthgarmin.ui.kit.components.StatusRow
import java.text.DateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale

/**
 * Activity-owned actions the Home screen triggers (manual wiring, same
 * style as the device screens' requestBlePermissions): the Health Connect
 * permission screen and Play Store fallback (Activity Result API +
 * intents live in MainActivity) and the debug-only Garmin auth spike.
 */
data class HomeActions(
    val requestHcPermissions: () -> Unit,
    val openHealthConnectInPlayStore: () -> Unit,
    val triggerGarminAuthSpike: () -> Unit,
)

/**
 * Home tab (redesign v3, task xlg.1): faithful Compose port of the former
 * View-based MainActivity screen — Health Connect status (tap-through to
 * the permission screen / Play Store), upload progress + status, last-sync
 * + pending line, Sync now, Backfill window picker, the Garmin connect
 * entry point and the optional blood-pressure/hydration permission notes.
 * All logic stays in [MainViewModel]; this file only renders [MainUiState]
 * and forwards taps.
 *
 * [hcSyncError] is deliberately not rendered (as before); [uploadStatus]
 * keeps the existing overloaded Garmin-upload/HC-sync mapping.
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.observeAsState()
    val s = state ?: MainUiState()
    val loginDialog = remember { LoginDialogVisibility() }

    // Every distinct LiveData emission advances the login-dialog state
    // machine (edge detection; equal states are no-ops).
    LaunchedEffect(s) { loginDialog.onState(s) }

    var showBackfill by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionHeader(text = stringResource(R.string.app_name), centered = true)
        Spacer(modifier = Modifier.height(16.dp))

        SectionCard(modifier = Modifier.fillMaxWidth()) {
            HcStatusRow(s, actions)
            UploadStatusSection(s, actions)
            LastSyncLine(s)
        }
        Spacer(modifier = Modifier.height(16.dp))

        val canSync =
            s.hcAvailable == true && s.hcPermissionsGranted && !s.hcSyncRunning
        EhgButton(
            text = stringResource(R.string.sync_now),
            onClick = viewModel::syncNow,
            enabled = canSync,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        EhgButton(
            text = stringResource(R.string.backfill),
            onClick = { showBackfill = true },
            enabled = canSync,
            tonal = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (s.garminConnected) {
            StatusRow(
                label = stringResource(R.string.garmin_connected),
                leadingIcon = Icons.Filled.CheckCircle,
            )
        } else {
            EhgButton(
                text = stringResource(R.string.connect_garmin),
                onClick = loginDialog::onUserOpen,
                tonal = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        OptionalPermissionNote(
            noteRes = R.string.bp_note,
            tapRes = R.string.bp_note_tap,
            granted = s.hcBloodPressureGranted,
            onRequest = actions.requestHcPermissions,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OptionalPermissionNote(
            noteRes = R.string.hydration_note,
            tapRes = R.string.hydration_note_tap,
            granted = s.hcHydrationGranted,
            onRequest = actions.requestHcPermissions,
        )
    }

    if (showBackfill) {
        BackfillDialog(
            onDismiss = { showBackfill = false },
            onWindowSelected = { window ->
                showBackfill = false
                viewModel.backfill(window)
            },
        )
    }
    if (loginDialog.visible) {
        GarminLoginDialog(
            needsMfa = s.loginNeedsMfa,
            error = s.loginError,
            onSubmit = viewModel::loginToGarmin,
            onDismiss = {
                viewModel.onLoginCancelled()
                loginDialog.onUserDismiss()
            },
        )
    }
}

/**
 * Health Connect status line: checking / missing-or-outdated (tap opens the
 * provider in Play Store) / needs permissions (tap launches the HC
 * permission screen) / connected (no action). The debug-only Garmin auth
 * spike hangs off the long-press (this line is always on screen, so the
 * spike is reachable on a fresh launch).
 */
@Composable
private fun HcStatusRow(s: MainUiState, actions: HomeActions) {
    data class HcStatus(
        val labelRes: Int,
        val icon: ImageVector,
        val onClick: (() -> Unit)?,
    )

    val status = when {
        s.hcAvailable == null -> HcStatus(R.string.hc_status_checking, Icons.Filled.Refresh, null)
        !s.hcAvailable -> HcStatus(
            R.string.hc_status_unavailable, Icons.Filled.Warning,
            actions.openHealthConnectInPlayStore
        )
        !s.hcPermissionsGranted -> HcStatus(
            R.string.hc_status_needs_permission, Icons.Filled.Warning,
            actions.requestHcPermissions
        )
        else -> HcStatus(R.string.hc_status_ready, Icons.Filled.CheckCircle, null)
    }
    StatusRow(
        label = stringResource(status.labelRes),
        leadingIcon = status.icon,
        onClick = status.onClick,
        onLongClick = if (BuildConfig.DEBUG) actions.triggerGarminAuthSpike else null,
    )
}

/**
 * Upload/sync status mapping — the existing overloaded enum, ported
 * as-is: progress + "uploading" while in flight, a plain status line for
 * every terminal outcome, nothing before the first pass. PARTIAL and
 * RETRYABLE append the first non-blank failure message.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UploadStatusSection(s: MainUiState, actions: HomeActions) {
    when (s.uploadStatus) {
        null -> Unit
        GarminUploadStatus.UPLOADING -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(4.dp))
            StatusLine(stringResource(R.string.upload_in_progress), actions)
        }
        GarminUploadStatus.SUCCESS ->
            StatusLine(stringResource(R.string.upload_success, s.uploadedCount), actions)
        GarminUploadStatus.NOT_NEEDED ->
            StatusLine(stringResource(R.string.upload_not_needed), actions)
        GarminUploadStatus.PARTIAL -> StatusLine(
            buildString {
                append(stringResource(R.string.upload_partial, s.uploadedCount, s.totalRecords))
                s.uploadMessages.firstOrNull { it.isNotBlank() }?.let {
                    append("\n").append(it)
                }
            },
            actions,
        )
        GarminUploadStatus.RETRYABLE -> StatusLine(
            buildString {
                append(stringResource(R.string.upload_retryable))
                s.uploadMessages.firstOrNull { it.isNotBlank() }?.let {
                    append("\n").append(it)
                }
            },
            actions,
        )
        GarminUploadStatus.LOGIN_REQUIRED ->
            StatusLine(stringResource(R.string.login_required), actions)
    }
}

/** One centered status text line; debug builds long-press the auth spike. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusLine(text: String, actions: HomeActions) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = if (BuildConfig.DEBUG) actions.triggerGarminAuthSpike else null,
            )
            .padding(vertical = 4.dp),
    )
}

/** Last sync timestamp plus the pending line (only when something waits). */
@Composable
private fun LastSyncLine(s: MainUiState) {
    val text = buildString {
        append(
            if (s.hcLastSyncEpochMs != null) {
                stringResource(R.string.hc_last_sync, formatDateTime(s.hcLastSyncEpochMs))
            } else {
                stringResource(R.string.hc_never_synced)
            }
        )
        if (s.hcPendingCount > 0) {
            append('\n')
            append(stringResource(R.string.hc_pending_count, s.hcPendingCount))
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Blood pressure / water note: the "tap to allow" hint and the tap itself
 * only while that read is missing — once granted the permission screen
 * returns at once, so a tap would look dead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OptionalPermissionNote(
    noteRes: Int,
    tapRes: Int,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    val note = stringResource(noteRes)
    val text = if (granted) note else note + " " + stringResource(tapRes)
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (granted) Modifier
                else Modifier.combinedClickable(onClick = onRequest)
            )
            .padding(vertical = 4.dp),
    )
}

/**
 * Backfill window picker. Health Connect only serves about 30 days of
 * history to an app without the history-read permission, hence the cap.
 * The durations stay index-aligned with R.array.backfill_windows.
 */
@Composable
private fun BackfillDialog(
    onDismiss: () -> Unit,
    onWindowSelected: (Duration) -> Unit,
) {
    val backfillWindows = listOf(
        Duration.ofDays(1),
        Duration.ofDays(7),
        Duration.ofDays(30),
    )
    val labels = stringArrayResource(R.array.backfill_windows)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backfill_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                labels.forEachIndexed { index, label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable { onWindowSelected(backfillWindows[index]) }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun formatDateTime(epochMs: Long): String {
    val formatter = remember {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
    }
    return remember(epochMs) { formatter.format(Date(epochMs)) }
}
