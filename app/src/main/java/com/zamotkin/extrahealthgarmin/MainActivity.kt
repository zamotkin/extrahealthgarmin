package com.zamotkin.extrahealthgarmin

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.health.connect.client.PermissionController
import com.zamotkin.extrahealthgarmin.health.SyncWorker
import com.zamotkin.extrahealthgarmin.ui.MainViewModel
import com.zamotkin.extrahealthgarmin.ui.MainViewModelFactory
import com.zamotkin.extrahealthgarmin.ui.kit.theme.EhgTheme
import com.zamotkin.extrahealthgarmin.ui.navigation.AppRoot
import com.zamotkin.extrahealthgarmin.ui.omron.OmronActivity
import com.zamotkin.extrahealthgarmin.ui.screens.home.HomeActions

/**
 * Single-activity Compose host (redesign v3, task xlg.1): renders
 * [AppRoot] — the bottom-navigation scaffold (Home / Measurements /
 * Settings) — under the shared [EhgTheme], edge to edge. Owns everything
 * Compose must not: the Activity Result API surfaces (Health Connect
 * permissions, POST_NOTIFICATIONS), the Play Store fallback, the Omron
 * entry intent and the intent deep links; all Home logic stays in
 * [MainViewModel] (see [SyncWorker] for the foreground/background split).
 *
 * [launchMode][android.R.attr.launchMode] is singleTop, so the Health
 * Connect permission-usage rationale alias and the sync-alert
 * notifications reuse the running instance through [onNewIntent] instead
 * of stacking duplicate activities.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /**
         * Launch extra marking a Health Connect permission-usage rationale
         * launch: the VIEW_PERMISSION_USAGE activity-alias in the manifest
         * targets this activity, and a launch carrying this extra answers
         * with the privacy-policy dialog instead of doing nothing extra.
         */
        const val EXTRA_HEALTH_PERMISSIONS_RATIONALE = "health_permissions_rationale"

        /**
         * Launch extra asking the app to open the Garmin login dialog right
         * away — set by the Omron screen's "Sign in to Garmin again" entry
         * (the login dialog lives on the Home tab).
         */
        const val EXTRA_SHOW_GARMIN_LOGIN = "show_garmin_login"

        /**
         * Health Connect provider package — the Play Store link target and
         * the <queries> entry in AndroidManifest.xml.
         */
        private const val HC_PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private const val HC_PROVIDER_PLAY_URL =
            "https://play.google.com/store/apps/details?id=$HC_PROVIDER_PACKAGE"
    }

    // Deep-link UI state read by the Compose tree. The privacy dialog is a
    // plain Boolean; the login dialog request is a one-shot signal — set
    // per delivered EXTRA_SHOW_GARMIN_LOGIN intent, consumed by Home when
    // it opens the dialog (so a later tab switch back cannot re-open it).
    private var privacyDialogVisible by mutableStateOf(false)
    private var loginDialogRequested by mutableStateOf(false)

    // Health Connect permission screen (Activity Result API). connect-client
    // 1.1.0 has no Intent-based request builder — this contract is the
    // official way to launch the permission UI, and its input is the
    // permission set from the ViewModel.
    private val hcPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        viewModel.onHcPermissionsResult(granted)
    }

    // Android 13+ runtime permission for the background-sync problem
    // notifications (SyncAlertNotifier); denial just means no alerts.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // ViewModel
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EhgTheme {
                AppRoot(
                    viewModel = viewModel,
                    homeActions = HomeActions(
                        requestHcPermissions = ::requestHcPermissions,
                        openHealthConnectInPlayStore = ::openHealthConnectInPlayStore,
                        openOmron = { startActivity(Intent(this, OmronActivity::class.java)) },
                        triggerGarminAuthSpike = { GarminAuthSpike.trigger(this) },
                    ),
                    loginDialogRequest = loginDialogRequested,
                    onLoginDialogRequestConsumed = { loginDialogRequested = false },
                    privacyDialogVisible = privacyDialogVisible,
                    onPrivacyDialogDismiss = { privacyDialogVisible = false },
                )
            }
        }

        // Periodic background sync (task 2b): ~3h, network-constrained, KEEP
        // — idempotent across every launch.
        SyncWorker.enqueuePeriodicSync(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() coherent with the latest delivery so a later
        // recreation replays the same deep link.
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onScreenResume()
    }

    /**
     * Dispatches the deep-link extras once per delivered intent — from
     * [onCreate] for a fresh instance and from [onNewIntent] when the
     * singleTop instance is reused: the rationale alias opens the
     * privacy-policy dialog, the Omron re-login entry asks Home for the
     * Garmin login dialog.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_HEALTH_PERMISSIONS_RATIONALE, false) == true) {
            privacyDialogVisible = true
        }
        if (intent?.getBooleanExtra(EXTRA_SHOW_GARMIN_LOGIN, false) == true) {
            loginDialogRequested = true
        }
    }

    /** Launches the Health Connect permission screen for the required set. */
    private fun requestHcPermissions() {
        try {
            hcPermissionLauncher.launch(viewModel.permissionsToRequest())
        } catch (e: ActivityNotFoundException) {
            // The provider apk vanished between the availability check and
            // this tap — fall back to the store.
            openHealthConnectInPlayStore()
        }
    }

    /**
     * Opens the Health Connect provider page in Play Store — the action for
     * both NotInstalled and NeedsUpdate. The market:// scheme first, the
     * https URL as the fallback for devices without the Play app.
     */
    private fun openHealthConnectInPlayStore() {
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            "market://details?id=$HC_PROVIDER_PACKAGE".toUri()
        )
        try {
            startActivity(marketIntent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, HC_PROVIDER_PLAY_URL.toUri()))
        }
    }
}
