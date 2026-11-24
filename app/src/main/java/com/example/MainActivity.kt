package com.example

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.hardware.BatteryAlertManager
import com.example.domain.ExportFormat
import com.example.ui.components.ExportReportBottomSheet
import com.example.ui.components.SystemSettingsIntents
import com.example.ui.screens.CausesScreen
import com.example.ui.screens.InspectorScreen
import com.example.ui.screens.LiveScreen
import com.example.ui.screens.TimelineScreen
import com.example.ui.theme.VoltTraceTheme
import com.example.ui.viewmodel.VoltTraceViewModel

private const val ROUTE_LIVE = "live"
private const val ROUTE_CAUSES = "causes"
private const val ROUTE_TIMELINE = "timeline"
private const val ROUTE_INSPECTOR = "inspector"

private data class NavTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val VoltTraceTabs = listOf(
    NavTab(ROUTE_LIVE, R.string.nav_live, Icons.Filled.ElectricBolt, Icons.Outlined.ElectricBolt),
    NavTab(ROUTE_CAUSES, R.string.nav_causes, Icons.Filled.Analytics, Icons.Outlined.Analytics),
    NavTab(ROUTE_TIMELINE, R.string.nav_timeline, Icons.Filled.Timeline, Icons.Outlined.Timeline),
    NavTab(ROUTE_INSPECTOR, R.string.nav_inspector, Icons.Filled.Memory, Icons.Outlined.Memory)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as VoltTraceApp
        val initialRoute = intent?.getStringExtra(EXTRA_START_ROUTE)
            ?.takeIf { it in setOf(ROUTE_LIVE, ROUTE_CAUSES, ROUTE_TIMELINE, ROUTE_INSPECTOR) }
            ?: ROUTE_LIVE

        setContent {
            val viewModel: VoltTraceViewModel = viewModel(
                factory = VoltTraceViewModel.Factory(app.repository)
            )
            VoltTraceRoot(viewModel = viewModel, initialRoute = initialRoute)
        }
    }

    companion object {
        const val EXTRA_START_ROUTE = "extra_start_route"
    }
}

private enum class PendingNotificationAction {
    NONE,
    ENABLE_PRECISION_MONITOR,
    ENABLE_CRITICAL_ALERTS
}

