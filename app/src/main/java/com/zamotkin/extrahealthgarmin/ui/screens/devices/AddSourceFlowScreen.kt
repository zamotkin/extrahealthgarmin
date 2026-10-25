package com.zamotkin.extrahealthgarmin.ui.screens.devices

import android.app.Application
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.devices.api.DiscoveredBleDevice
import com.zamotkin.extrahealthgarmin.ui.kit.components.EhgButton
import com.zamotkin.extrahealthgarmin.ui.kit.components.EmptyState
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionCard
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionHeader
import com.zamotkin.extrahealthgarmin.ui.kit.components.StatusRow

/**
 * The guided add-source flow (task tefal2garmin-app-redesign-v3-xlg.3):
 * one destination rendering the [AddSourceUiState] machine - type picker,
 * BLE permissions gate, live scan, per-type pair step, save, done. All
 * logic stays in [AddSourceViewModel]; this file renders state and
 * forwards taps. The two Activity-Result launchers the flow needs (BLE
 * runtime permissions, the Health Connect grant) stay in Compose via
 * rememberLauncherForActivityResult - the single-activity rule of the
 * epic, mirroring MainActivity's and OmronActivity's activity-owned
 * launchers (raw material: ui/omron's PairingFlowScreen and OmronActivity).
 */
@Composable
fun AddSourceFlowScreen(
    onBack: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: AddSourceViewModel = viewModel(factory = AddSourceViewModelFactory(application))
    val state by viewModel.uiState.observeAsState()
    val current = state ?: AddSourceUiState.TypePicker(emptyList())

    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> viewModel.onBlePermissionsResult(granted) }

    val hcPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted -> viewModel.onHcPermissionsResult(granted) }

    var pairingKey by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        BackHeader(title = stringResource(R.string.add_source_title), onBack = onBack)
        Spacer(modifier = Modifier.height(16.dp))
        when (val s = current) {
            is AddSourceUiState.TypePicker -> TypePickerContent(s, viewModel)

            is AddSourceUiState.BlePermissionsRequired -> BlePermissionsContent(
                state = s,
                onGrant = { blePermissionLauncher.launch(s.permissions.toTypedArray()) },
            )

            is AddSourceUiState.Scanning -> ScanContent(s, viewModel)

            is AddSourceUiState.Found -> FoundContent(s, viewModel)

            is AddSourceUiState.Pairing -> PairingContent(
                state = s,
                key = pairingKey,
                onKeyChanged = {
                    pairingKey = it
                    if (s.keyError) viewModel.clearKeyError()
                },
                onSubmitKey = { viewModel.submitPairingKey(pairingKey) },
                onSave = viewModel::saveSource,
                onGrantHc = {
                    try {
                        hcPermissionLauncher.launch(viewModel.hcPermissionsToRequest())
                    } catch (e: ActivityNotFoundException) {
                        // The provider apk vanished between the status check
                        // and this tap - an honest error, not a crash (the
                        // Home tab keeps the Play Store fallback).
                        viewModel.onHcPermissionsUnavailable()
                    }
                },
            )

            AddSourceUiState.Saving -> SavingContent()

            is AddSourceUiState.Done -> DoneContent(onFinished)

            is AddSourceUiState.Error -> ErrorContent(s, viewModel)
        }
    }
}

@Composable
private fun TypePickerContent(state: AddSourceUiState.TypePicker, viewModel: AddSourceViewModel) {
    Text(
        text = stringResource(R.string.add_source_pick_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(16.dp))
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        state.options.forEach { option ->
            StatusRow(
                label = stringResource(option.nameRes),
                value = stringResource(
                    if (option.isBle) R.string.add_source_ble_hint
                    else R.string.add_source_hc_hint
                ),
                onClick = { viewModel.onSelectOption(option) },
            )
        }
    }
}

