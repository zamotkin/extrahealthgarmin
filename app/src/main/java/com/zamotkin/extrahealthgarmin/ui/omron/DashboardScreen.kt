package com.zamotkin.extrahealthgarmin.ui.omron

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.livedata.observeAsState
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.omron.BloodPressureReading
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Readings dashboard (task tefal2garmin-mnb): every stored reading of the
 * selected monitor user (sys/dia + pulse + date, MOV/IHB badges), the manual
 * Sync button (same foreground pass as the transfer screen's Start) and the
 * auto-upload toggle. Direct upload only — there is deliberately NO
 * export/share entry anywhere (epic tefal2garmin-939 anti-pattern).
 */
@Composable
fun DashboardScreen(viewModel: OmronViewModel) {
    val state by viewModel.uiState.observeAsState()
    val readings by viewModel.latestReadings.observeAsState(emptyList())
    val autoSync by viewModel.autoSyncEnabled.observeAsState(true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.omron_title_dashboard),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(12.dp))

        // State line — only the terminal/attention states speak here.
        when (val current = state) {
            OmronUiState.Reading -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.omron_reading),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            is OmronUiState.Success -> Text(
                text = if (current.readingsCount > 0) {
                    stringResource(R.string.omron_success, current.readingsCount)
                } else {
                    stringResource(R.string.omron_success_none)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            is OmronUiState.Error -> Text(
                text = current.detail?.let {
                    stringResource(current.messageRes) + "\n" + it
                } ?: stringResource(current.messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            is OmronUiState.PermissionsMissing -> Text(
                text = stringResource(R.string.omron_permissions_rationale),
                style = MaterialTheme.typography.bodyMedium
            )
            is OmronUiState.Pairing -> Text(
                text = stringResource(R.string.omron_bonding_required),
                style = MaterialTheme.typography.bodyMedium
            )
            OmronUiState.Idle, null -> Unit
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.omron_autosync),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = autoSync,
                onCheckedChange = { viewModel.setAutoSync(it) }
            )
        }
        Text(
            text = stringResource(R.string.omron_autosync_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = { viewModel.syncNow() },
            enabled = state != OmronUiState.Reading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.sync_now))
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // Total for the selected slot — the list below is its full stored history.
        Text(
            text = stringResource(R.string.omron_readings_count, readings.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (readings.isEmpty()) {
            Text(
                text = stringResource(R.string.omron_no_readings),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            readings.forEach { reading ->
                ReadingRow(reading)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

/** One reading line: sys/dia + pulse + date on the left, MOV/IHB badges right. */
@Composable
private fun ReadingRow(reading: BloodPressureReading) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    R.string.omron_reading_sys_dia, reading.systolic, reading.diastolic
                ) + "  ·  " + stringResource(R.string.omron_reading_pulse, reading.pulse),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = formatReadingDate(reading.timestampEpochSec),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (reading.mov) {
            ReadingBadge(R.string.omron_badge_mov)
            Spacer(modifier = Modifier.width(4.dp))
        }
        if (reading.ihb) {
            ReadingBadge(R.string.omron_badge_ihb)
        }
    }
}

@Composable
private fun ReadingBadge(labelRes: Int) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun formatReadingDate(timestampEpochSec: Long): String {
    val formatter = remember {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
    }
    return remember(timestampEpochSec) { formatter.format(Date(timestampEpochSec * 1000L)) }
}
