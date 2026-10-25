package com.zamotkin.extrahealthgarmin.ui.screens.settings

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zamotkin.extrahealthgarmin.BuildConfig
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.devices.store.ConnectedSource
import com.zamotkin.extrahealthgarmin.ui.MainViewModel
import com.zamotkin.extrahealthgarmin.ui.kit.components.EhgButton
import com.zamotkin.extrahealthgarmin.ui.kit.components.LabeledValue
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionCard
import com.zamotkin.extrahealthgarmin.ui.kit.components.SectionHeader
import com.zamotkin.extrahealthgarmin.ui.kit.components.StatusRow
import java.text.DateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale

/** The app's public source repository — the About group's link target. */
private const val SOURCE_URL = "https://github.com/zamotkin/extrahealthgarmin"

/**
 * Health Connect provider package — the Play Store link target (the
 * <queries> entry in AndroidManifest.xml mirrors it).
 */
private const val HC_PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
private const val HC_PROVIDER_PLAY_URL =
    "https://play.google.com/store/apps/details?id=$HC_PROVIDER_PACKAGE"

/** No slot picked yet — the pending per-user delete dialog's sentinel. */
private const val NO_SLOT = -1

/**
 * Settings tab (redesign v3, task tefal2garmin-app-redesign-v3-xlg.7, epic
 * R7): every service function lives here in four groups —
 *
 *  - **Connections**: the connected sources (each row opens its detail),
 *    the sources-management entry, "Add source", and the destructive
 *    delete-readings actions behind confirmation dialogs (the deleted
 *    ui/omron SettingsScreen's semantics; Garmin-uploaded readings are not
 *    removed there);
 *  - **Garmin Connect**: account status, sign-in (the shared login dialog,
 *    rendered app-level), sign-out (only the tokens go), last upload;
 *  - **Health Connect**: the permission status line (the same presentation
 *    as Home), the "Sync with Health Connect" toggle, the "Manage access"
 *    deep link, the relocated backfill picker and permission notes — after
 *    two permission-screen cancellations the grant stops re-prompting and
 *    directs to the Health Connect settings (official HC UX guideline);
 *  - **About**: version, source link, read-only diagnostics (dedup journal
 *    sizes, last sync anchors).
 *
 * All logic stays in [SettingsViewModel] (and, behind it, the shared
 * [MainViewModel]); this file renders the group states and forwards taps.
 */
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    onOpenSources: () -> Unit,
    onOpenSourceDetail: (String) -> Unit,
    onAddSource: () -> Unit,
    onTriggerAuthSpike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: SettingsViewModel =
        viewModel(factory = SettingsViewModelFactory(application, mainViewModel))
    // Re-entering the destination re-reads the store-backed groups: sources
    // added or removed elsewhere, journal sizes after syncs.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val context = LocalContext.current
    val hcPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted -> viewModel.onHcPermissionsResult(granted) }

    val garmin by viewModel.garmin.observeAsState()
    val healthConnect by viewModel.healthConnect.observeAsState()
    val connections by viewModel.connections.observeAsState()
    val about by viewModel.about.observeAsState()
    val hc = healthConnect ?: HcGroupState(
        checking = true, available = false, granted = false,
        bloodPressureGranted = false, hydrationGranted = false,
        syncRunning = false, directToHcSettings = false,
    )

    fun launchHcPermissions() {
        try {
            hcPermissionLauncher.launch(viewModel.hcPermissionsToRequest())
        } catch (e: ActivityNotFoundException) {
            // The provider apk vanished between the availability check and
            // this tap - the store is the honest answer.
            openHealthConnectInPlayStore(context)
        }
    }

    /** The grant action: after two cancellations, Health Connect settings instead. */
    fun requestPermissionsOrManage() {
        if (hc.directToHcSettings) openHcSettings(context) else launchHcPermissions()
    }

    var confirmDeleteSlot by rememberSaveable { mutableStateOf(NO_SLOT) }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    var showBackfill by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionHeader(text = stringResource(R.string.nav_settings))
        Spacer(modifier = Modifier.height(16.dp))

        ConnectionsGroup(
            viewModel = viewModel,
            connections = connections,
            onOpenSources = onOpenSources,
            onOpenSourceDetail = onOpenSourceDetail,
            onAddSource = onAddSource,
            onDeleteForUser = { confirmDeleteSlot = it },
            onDeleteAll = { confirmDeleteAll = true },
        )
        Spacer(modifier = Modifier.height(24.dp))

        GarminGroup(
            garmin = garmin,
            onSignIn = viewModel::requestGarminSignIn,
            onSignOut = viewModel::signOutGarmin,
            onTriggerAuthSpike = onTriggerAuthSpike,
        )
        Spacer(modifier = Modifier.height(24.dp))

        HealthConnectGroup(
            hc = hc,
            onToggleSync = {
                if (hc.granted) {
                    // The app never revokes Health Connect permissions - the
                    // provider's own settings own that.
                    openHcSettings(context)
                } else {
                    requestPermissionsOrManage()
                }
            },
            onManageAccess = { openHcSettings(context) },
            onInstallOrUpdate = { openHealthConnectInPlayStore(context) },
            onBackfill = { showBackfill = true },
            onOptionalPermission = { requestPermissionsOrManage() },
        )
        Spacer(modifier = Modifier.height(24.dp))

        AboutGroup(viewModel = viewModel, about = about)
    }

    if (confirmDeleteSlot != NO_SLOT) {
        val slot = confirmDeleteSlot
        AlertDialog(
            onDismissRequest = { confirmDeleteSlot = NO_SLOT },
            title = { Text(stringResource(R.string.settings_delete_for_user, slot)) },
            text = {
                Text(stringResource(R.string.settings_delete_for_user_confirm, slot))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteSlot = NO_SLOT
                    viewModel.deleteReadingsForUser(slot)
                }) {
                    Text(stringResource(R.string.settings_delete_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSlot = NO_SLOT }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.settings_delete_all)) },
            text = { Text(stringResource(R.string.settings_delete_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    viewModel.deleteAllReadings()
                }) {
                    Text(stringResource(R.string.settings_delete_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
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
}

// --------------------------------------------------------------------- //
//  Connections                                                          //
// --------------------------------------------------------------------- //

@Composable
private fun ConnectionsGroup(
    viewModel: SettingsViewModel,
    connections: ConnectionsGroupState?,
    onOpenSources: () -> Unit,
    onOpenSourceDetail: (String) -> Unit,
    onAddSource: () -> Unit,
    onDeleteForUser: (Int) -> Unit,
    onDeleteAll: () -> Unit,
) {
    val state = connections ?: ConnectionsGroupState(emptyList(), emptyList(), null)
    GroupHeader(text = stringResource(R.string.settings_connections_title))
    Spacer(modifier = Modifier.height(8.dp))
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        if (state.sources.isEmpty()) {
            Text(
                text = stringResource(R.string.sources_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.sources.forEach { source ->
                SourceRow(
                    viewModel = viewModel,
                    source = source,
                    onClick = { onOpenSourceDetail(source.id) },
                )
            }
        }
        // Management entry (the sources list of task xlg.3): remove, sync
        // mode, user attribution - the full per-source detail surface.
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

    // Destructive block (the deleted ui/omron SettingsScreen's semantics):
    // one row per picked device user plus the wipe-everything row, each
    // behind a confirmation dialog. Garmin-uploaded readings are never
    // touched here.
    Spacer(modifier = Modifier.height(16.dp))
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        state.userSlots.forEach { slot ->
            DestructiveRow(
                text = stringResource(R.string.settings_delete_for_user, slot),
                onClick = { onDeleteForUser(slot) },
            )
        }
        DestructiveRow(
            text = stringResource(R.string.settings_delete_all),
            onClick = onDeleteAll,
        )
        when (state.deleteResult) {
            DeleteReadingsResult.DELETED -> Text(
                text = stringResource(R.string.settings_delete_success),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            DeleteReadingsResult.FAILED -> Text(
                text = stringResource(R.string.settings_delete_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            null -> Unit
        }
    }
}

/** One connected source, named by the sources-list rule: device name for BLE sources, the type's display name otherwise. */
@Composable
private fun SourceRow(
    viewModel: SettingsViewModel,
    source: ConnectedSource,
    onClick: () -> Unit,
) {
    val type = viewModel.typeOf(source.sourceTypeId)
    StatusRow(
        label = if (source.address == null) {
            type?.let { stringResource(it.displayNameRes) } ?: source.name
        } else {
            source.name
        },
        value = type?.let { stringResource(it.displayNameRes) },
        onClick = onClick,
    )
}

/** One error-colored destructive row. */
@Composable
private fun DestructiveRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

// --------------------------------------------------------------------- //
//  Garmin Connect                                                       //
// --------------------------------------------------------------------- //

@Composable
private fun GarminGroup(
    garmin: GarminGroupState?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onTriggerAuthSpike: () -> Unit,
) {
    val state = garmin ?: GarminGroupState(signedIn = false, lastUpload = null)
    GroupHeader(text = stringResource(R.string.settings_garmin_title))
    Spacer(modifier = Modifier.height(8.dp))
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        StatusRow(
            label = stringResource(
                if (state.signedIn) R.string.garmin_connected
                else R.string.garmin_not_connected
            ),
            leadingIcon = if (state.signedIn) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            // Debug builds keep the live Garmin auth spike reachable - the
            // long-press of the old Home status line, relocated with the
            // service entries (R7).
            onLongClick = if (BuildConfig.DEBUG) onTriggerAuthSpike else null,
        )
        state.lastUpload?.let { lastUpload ->
            Spacer(modifier = Modifier.height(4.dp))
            LabeledValue(
                label = stringResource(R.string.settings_garmin_last_upload),
                value = stringResource(lastUpload.messageRes, *lastUpload.args.toTypedArray()),
            )
            lastUpload.failureMessage?.let { failure ->
                Text(
                    text = failure,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (state.signedIn) {
            EhgButton(
                text = stringResource(R.string.settings_garmin_sign_out),
                onClick = onSignOut,
                tonal = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            EhgButton(
                text = stringResource(R.string.connect_garmin),
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// --------------------------------------------------------------------- //
//  Health Connect                                                       //
// --------------------------------------------------------------------- //

@Composable
private fun HealthConnectGroup(
    hc: HcGroupState,
    onToggleSync: () -> Unit,
    onManageAccess: () -> Unit,
    onInstallOrUpdate: () -> Unit,
    onBackfill: () -> Unit,
    onOptionalPermission: () -> Unit,
) {
    GroupHeader(text = stringResource(R.string.settings_hc_title))
    Spacer(modifier = Modifier.height(8.dp))
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        HcStatusRow(hc = hc)
        if (!hc.available && !hc.checking) {
            Spacer(modifier = Modifier.height(4.dp))
            EhgButton(
                text = stringResource(R.string.settings_hc_install_update),
                onClick = onInstallOrUpdate,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (hc.available) {
            Spacer(modifier = Modifier.height(4.dp))
            SyncToggleRow(hc = hc, onToggle = onToggleSync)
            if (hc.directToHcSettings) {
                Text(
                    text = stringResource(R.string.settings_hc_manual_grant_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            StatusRow(
                label = stringResource(R.string.settings_hc_manage_access),
                onClick = onManageAccess,
            )
            Spacer(modifier = Modifier.height(12.dp))
            EhgButton(
                text = stringResource(R.string.backfill),
                onClick = onBackfill,
                enabled = hc.granted && !hc.syncRunning,
                tonal = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OptionalPermissionNote(
            noteRes = R.string.bp_note,
            tapRes = R.string.bp_note_tap,
            granted = hc.bloodPressureGranted,
            onRequest = onOptionalPermission,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OptionalPermissionNote(
            noteRes = R.string.hydration_note,
            tapRes = R.string.hydration_note_tap,
            granted = hc.hydrationGranted,
            onRequest = onOptionalPermission,
        )
    }
}

/**
 * Health Connect status line — the same presentation as Home's (consistent
 * insufficient-access across entry points). Pure status; the install/update
 * action and the grant live on their own controls below.
 */
@Composable
private fun HcStatusRow(hc: HcGroupState) {
    data class HcStatus(val labelRes: Int, val icon: ImageVector)

    val status = when {
        hc.checking -> HcStatus(R.string.hc_status_checking, Icons.Filled.Refresh)
        !hc.available -> HcStatus(R.string.hc_status_unavailable, Icons.Filled.Warning)
        !hc.granted -> HcStatus(R.string.hc_status_needs_permission, Icons.Filled.Warning)
        else -> HcStatus(R.string.hc_status_ready, Icons.Filled.CheckCircle)
    }
    StatusRow(
        label = stringResource(status.labelRes),
        leadingIcon = status.icon,
    )
}

/**
 * The "Sync with Health Connect" toggle (Health Connect onboarding UX
 * guideline): checked when the foreground read set is granted. Tapping it
 * while granted never revokes anything in-app - Health Connect owns
 * revocation - and while ungranted it requests the permission screen (or,
 * after two cancellations, the provider's settings).
 */
@Composable
private fun SyncToggleRow(hc: HcGroupState, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_hc_sync_toggle),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = hc.granted, onCheckedChange = { onToggle() })
    }
}

/**
 * Blood pressure / water note (relocated from Home): the "tap to allow"
 * hint and the tap itself only while that read is missing - once granted
 * the permission screen returns at once, so a tap would look dead.
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
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (granted) Modifier
                else Modifier.combinedClickable(onClick = onRequest)
            )
            .padding(vertical = 4.dp),
    )
}

// --------------------------------------------------------------------- //
//  About                                                                //
// --------------------------------------------------------------------- //

@Composable
private fun AboutGroup(viewModel: SettingsViewModel, about: AboutGroupState?) {
    val context = LocalContext.current
    val state = about ?: AboutGroupState("", 0, 0, null, emptyList())
    GroupHeader(text = stringResource(R.string.settings_about_title))
    Spacer(modifier = Modifier.height(8.dp))
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        LabeledValue(
            label = stringResource(R.string.settings_about_version),
            value = state.appVersion,
        )
        Spacer(modifier = Modifier.height(12.dp))
        StatusRow(
            label = stringResource(R.string.settings_about_source),
            value = SOURCE_URL,
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri()))
                } catch (e: ActivityNotFoundException) {
                    // No browser - the URL stays readable as the row's value.
                }
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        // Read-only diagnostics: dedup journal sizes and the sync anchors.
        LabeledValue(
            label = stringResource(R.string.settings_diag_weighin_journal),
            value = state.weighInJournalCount.toString(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        LabeledValue(
            label = stringResource(R.string.settings_diag_bp_journal),
            value = state.bpJournalCount.toString(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        LabeledValue(
            label = stringResource(R.string.settings_diag_last_weighin_sync),
            value = state.lastWeighInSyncEpochMs
                ?.let { formatDateTime(it) }
                ?: stringResource(R.string.hc_never_synced),
        )
        state.readAnchors.forEach { anchor ->
            Spacer(modifier = Modifier.height(12.dp))
            LabeledValue(
                label = stringResource(
                    R.string.settings_diag_last_read,
                    anchorDisplayName(viewModel, anchor),
                ),
                value = anchor.anchorEpochSec
                    ?.let { formatDateTime(it * 1000L) }
                    ?: stringResource(R.string.hc_never_synced),
            )
        }
    }
}

/** The anchor row's source name — the sources-list naming rule. */
@Composable
private fun anchorDisplayName(viewModel: SettingsViewModel, anchor: SourceReadAnchor): String {
    val type = viewModel.typeOf(anchor.sourceTypeId)
    return if (anchor.address == null) {
        type?.let { stringResource(it.displayNameRes) } ?: anchor.name
    } else {
        anchor.name
    }
}

// --------------------------------------------------------------------- //
//  Shared bits                                                          //
// --------------------------------------------------------------------- //

/** Group title within the Settings screen (the Connections style of task xlg.3). */
@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Backfill window picker (relocated from Home - it belongs with the
 * weigh-in/Health Connect sync context). Health Connect only serves about
 * 30 days of history to an app without the history-read permission, hence
 * the cap. The durations stay index-aligned with R.array.backfill_windows.
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

/** The Health Connect "Manage access" deep link: the provider's settings screen. */
private fun openHcSettings(context: Context) {
    try {
        context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
    } catch (e: ActivityNotFoundException) {
        // No Health Connect to manage - the store is the honest answer.
        openHealthConnectInPlayStore(context)
    }
}

/**
 * Opens the Health Connect provider page in Play Store — the action for
 * NotInstalled and NeedsUpdate (the market:// scheme first, the https URL
 * as the fallback for devices without the Play app).
 */
private fun openHealthConnectInPlayStore(context: Context) {
    val marketIntent = Intent(
        Intent.ACTION_VIEW,
        "market://details?id=$HC_PROVIDER_PACKAGE".toUri()
    )
    try {
        context.startActivity(marketIntent)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, HC_PROVIDER_PLAY_URL.toUri()))
        } catch (e2: ActivityNotFoundException) {
            // Neither market nor browser - nothing sensible left to open.
        }
    }
}

/** Epoch millis -> a short localized date-time (screen-only formatting). */
@Composable
private fun formatDateTime(epochMs: Long): String {
    val formatter = remember {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
    }
    return remember(epochMs) { formatter.format(Date(epochMs)) }
}
