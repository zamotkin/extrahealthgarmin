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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.livedata.observeAsState
import com.zamotkin.extrahealthgarmin.R

/**
 * Transfer screen (task tefal2garmin-mnb): numbered monitor-side pairing
 * instructions, then the Start button. While a pass runs, a progress line
 * stands in for granular stages (the BLE facade reports only terminal
 * results); every terminal state renders its own message + action.
 *
 * [requestBlePermissions] launches the runtime Bluetooth permission request
 * (Activity Result API — owned by [OmronActivity]); the screen never touches
 * permissions itself.
 */
@Composable
fun PairingFlowScreen(
    viewModel: OmronViewModel,
    requestBlePermissions: () -> Unit,
    onOpenReadings: () -> Unit,
) {
    val state by viewModel.uiState.observeAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.omron_title_transfer),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(16.dp))

        val steps = listOf(
            R.string.omron_pairing_step1,
            R.string.omron_pairing_step2,
            R.string.omron_pairing_step3
        )
        steps.forEachIndexed { index, stepRes ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(24.dp)
                )
                Text(
                    text = stringResource(stepRes),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (val current = state) {
            OmronUiState.Idle, null -> Button(
                onClick = { viewModel.syncNow() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.omron_start))
            }

            is OmronUiState.PermissionsMissing -> {
                Text(
                    text = stringResource(R.string.omron_permissions_rationale),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = requestBlePermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.omron_permissions_grant))
                }
            }

            OmronUiState.Reading -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.omron_reading),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            is OmronUiState.Pairing -> {
                Text(
                    text = stringResource(R.string.omron_bonding_required),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.syncNow() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.label_try_again))
                }
            }

            is OmronUiState.Success -> {
                Text(
                    text = if (current.readingsCount > 0) {
                        stringResource(R.string.omron_success, current.readingsCount)
                    } else {
                        stringResource(R.string.omron_success_none)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenReadings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.omron_open_readings))
                }
            }

            is OmronUiState.Error -> {
                Text(
                    text = current.detail?.let {
                        stringResource(current.messageRes) + "\n" + it
                    } ?: stringResource(current.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.syncNow() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.label_try_again))
                }
            }
        }
    }
}