@Composable
private fun BlePermissionsContent(state: AddSourceUiState.BlePermissionsRequired, onGrant: () -> Unit) {
    SectionHeader(text = stringResource(R.string.add_source_ble_permissions_title))
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(
            if (state.denied) R.string.add_source_ble_permissions_denied_text
            else R.string.add_source_ble_permissions_text
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(modifier = Modifier.height(16.dp))
    EhgButton(
        text = stringResource(R.string.add_source_ble_permissions_grant),
        onClick = onGrant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ScanContent(state: AddSourceUiState.Scanning, viewModel: AddSourceViewModel) {
    SectionHeader(text = stringResource(R.string.add_source_scanning))
    Spacer(modifier = Modifier.height(8.dp))
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.add_source_scan_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(16.dp))
    DeviceList(devices = state.devices, onDevice = viewModel::selectDevice)
    Spacer(modifier = Modifier.height(16.dp))
    EhgButton(
        text = stringResource(R.string.add_source_stop_scan),
        onClick = viewModel::stopScan,
        tonal = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FoundContent(state: AddSourceUiState.Found, viewModel: AddSourceViewModel) {
    DeviceList(devices = state.devices, onDevice = viewModel::selectDevice)
    Spacer(modifier = Modifier.height(16.dp))
    EhgButton(
        text = stringResource(R.string.add_source_scan_again),
        onClick = viewModel::scanAgain,
        tonal = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DeviceList(devices: List<DiscoveredBleDevice>, onDevice: (DiscoveredBleDevice) -> Unit) {
    if (devices.isEmpty()) {
        SectionCard(modifier = Modifier.fillMaxWidth()) {
            EmptyState(
                title = stringResource(R.string.add_source_no_devices),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        SectionCard(modifier = Modifier.fillMaxWidth()) {
            devices.forEach { device ->
                StatusRow(
                    // Advertised names can be empty or non-ASCII - the
                    // address always identifies the device.
                    label = device.name.ifEmpty { device.address },
                    value = device.address,
                    trailingStatus = stringResource(R.string.add_source_rssi, device.rssi),
                    onClick = { onDevice(device) },
                )
            }
        }
    }
}

@Composable
private fun PairingContent(
    state: AddSourceUiState.Pairing,
    key: String,
    onKeyChanged: (String) -> Unit,
    onSubmitKey: () -> Unit,
    onSave: () -> Unit,
    onGrantHc: () -> Unit,
) {
    SectionHeader(
        text = stringResource(
            R.string.add_source_pair_title,
            stringResource(state.option.nameRes)
        )
    )
    Spacer(modifier = Modifier.height(16.dp))
    state.device?.let { device ->
        SectionCard(modifier = Modifier.fillMaxWidth()) {
            StatusRow(
                label = device.name.ifEmpty { device.address },
                value = device.address,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
    when {
        state.option.requiresPairingKey -> {
            Text(
                text = stringResource(R.string.add_source_key_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = key,
                onValueChange = onKeyChanged,
                label = { Text(text = stringResource(R.string.add_source_key_label)) },
                isError = state.keyError,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.keyError) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.add_source_key_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            EhgButton(
                text = stringResource(R.string.add_source_save),
                onClick = onSubmitKey,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        state.option.isBle -> {
            // Standard BLE monitors (0x1810, task xlg.9) pair through the
            // system bonding dialog on their first read - nothing to enter.
            Text(
                text = stringResource(R.string.add_source_bonding_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            EhgButton(
                text = stringResource(R.string.add_source_save),
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        else -> {
            // Singleton source (Health Connect): the grant IS the connect step.
            if (state.permissionDenied) {
                Text(
                    text = stringResource(R.string.add_source_hc_denied_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = stringResource(R.string.add_source_hc_grant_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (state.permissionGranted) {
                EhgButton(
                    text = stringResource(R.string.add_source_save),
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                EhgButton(
                    text = stringResource(R.string.add_source_hc_grant),
                    onClick = onGrantHc,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SavingContent() {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.add_source_saving),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun DoneContent(onFinished: () -> Unit) {
    EmptyState(
        title = stringResource(R.string.add_source_done_title),
        text = stringResource(R.string.add_source_done_text),
        icon = Icons.Filled.CheckCircle,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(24.dp))
    EhgButton(
        text = stringResource(R.string.add_source_done),
        onClick = onFinished,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorContent(state: AddSourceUiState.Error, viewModel: AddSourceViewModel) {
    EmptyState(
        title = stringResource(state.messageRes),
        text = state.detail,
        icon = Icons.Filled.Warning,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(24.dp))
    EhgButton(
        text = stringResource(R.string.add_source_start_over),
        onClick = viewModel::reset,
        modifier = Modifier.fillMaxWidth(),
    )
}
