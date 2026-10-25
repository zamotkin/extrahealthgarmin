package com.zamotkin.extrahealthgarmin.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zamotkin.extrahealthgarmin.R
import com.zamotkin.extrahealthgarmin.ui.MainViewModel
import com.zamotkin.extrahealthgarmin.ui.screens.devices.AddSourceFlowScreen
import com.zamotkin.extrahealthgarmin.ui.screens.devices.SourceDetailScreen
import com.zamotkin.extrahealthgarmin.ui.screens.devices.SourceListScreen
import com.zamotkin.extrahealthgarmin.ui.screens.home.HomeActions
import com.zamotkin.extrahealthgarmin.ui.screens.home.HomeScreen
import com.zamotkin.extrahealthgarmin.ui.screens.measurements.MeasurementsScreen
import com.zamotkin.extrahealthgarmin.ui.screens.settings.SettingsScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_MEASUREMENTS = "measurements"
private const val ROUTE_SETTINGS = "settings"

// Add-source flow + source management (task xlg.3): sub-screens of the
// Settings tab, above the bottom bar's own destinations.
private const val ROUTE_SOURCES = "sources"
private const val ROUTE_ADD_SOURCE = "add-source"
private const val SOURCE_DETAIL_ARG = "sourceId"
private const val ROUTE_SOURCE_DETAIL = "source-detail"
private const val ROUTE_SOURCE_DETAIL_PATTERN = "$ROUTE_SOURCE_DETAIL/{$SOURCE_DETAIL_ARG}"

private data class EhgDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

private val destinations = listOf(
    EhgDestination(ROUTE_HOME, R.string.nav_home, Icons.Filled.Home),
    EhgDestination(ROUTE_MEASUREMENTS, R.string.nav_measurements, Icons.AutoMirrored.Filled.List),
    EhgDestination(ROUTE_SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

/**
 * App scaffold (redesign v3, task xlg.1): one Scaffold with the bottom
 * [NavigationBar] (Home / Measurements / Settings, labels always visible)
 * over a three-destination [NavHost] — the single-activity layout every
 * later screen plugs into. The Home tab hosts the migrated weigh-in sync
 * UI; Measurements and Settings render their interim content for now.
 *
 * The privacy-policy dialog is app-level: the Health Connect
 * permission-usage rationale deep link (and only it) opens it here, over
 * whichever tab is active.
 */
@Composable
fun AppRoot(
    viewModel: MainViewModel,
    homeActions: HomeActions,
    loginDialogRequest: Boolean,
    onLoginDialogRequestConsumed: () -> Unit,
    privacyDialogVisible: Boolean,
    onPrivacyDialogDismiss: () -> Unit,
) {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = { EhgBottomBar(navController) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(ROUTE_HOME) {
                HomeScreen(
                    viewModel = viewModel,
                    actions = homeActions,
                    loginDialogRequest = loginDialogRequest,
                    onLoginDialogRequestConsumed = onLoginDialogRequestConsumed,
                )
            }
            composable(ROUTE_MEASUREMENTS) {
                MeasurementsScreen()
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    onOpenSources = { navController.navigate(ROUTE_SOURCES) },
                    onAddSource = { navController.navigate(ROUTE_ADD_SOURCE) },
                )
            }
            composable(ROUTE_SOURCES) {
                SourceListScreen(
                    onBack = { navController.navigateUp() },
                    onOpenSource = { sourceId ->
                        navController.navigate("$ROUTE_SOURCE_DETAIL/$sourceId")
                    },
                    onAddSource = { navController.navigate(ROUTE_ADD_SOURCE) },
                )
            }
            composable(
                route = ROUTE_SOURCE_DETAIL_PATTERN,
                arguments = listOf(navArgument(SOURCE_DETAIL_ARG) { type = NavType.StringType }),
            ) { entry ->
                SourceDetailScreen(
                    sourceId = entry.arguments?.getString(SOURCE_DETAIL_ARG).orEmpty(),
                    onBack = { navController.navigateUp() },
                    onRemoved = { navController.navigateUp() },
                )
            }
            composable(ROUTE_ADD_SOURCE) {
                AddSourceFlowScreen(
                    onBack = { navController.navigateUp() },
                    onFinished = { navController.popBackStack() },
                )
            }
        }
    }
    if (privacyDialogVisible) {
        PrivacyPolicyDialog(onDismiss = onPrivacyDialogDismiss)
    }
}

@Composable
private fun EhgBottomBar(navController: NavHostController) {
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

/**
 * Privacy-policy dialog for the VIEW_PERMISSION_USAGE rationale launch: a
 * plain scrollable text, EN/RU via R.string.
 */
@Composable
private fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hc_privacy_policy_title)) },
        text = {
            Text(
                text = stringResource(R.string.hc_privacy_policy_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
    )
}