@Composable
private fun VoltTraceRoot(
    viewModel: VoltTraceViewModel,
    initialRoute: String
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val liveSample by viewModel.liveSample.collectAsStateWithLifecycle()
    val liveSubsystems by viewModel.liveSubsystems.collectAsStateWithLifecycle()
    val oscilloscopeMw by viewModel.oscilloscopeMw.collectAsStateWithLifecycle()
    val windowSamples by viewModel.windowSamples.collectAsStateWithLifecycle()
    val report by viewModel.analysisReport.collectAsStateWithLifecycle()
    val selectedWindow by viewModel.selectedWindow.collectAsStateWithLifecycle()
    val hasUsageAccess by viewModel.hasUsageAccess.collectAsStateWithLifecycle()
    val probeState by viewModel.probeState.collectAsStateWithLifecycle()
    val totalSampleCount by viewModel.totalSampleCount.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val energyMeterInventory by viewModel.energyMeterInventory.collectAsStateWithLifecycle()
    val selectedSpikeReport by viewModel.selectedSpikeReport.collectAsStateWithLifecycle()
    val isInvestigatingSpike by viewModel.isInvestigatingSpike.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: initialRoute

    var showExportSheet by remember { mutableStateOf(false) }
    var pendingPermissionAction by remember { mutableStateOf(PendingNotificationAction.NONE) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        when (pendingPermissionAction) {
            PendingNotificationAction.ENABLE_PRECISION_MONITOR -> {
                // Foreground service still works cleanly even if user declined notification banner
                viewModel.setHighPrecisionMonitor(true)
            }

            PendingNotificationAction.ENABLE_CRITICAL_ALERTS -> {
                viewModel.setCriticalAlertsEnabled(granted)
            }

            PendingNotificationAction.NONE -> Unit
        }
        pendingPermissionAction = PendingNotificationAction.NONE
    }

    val handlePrecisionToggle: (Boolean) -> Unit = { enabled ->
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !BatteryAlertManager.hasNotificationPermission(context)
        ) {
            pendingPermissionAction = PendingNotificationAction.ENABLE_PRECISION_MONITOR
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setHighPrecisionMonitor(enabled)
        }
    }

    val handleCriticalAlertsToggle: (Boolean) -> Unit = { enabled ->
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !BatteryAlertManager.hasNotificationPermission(context)
        ) {
            pendingPermissionAction = PendingNotificationAction.ENABLE_CRITICAL_ALERTS
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setCriticalAlertsEnabled(enabled)
        }
    }

    val handleExportFormat: (ExportFormat) -> Unit = { format ->
        showExportSheet = false
        viewModel.exportDiagnosticReport(format) { uri, exportedFormat ->
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = exportedFormat.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "VoltTrace Battery Forensic Report (${selectedWindow.label})"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(
                    Intent.createChooser(shareIntent, "Share VoltTrace Diagnostic Report")
                )
            }
        }
    }

    // Refresh Usage Access permission status & ensure Precision Service matches setting on resume
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshUsageAccessAndApps()
        viewModel.syncPrecisionService()
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbar()
        }
    }

    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    VoltTraceTheme(themeMode = settings.themeMode) {
        if (showExportSheet) {
            ExportReportBottomSheet(
                windowLabel = selectedWindow.label,
                sampleCount = report.sampleCount,
                onDismiss = { showExportSheet = false },
                onSelectFormat = handleExportFormat
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                bottomBar = {
                    if (!useRail) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            VoltTraceTabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = stringResource(tab.labelRes)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(tab.labelRes),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    modifier = Modifier.testTag("nav_tab_${tab.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail) {
                        NavigationRail(
                            modifier = Modifier.fillMaxHeight(),
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            VoltTraceTabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = stringResource(tab.labelRes)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(tab.labelRes),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    modifier = Modifier.testTag("nav_rail_${tab.route}")
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = initialRoute,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable(ROUTE_LIVE) {
                                LiveScreen(
                                    liveSample = liveSample,
                                    liveSubsystems = liveSubsystems,
                                    oscilloscopeMw = oscilloscopeMw,
                                    report = report,
                                    probeState = probeState,
                                    totalSampleCount = totalSampleCount,
                                    highPrecisionEnabled = settings.highPrecisionMonitorEnabled,
                                    onToggleHighPrecision = handlePrecisionToggle,
                                    onCaptureSnapshot = viewModel::captureManualSnapshot,
                                    onToggleProbe = viewModel::toggleDrainProbe,
                                    onOpenSettings = { action, pkg ->
                                        SystemSettingsIntents.launch(context, action, pkg)
                                    },
                                    onNavigateToCauses = { navigateToTab(ROUTE_CAUSES) }
                                )
                            }
                            composable(ROUTE_CAUSES) {
                                CausesScreen(
                                    report = report,
                                    selectedWindow = selectedWindow,
                                    hasUsageAccess = hasUsageAccess,
                                    onSelectWindow = viewModel::selectWindow,
                                    onOpenSettings = { action, pkg ->
                                        SystemSettingsIntents.launch(context, action, pkg)
                                    },
                                    onOpenExport = { showExportSheet = true }
                                )
                            }
                            composable(ROUTE_TIMELINE) {
                                TimelineScreen(
                                    samples = windowSamples,
                                    report = report,
                                    selectedWindow = selectedWindow,
                                    hasUsageAccess = hasUsageAccess,
                                    selectedSpikeReport = selectedSpikeReport,
                                    isInvestigatingSpike = isInvestigatingSpike,
                                    onSelectWindow = viewModel::selectWindow,
                                    onInspectSample = viewModel::inspectTimelineSample,
                                    onDismissSpike = viewModel::dismissSpikeInspection,
                                    onOpenSettings = { action, pkg ->
                                        SystemSettingsIntents.launch(context, action, pkg)
                                    }
                                )
                            }
                            composable(ROUTE_INSPECTOR) {
                                InspectorScreen(
                                    liveSample = liveSample,
                                    report = report,
                                    settings = settings,
                                    hasUsageAccess = hasUsageAccess,
                                    totalSampleCount = totalSampleCount,
                                    energyMeterInventory = energyMeterInventory,
                                    onScanEnergyMeters = { viewModel.scanEnergyMeters(showToast = true) },
                                    onSetBackgroundMonitoring = viewModel::setBackgroundMonitoring,
                                    onSetHighPrecisionMonitor = handlePrecisionToggle,
                                    onSetCriticalAlertsEnabled = handleCriticalAlertsToggle,
                                    onSetThemeMode = viewModel::setThemeMode,
                                    onSetThermalAlertCelsius = viewModel::setThermalAlertCelsius,
                                    onSetRatedCapacityMah = viewModel::setRatedCapacityMah,
                                    onOpenSettings = { action, pkg ->
                                        SystemSettingsIntents.launch(context, action, pkg)
                                    },
                                    onOpenExport = { showExportSheet = true },
                                    onClearHistory = viewModel::clearHistory
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
