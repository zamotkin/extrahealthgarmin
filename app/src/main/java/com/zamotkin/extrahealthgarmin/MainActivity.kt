package com.zamotkin.extrahealthgarmin

import android.Manifest
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
import com.zamotkin.extrahealthgarmin.health.SyncWorker
import com.zamotkin.extrahealthgarmin.ui.MainViewModel
import com.zamotkin.extrahealthgarmin.ui.MainViewModelFactory
import com.zamotkin.extrahealthgarmin.ui.kit.theme.EhgTheme
import com.zamotkin.extrahealthgarmin.ui.navigation.AppRoot

/**
 * Single-activity Compose host (redesign v3, task xlg.1): renders
 * [AppRoot] — the bottom-navigation scaffold (Home / Measurements /
 * Settings) — under the shared [EhgTheme], edge to edge. Owns everything
 * Compose must not: the Activity Result API surface for the background
 * sync notifications (POST_NOTIFICATIONS) and the intent deep links; all
 * screen logic stays in the ViewModels (see [SyncWorker] for the
 * foreground/background split). Since task
 * tefal2garmin-app-redesign-v3-xlg.7 the Home tab is status + Sync now
 * only - the Health Connect permission screen, the Play Store fallback
 * and the Health Connect settings deep link are owned by the Settings
 * tab's Compose UI (the add-source flow's launcher pattern).
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
    }

    // Deep-link UI state read by the Compose tree. The privacy dialog is a
    // plain Boolean; the Garmin login dialog needs no launch signal - it
    // opens in-app from the Settings sign-in entry or a LoginRequired pass
    // (rendered at the app root, task xlg.7).
    private var privacyDialogVisible by mutableStateOf(false)

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
                    triggerGarminAuthSpike = { GarminAuthSpike.trigger(this) },
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
     * privacy-policy dialog.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_HEALTH_PERMISSIONS_RATIONALE, false) == true) {
            privacyDialogVisible = true
        }
    }
}
