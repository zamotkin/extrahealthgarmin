package com.zamotkin.extrahealthgarmin.ui.screens.home

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.ui.GarminUploadStatus
import com.zamotkin.extrahealthgarmin.ui.MainUiState
import com.zamotkin.extrahealthgarmin.ui.MainViewModel
import com.zamotkin.extrahealthgarmin.ui.kit.components.EhgButton
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionCard
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionHeader
import com.zamotkin.extrahealthgarmin.ui.kit.components.StatusRow
import com.zamotkin.extrahealthgarmin.ui.kit.components.UploadSummaryText
import com.zamotkin.extrahealthgarmin.ui.uploadSummary
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Home tab (redesign v3; slimmed to weigh-in essentials by task
 * tefal2garmin-app-redesign-v3-xlg.7, epic R7): one status card — Health
 * Connect status, Garmin account status, the running/last upload pass and
 * the last-sync line — plus the single action "Sync now". Every service
 * function moved to the Settings groups: the Garmin connect entry and
 * sign-out (Settings → Garmin Connect), the Health Connect permission
 * grant, manage-access link, permission notes and the backfill picker
 * (Settings → Health Connect). The status rows are deliberately NOT
 * tappable — the screen exposes no action except Sync now. All logic stays
 * in [MainViewModel]; this file only renders [MainUiState].
 *
 * [hcSyncError] is deliberately not rendered (as before); [uploadStatus]
 * keeps the existing overloaded Garmin-upload/HC-sync mapping via
 * [uploadSummary].
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.observeAsState()
    val s = state ?: MainUiState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionHeader(text = stringResource(R.string.app_name), centered = true)
        Spacer(modifier = Modifier.height(16.dp))

        SectionCard(modifier = Modifier.fillMaxWidth()) {
            HcStatusLine(s)
            GarminStatusLine(s)
            UploadStatusSection(s)
            LastSyncLine(s)
        }
        Spacer(modifier = Modifier.height(16.dp))

        val canSync =
            s.hcAvailable == true && s.hcPermissionsGranted && !s.hcSyncRunning
        // Haptic confirm on the primary action (task xlg.8): the tap that
        // starts the sync pass acknowledges itself.
        val haptics = LocalHapticFeedback.current
        EhgButton(
            text = stringResource(R.string.sync_now),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                viewModel.syncNow()
            },
            enabled = canSync,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Health Connect status line: checking / missing-or-outdated / needs
 * permissions / connected. Pure status — the install/grant actions live in
 * the Settings Health Connect group (R7: no actions on Home).
 */
@Composable
private fun HcStatusLine(s: MainUiState) {
    data class HcStatus(val labelRes: Int, val icon: ImageVector)

    val status = when {
        s.hcAvailable == null -> HcStatus(R.string.hc_status_checking, Icons.Filled.Refresh)
        !s.hcAvailable -> HcStatus(R.string.hc_status_unavailable, Icons.Filled.Warning)
        !s.hcPermissionsGranted ->
            HcStatus(R.string.hc_status_needs_permission, Icons.Filled.Warning)
        else -> HcStatus(R.string.hc_status_ready, Icons.Filled.CheckCircle)
    }
    StatusRow(
        label = stringResource(status.labelRes),
        leadingIcon = status.icon,
    )
}

/** Garmin account status line: connected / not signed in (actions live in Settings). */
@Composable
private fun GarminStatusLine(s: MainUiState) {
    StatusRow(
        label = stringResource(
            if (s.garminConnected) R.string.garmin_connected else R.string.garmin_not_connected
        ),
        leadingIcon = if (s.garminConnected) Icons.Filled.CheckCircle else Icons.Filled.Warning,
    )
}

/**
 * Upload/sync status — the shared [uploadSummary] mapping (the Settings
 * Garmin group renders the same line as "last upload"): progress plus a
 * note while in flight, one outcome line for every terminal state, nothing
 * before the first pass.
 */
@Composable
private fun UploadStatusSection(s: MainUiState) {
    if (s.uploadStatus == GarminUploadStatus.UPLOADING) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(4.dp))
    }
    s.uploadSummary()?.let { summary ->
        UploadSummaryText(
            summary = summary,
            centered = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
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

@Composable
private fun formatDateTime(epochMs: Long): String {
    val formatter = remember {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
    }
    return remember(epochMs) { formatter.format(Date(epochMs)) }
}
