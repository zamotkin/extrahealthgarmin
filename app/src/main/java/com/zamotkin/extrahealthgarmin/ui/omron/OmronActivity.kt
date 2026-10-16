package com.zamotkin.extrahealthgarmin.ui.omron

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zamotkin.extrahealthgarmin.MainActivity
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.omron.ble.requiredBlePermissions

/**
 * Compose host of the Omron blood-pressure feature (task tefal2garmin-mnb):
 * one activity, three destinations (readings dashboard / monitor transfer /
 * settings) in a [NavHost] with a bottom navigation bar. Foreground-
 * interactive only — nothing here enqueues background work; the ViewModel
 * cancels its in-flight pass in onCleared and the BLE client owns GATT
 * cleanup.
 *
 * Runtime Bluetooth permissions are requested through the Activity Result
 * API (mirroring MainActivity's Health Connect launcher): the transfer
 * screen surfaces the missing set from the ViewModel state and this activity
 * launches the system dialog for [requiredBlePermissions] (BLUETOOTH_SCAN +
 * BLUETOOTH_CONNECT on Android 12+, ACCESS_FINE_LOCATION below).
 */
class OmronActivity : ComponentActivity() {

    private val viewModel: OmronViewModel by viewModels {
        // The re-login callback is wired by the activity: the Garmin login
        // dialog state machine lives in MainActivity, so the entry relaunches
        // it with the login extra (see MainActivity.EXTRA_SHOW_GARMIN_LOGIN).
        OmronViewModelFactory(application, reloginGarmin = ::openGarminLogin)
    }

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onPermissionsResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                OmronApp(
                    viewModel = viewModel,
                    requestBlePermissions = {
                        blePermissionLauncher.launch(
                            requiredBlePermissions(Build.VERSION.SDK_INT).toTypedArray()
                        )
                    }
                )
            }
        }
    }

    private fun openGarminLogin() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SHOW_GARMIN_LOGIN, true)
        )
    }
}

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_TRANSFER = "transfer"
private const val ROUTE_SETTINGS = "settings"

private data class OmronDestination(
    val route: String,
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private val destinations = listOf(
    OmronDestination(ROUTE_DASHBOARD, R.string.omron_tab_readings, Icons.Filled.List),
    OmronDestination(ROUTE_TRANSFER, R.string.omron_tab_transfer, Icons.Filled.Refresh),
    OmronDestination(ROUTE_SETTINGS, R.string.omron_tab_settings, Icons.Filled.Settings),
)

/** Root composable: bottom navigation over the three-destination [NavHost]. */
@Composable
private fun OmronApp(viewModel: OmronViewModel, requestBlePermissions: () -> Unit) {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = { OmronBottomBar(navController) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_DASHBOARD,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(ROUTE_DASHBOARD) {
                DashboardScreen(viewModel = viewModel)
            }
            composable(ROUTE_TRANSFER) {
                PairingFlowScreen(
                    viewModel = viewModel,
                    requestBlePermissions = requestBlePermissions,
                    onOpenReadings = { navController.navigateTab(ROUTE_DASHBOARD) }
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun OmronBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { navController.navigateTab(destination.route) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.labelRes)) }
            )
        }
    }
}

/** Standard bottom-bar navigation: single instance, state saved and restored. */
private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
