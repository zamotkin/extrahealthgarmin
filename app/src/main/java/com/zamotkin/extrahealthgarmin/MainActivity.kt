package com.zamotkin.extrahealthgarmin

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Observer
import com.zamotkin.extrahealthgarmin.health.SyncWorker
import com.zamotkin.extrahealthgarmin.ui.omron.OmronActivity
import com.zamotkin.extrahealthgarmin.ui.GarminUploadStatus
import com.zamotkin.extrahealthgarmin.ui.MainUiState
import com.zamotkin.extrahealthgarmin.ui.MainViewModel
import com.zamotkin.extrahealthgarmin.ui.MainViewModelFactory
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.time.Duration

/**
 * Home screen (Strangler phase 2b): Health Connect status line (install /
 * update / grant-permissions actions), last-sync + pending line, manual
 * "Sync now", and the Garmin connection entry point with its login dialog.
 * The periodic background sync is enqueued here too (see [SyncWorker] for the
 * foreground/background split).
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
         * Launch extra asking MainActivity to open the Garmin login dialog
         * right away — set by the Omron screen's "Sign in to Garmin again"
         * entry (the login dialog state machine lives here).
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

    // Home screen views
    private lateinit var titleText: TextView
    private lateinit var hcStatusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var hcLastSyncText: TextView
    private lateinit var syncNowButton: Button
    private lateinit var backfillButton: Button

    // Garmin connection entry point (task garmin-api-import-j5e.4).
    // Created programmatically - inserted into the existing
    // scrollable column below backfillButton.
    private lateinit var connectGarminButton: Button
    private lateinit var garminConnectedText: TextView

    // Login dialog (AlertDialog + programmatic custom view, no fragments).
    // Nullable: the refs (and the input contents) are cleared on every dialog
    // dismiss — credentials must not outlive the dialog.
    private var loginDialog: AlertDialog? = null
    private var loginEmailInput: EditText? = null
    private var loginPasswordInput: EditText? = null
    private var loginMfaInput: EditText? = null
    private var loginErrorText: TextView? = null

    // Guards so repeated LiveData emissions never re-trigger one-shot actions
    // (dialog lifecycle) — see renderLoginDialog.
    private var lastShowLoginDialog = false
    private var lastGarminConnected = false

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
        setContentView(R.layout.activity_main)

        initializeViews()

        observeViewModel()

        // Periodic background sync (task 2b): ~3h, network-constrained, KEEP
        // — idempotent across every launch.
        SyncWorker.enqueuePeriodicSync(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Launched through the VIEW_PERMISSION_USAGE alias: Health Connect's
        // "see how this app uses your data" lands here. Standard launchMode,
        // so the rationale always arrives through onCreate of a fresh
        // instance; no onNewIntent handling is needed.
        if (intent?.getBooleanExtra(EXTRA_HEALTH_PERMISSIONS_RATIONALE, false) == true) {
            showPrivacyPolicyDialog()
        }

        // Re-login entry from the Omron blood-pressure screen: land on the
        // home screen with the Garmin login dialog already open.
        if (intent?.getBooleanExtra(EXTRA_SHOW_GARMIN_LOGIN, false) == true) {
            showLoginDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onScreenResume()
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this, Observer { state ->
            updateUI(state)
        })
    }

    private fun updateUI(state: MainUiState) {
        renderHcStatus(state)
        renderHcSyncLine(state)
        renderSyncButton(state)
        renderOptionalNotes(state)
        // Upload/login status text wins over the HC lines
        renderUploadStatus(state)
        renderGarminConnection(state)
        renderLoginDialog(state)
    }

    private fun initializeViews() {
        titleText = findViewById(R.id.titleText)
        hcStatusText = findViewById(R.id.hcStatusText)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        hcLastSyncText = findViewById(R.id.hcLastSyncText)
        syncNowButton = findViewById(R.id.syncNowButton)

        backfillButton = findViewById(R.id.backfillButton)

        syncNowButton.setOnClickListener { viewModel.syncNow() }
        backfillButton.setOnClickListener { showBackfillDialog() }

        // Omron blood-pressure entry point (epic tefal2garmin-939): opens
        // the Compose OmronActivity (transfer / readings / settings).
        findViewById<Button>(R.id.omronButton).setOnClickListener {
            startActivity(Intent(this, OmronActivity::class.java))
        }

        // Debug-only live-spike trigger for the Garmin DI OAuth2 auth client
        // (task garmin-api-import-j5e.1): hidden long-press on
        // the status lines. The implementation lives in the debug source set
        // (release links a no-op — the GARMINSPIKE_* BuildConfig fields
        // exist only in debug). Credentials come from BuildConfig fields fed
        // by Gradle properties:
        //   ./gradlew assembleDebug -PGARMINSPIKE_EMAIL=... \
        //     -PGARMINSPIKE_PASSWORD=... [-PGARMINSPIKE_MFA_CODE=...]
        // Watch with: adb logcat -s MainActivity GarminAuth GarminHttp
        if (BuildConfig.DEBUG) {
            // Attached to the HC status text as well because statusText
            // is INVISIBLE until an upload runs and invisible views receive
            // no touch events — the spike must be reachable on a fresh launch.
            hcStatusText.setOnLongClickListener {
                triggerGarminAuthSpike()
                true
            }
            statusText.setOnLongClickListener {
                triggerGarminAuthSpike()
                true
            }
        }

        addGarminConnectionViews()
    }

    /**
     * Builds the "Connect Garmin" entry point in code: an outlined button
     * when no token is stored, and a connected-status label once the
     * ViewModel reports a stored token. Anchored below backfillButton.
     */
    private fun addGarminConnectionViews() {
        val root = (findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout

        connectGarminButton = Button(
            this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            id = View.generateViewId()
            text = getString(R.string.connect_garmin)
            setOnClickListener { showLoginDialog() }
        }
        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(16)
        }
        root.addView(connectGarminButton, root.indexOfChild(backfillButton) + 1, buttonParams)

        garminConnectedText = TextView(this).apply {
            id = View.generateViewId()
            text = getString(R.string.garmin_connected)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            visibility = View.GONE
        }
        val labelParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        }
        root.addView(garminConnectedText, root.indexOfChild(connectGarminButton) + 1, labelParams)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ //
    //  Health Connect home-screen rendering                               //
    // ------------------------------------------------------------------ //

    /**
     * HC status line: checking / missing-or-outdated (tap opens the provider
     * in Play Store) / needs permissions (tap launches the HC permission
     * screen) / connected (no action).
     */
    private fun renderHcStatus(state: MainUiState) {
        when {
            state.hcAvailable == null -> {
                hcStatusText.text = getString(R.string.hc_status_checking)
                hcStatusText.isClickable = false
                hcStatusText.setOnClickListener(null)
            }
            !state.hcAvailable -> {
                hcStatusText.text = getString(R.string.hc_status_unavailable)
                hcStatusText.setOnClickListener { openHealthConnectInPlayStore() }
            }
            !state.hcPermissionsGranted -> {
                hcStatusText.text = getString(R.string.hc_status_needs_permission)
                hcStatusText.setOnClickListener { requestHcPermissions() }
            }
            else -> {
                hcStatusText.text = getString(R.string.hc_status_ready)
                hcStatusText.isClickable = false
                hcStatusText.setOnClickListener(null)
            }
        }
    }

    /**
     * Blood pressure / water notes: the "tap to allow" hint and the tap itself
     * only while that read is missing — once granted the permission screen
     * returns at once, so a tap would look dead.
     */
    private fun renderOptionalNotes(state: MainUiState) {
        renderOptionalNote(R.id.bloodPressureNote, R.string.bp_note, R.string.bp_note_tap, state.hcBloodPressureGranted)
        renderOptionalNote(R.id.hydrationNote, R.string.hydration_note, R.string.hydration_note_tap, state.hcHydrationGranted)
    }

    private fun renderOptionalNote(viewId: Int, noteRes: Int, tapRes: Int, granted: Boolean) {
        val note = findViewById<TextView>(viewId)
        if (granted) {
            note.text = getString(noteRes)
            note.setOnClickListener(null)
            note.isClickable = false
        } else {
            note.text = getString(noteRes) + " " + getString(tapRes)
            note.setOnClickListener { requestHcPermissions() }
        }
    }

    /** Last sync timestamp + the pending line (only when something waits). */
    private fun renderHcSyncLine(state: MainUiState) {
        val lastSync = state.hcLastSyncEpochMs
        val text = buildString {
            append(
                if (lastSync != null) {
                    getString(R.string.hc_last_sync, formatDateTime(lastSync))
                } else {
                    getString(R.string.hc_never_synced)
                }
            )
            if (state.hcPendingCount > 0) {
                append('\n')
                append(getString(R.string.hc_pending_count, state.hcPendingCount))
            }
        }
        hcLastSyncText.text = text
    }

    private fun renderSyncButton(state: MainUiState) {
        syncNowButton.isEnabled =
            state.hcAvailable == true && state.hcPermissionsGranted && !state.hcSyncRunning
        backfillButton.isEnabled = syncNowButton.isEnabled
    }

    /**
     * Backfill window picker. Health Connect only serves about 30 days of
     * history to an app without the history-read permission, hence the cap.
     */
    private fun showBackfillDialog() {
        val windows = listOf(Duration.ofDays(1), Duration.ofDays(7), Duration.ofDays(30))
        AlertDialog.Builder(this)
            .setTitle(R.string.backfill_title)
            .setItems(resources.getStringArray(R.array.backfill_windows)) { _, which ->
                viewModel.backfill(windows[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatDateTime(epochMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
            .format(Date(epochMs))

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

    /**
     * Privacy-policy dialog shown for the VIEW_PERMISSION_USAGE rationale
     * launch: a plain scrollable TextView, EN/RU via R.string.
     */
    private fun showPrivacyPolicyDialog() {
        val padding = dp(20)
        val textView = TextView(this).apply {
            text = getString(R.string.hc_privacy_policy_body)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val scrollView = ScrollView(this).apply {
            addView(textView)
            setPadding(padding, padding / 2, padding, 0)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hc_privacy_policy_title))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.dialog_ok), null)
            .show()
    }

    // ------------------------------------------------------------------ //
    //  Garmin connection + upload rendering (unchanged flows)             //
    // ------------------------------------------------------------------ //

    /** Button vs connected-label visibility follows the stored-token state. */
    private fun renderGarminConnection(state: MainUiState) {
        connectGarminButton.visibility = if (state.garminConnected) View.GONE else View.VISIBLE
        garminConnectedText.visibility = if (state.garminConnected) View.VISIBLE else View.GONE
    }

    /**
     * Maps the ViewModel upload status to status strings. No outcome ever
     * writes a file or opens a browser — failed records stay retriable for
     * the next sync.
     */
    private fun renderUploadStatus(state: MainUiState) {
        when (state.uploadStatus) {
            null -> {
                progressBar.visibility = View.INVISIBLE
                statusText.visibility = View.INVISIBLE
            }
            GarminUploadStatus.UPLOADING -> {
                progressBar.visibility = View.VISIBLE
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.upload_in_progress)
            }
            GarminUploadStatus.SUCCESS -> showStatus(
                getString(R.string.upload_success, state.uploadedCount)
            )
            GarminUploadStatus.NOT_NEEDED -> showStatus(getString(R.string.upload_not_needed))
            GarminUploadStatus.PARTIAL -> showStatus(
                buildString {
                    append(getString(R.string.upload_partial, state.uploadedCount, state.totalRecords))
                    state.uploadMessages.firstOrNull { it.isNotBlank() }?.let {
                        append("\n").append(it)
                    }
                }
            )
            GarminUploadStatus.RETRYABLE -> showStatus(
                buildString {
                    append(getString(R.string.upload_retryable))
                    state.uploadMessages.firstOrNull { it.isNotBlank() }?.let {
                        append("\n").append(it)
                    }
                }
            )
            GarminUploadStatus.LOGIN_REQUIRED -> showStatus(getString(R.string.login_required))
        }
    }

    private fun showStatus(text: String) {
        progressBar.visibility = View.INVISIBLE
        statusText.text = text
        statusText.visibility = View.VISIBLE
    }

    /**
     * Debug-only live spike for the Garmin DI OAuth2 auth client — the real
     * implementation lives in the debug source set (needs the debug-only
     * GARMINSPIKE_* BuildConfig fields); release builds link a no-op.
     */
    private fun triggerGarminAuthSpike() {
        GarminAuthSpike.trigger(this)
    }

    /**
     * Owns the login AlertDialog lifecycle across LiveData emissions: opens it
     * when the ViewModel asks (upload hit LoginRequired or the Connect button
     * was tapped), updates the MFA field / inline error in place, and closes
     * it when the ViewModel reports a closed dialog or a successful login.
     */
    private fun renderLoginDialog(state: MainUiState) {
        val closeDueToState =
            (lastShowLoginDialog && !state.showLoginDialog) ||
                (state.garminConnected && !lastGarminConnected)
        if (closeDueToState && loginDialog?.isShowing == true) {
            loginDialog?.dismiss()
        }

        // Drop stale references (user dismissed via back/outside touch); the
        // dismiss listener also clears the input fields and their refs.
        if (loginDialog != null && loginDialog?.isShowing != true) {
            dropLoginDialog()
        }

        if (state.showLoginDialog && loginDialog?.isShowing != true) {
            showLoginDialog()
        }

        lastShowLoginDialog = state.showLoginDialog
        lastGarminConnected = state.garminConnected

        val dialog = loginDialog ?: return
        val mfaInput = loginMfaInput ?: return
        val errorText = loginErrorText ?: return
        mfaInput.visibility = if (state.loginNeedsMfa) View.VISIBLE else View.GONE
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility =
            if (state.loginNeedsMfa) View.VISIBLE else View.GONE
        errorText.text = state.loginError
            ?.let { getString(R.string.login_failed, it) }
            ?: ""
        errorText.visibility = if (state.loginError == null) View.GONE else View.VISIBLE
    }

    /**
     * Clears every login input (email/password/MFA), drops the Activity's
     * field references and the dialog ref. Runs on every dialog dismiss —
     * cancel, back/outside touch, and success all end in a dismiss — so
     * credentials never outlive the dialog.
     */
    private fun dropLoginDialog() {
        loginEmailInput?.setText("")
        loginPasswordInput?.setText("")
        loginMfaInput?.setText("")
        loginEmailInput = null
        loginPasswordInput = null
        loginMfaInput = null
        loginErrorText = null
        loginDialog = null
    }

    /**
     * Login dialog: email + password, then a verification-code field once the
     * ViewModel reports NeedsMfa. Credentials live only in the dialog's own
     * input fields and go straight into the login call — nothing is stored.
     */
    private fun showLoginDialog() {
        val padding = dp(20)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        // Local non-null handles: the fields are nullable (cleared on
        // dismiss), but inside this builder they are freshly assigned.
        val emailInput = EditText(this).apply {
            hint = getString(R.string.login_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passwordInput = EditText(this).apply {
            hint = getString(R.string.login_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod()
        }
        val mfaInput = EditText(this).apply {
            hint = getString(R.string.mfa_code_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            visibility = View.GONE
        }
        val errorText = TextView(this).apply {
            visibility = View.GONE
        }
        loginEmailInput = emailInput
        loginPasswordInput = passwordInput
        loginMfaInput = mfaInput
        loginErrorText = errorText
        container.addView(emailInput)
        container.addView(passwordInput)
        container.addView(mfaInput)
        container.addView(errorText)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.login_title))
            .setView(container)
            .setPositiveButton(getString(R.string.login_submit), null)
            .setNeutralButton(getString(R.string.resend_code), null)
            .setNegativeButton(getString(R.string.login_cancel), null)
            .setOnCancelListener { viewModel.onLoginCancelled() }
            .create()
        // Every exit path (cancel button, back, outside touch, success) ends
        // in a dismiss — clear the inputs and drop the refs there.
        dialog.setOnDismissListener { dropLoginDialog() }
        dialog.show()

        // Custom click listeners (installed after show()) replace the default
        // dismiss-on-click so NeedsMfa/Failed keep the dialog open with the
        // entered credentials intact.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val mfaCode = mfaInput.text.toString().trim().takeIf { it.isNotEmpty() }
            viewModel.loginToGarmin(
                emailInput.text.toString().trim(),
                passwordInput.text.toString(),
                mfaCode
            )
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            // Resend = re-run login with the same credentials and no code
            viewModel.loginToGarmin(
                emailInput.text.toString().trim(),
                passwordInput.text.toString(),
                null
            )
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            dialog.cancel() // fires the cancel listener -> onLoginCancelled
        }
        loginDialog = dialog
    }
}
