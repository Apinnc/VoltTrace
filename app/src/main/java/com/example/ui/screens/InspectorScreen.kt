package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.hardware.BatteryHardwareReader
import com.example.data.hardware.EnergyMeterInventory
import com.example.data.local.BatterySampleEntity
import com.example.data.local.UserMonitorSettings
import com.example.domain.BatteryAnalysisReport
import com.example.domain.SettingsAction
import com.example.ui.components.DiscoveredEnergyMetersCard
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryTextStyles
import com.example.ui.theme.ThemeMode
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun InspectorScreen(
    liveSample: BatterySampleEntity,
    report: BatteryAnalysisReport,
    settings: UserMonitorSettings,
    hasUsageAccess: Boolean,
    totalSampleCount: Int,
    energyMeterInventory: EnergyMeterInventory?,
    onScanEnergyMeters: () -> Unit,
    onSetBackgroundMonitoring: (Boolean) -> Unit,
    onSetHighPrecisionMonitor: (Boolean) -> Unit,
    onSetCriticalAlertsEnabled: (Boolean) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetThermalAlertCelsius: (Float) -> Unit,
    onSetRatedCapacityMah: (Int) -> Unit,
    onOpenSettings: (SettingsAction, String?) -> Unit,
    onOpenExport: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.dialog_clear_title)) },
            text = { Text(stringResource(R.string.dialog_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearHistory()
                    },
                    modifier = Modifier.testTag("confirm_clear_history_button")
                ) {
                    Text(
                        text = stringResource(R.string.action_confirm_clear),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 760.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = stringResource(R.string.inspector_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.inspector_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Battery Cell Hardware Specs Card
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, telemetry.cardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BatteryChargingFull,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.section_cell_specs),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SpecTile(
                            label = "Health",
                            value = BatteryHardwareReader.healthLabel(liveSample.healthCode),
                            modifier = Modifier.weight(1f)
                        )
                        SpecTile(
                            label = "Chemistry",
                            value = liveSample.technology,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SpecTile(
                            label = "Voltage Range",
                            value = "${report.minVoltageMv}–${report.maxVoltageMv} mV",
                            modifier = Modifier.weight(1f)
                        )
                        SpecTile(
                            label = "Thermal State",
                            value = BatteryHardwareReader.thermalLabel(liveSample.thermalStatus),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val chargeCounterStr = if (liveSample.chargeCounterMicroAh > 0L) {
                            "${liveSample.chargeCounterMicroAh / 1000L} mAh"
                        } else {
                            "Unavailable"
                        }
                        SpecTile(
                            label = "Charge Counter",
                            value = chargeCounterStr,
                            modifier = Modifier.weight(1f)
                        )
                        SpecTile(
                            label = "Stored Samples",
                            value = "$totalSampleCount",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // On-Device Hardware Energy Meters (Android 15+ SystemHealthManager PowerMonitor)
        item {
            DiscoveredEnergyMetersCard(
                inventory = energyMeterInventory,
                windowSummaries = report.discoveredEnergyMeters,
                onRescan = onScanEnergyMeters
            )
        }

        // Background Telemetry Engine Controls
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, telemetry.cardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.section_monitoring_engine),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stringResource(R.string.bg_monitor_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.bg_monitor_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.backgroundMonitoringEnabled,
                            onCheckedChange = onSetBackgroundMonitoring,
                            modifier = Modifier.testTag("bg_monitor_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stringResource(R.string.precision_monitor_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.precision_monitor_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.highPrecisionMonitorEnabled,
                            onCheckedChange = onSetHighPrecisionMonitor,
                            modifier = Modifier.testTag("precision_monitor_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stringResource(R.string.critical_alerts_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.critical_alerts_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.criticalAlertsEnabled,
                            onCheckedChange = onSetCriticalAlertsEnabled,
                            modifier = Modifier.testTag("critical_alerts_switch")
                        )
                    }

                    // Usage Access Status Tile
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onOpenSettings(SettingsAction.USAGE_ACCESS, null) }
                            .minimumInteractiveComponentSize()
                            .testTag("inspector_usage_access_row")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Security,
                                contentDescription = null,
                                tint = if (hasUsageAccess) telemetry.healthy else telemetry.warning
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.usage_access_status_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (hasUsageAccess) {
                                        stringResource(R.string.usage_access_granted)
                                    } else {
                                        stringResource(R.string.usage_access_denied)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.cd_open_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Calibration & Theme Appearance
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, telemetry.cardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.section_appearance),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { onSetThemeMode(mode) },
                                label = {
                                    Text(
                                        text = mode.name.lowercase()
                                            .replaceFirstChar { it.titlecase() },
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                },
                                modifier = Modifier.testTag("theme_chip_${mode.name.lowercase()}")
                            )
                        }
                    }

                    // Thermal Alert Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Thermal Stress Threshold",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f°C", settings.thermalAlertCelsius),
                                style = TelemetryTextStyles.MetricSmall,
                                color = telemetry.warning
                            )
                        }
                        Slider(
                            value = settings.thermalAlertCelsius,
                            onValueChange = onSetThermalAlertCelsius,
                            valueRange = 35f..45f,
                            steps = 19,
                            modifier = Modifier.testTag("thermal_threshold_slider")
                        )
                    }

                    // Battery Capacity Calibration Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Rated Battery Capacity",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${settings.ratedCapacityMah} mAh",
                                style = TelemetryTextStyles.MetricSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = settings.ratedCapacityMah.toFloat(),
                            onValueChange = { onSetRatedCapacityMah((it / 100f).roundToInt() * 100) },
                            valueRange = 2500f..7000f,
                            steps = 44,
                            modifier = Modifier.testTag("rated_capacity_slider")
                        )
                    }
                }
            }
        }

        // Export & Data Reset Controls
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onOpenExport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inspector_export_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.IosShare,
                        contentDescription = stringResource(R.string.cd_export_report),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.action_export_report))
                }

                OutlinedButton(
                    onClick = { showClearDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("clear_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.action_clear_history))
                }
            }
        }
    }
}

@Composable
private fun SpecTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = TelemetryTextStyles.MetricSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
