package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.AnalysisWindow
import com.example.domain.AppDrainAttribution
import com.example.domain.BatteryAnalysisReport
import com.example.domain.RootCauseAnalyzer
import com.example.domain.SettingsAction
import com.example.ui.components.DrainEpisodesCard
import com.example.ui.components.HardwareAttributionBreakdownCard
import com.example.ui.components.RootCauseCard
import com.example.ui.components.SleepSessionsCard
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryTextStyles
import java.util.Locale

@Composable
fun CausesScreen(
    report: BatteryAnalysisReport,
    selectedWindow: AnalysisWindow,
    hasUsageAccess: Boolean,
    onSelectWindow: (AnalysisWindow) -> Unit,
    onOpenSettings: (SettingsAction, String?) -> Unit,
    onOpenExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 760.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header + Export + Time Window Selector
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.causes_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.causes_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = onOpenExport,
                        modifier = Modifier.testTag("causes_export_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.IosShare,
                            contentDescription = stringResource(R.string.cd_export_report),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = "Export",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                WindowSelectorRow(
                    selectedWindow = selectedWindow,
                    onSelectWindow = onSelectWindow
                )
            }
        }

        // Baseline Collection Notice if window still gathering longitudinal samples
        if (!report.hasLongitudinalData) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "${stringResource(R.string.empty_causes_title)} (${report.sampleCount} samples)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.empty_causes_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Dedicated Hardware Elements Attribution Breakdown (Display, Cellular, Wi-Fi, GPS, Bluetooth, CPU/System)
        item {
            HardwareAttributionBreakdownCard(
                breakdown = report.hardwareBreakdown,
                totalDischargedMah = report.totalDischargedMah,
                hasLongitudinalData = report.hasLongitudinalData,
                attributionMode = report.hardwareAttributionMode,
                onOpenSettings = { action -> onOpenSettings(action, null) }
            )
        }

        // Traceable Significant Drain Episodes (Charge Counter + Boot Clocks + Epistemic Unknowns)
        item {
            DrainEpisodesCard(
                episodes = report.drainEpisodes,
                totalUnpluggedDeepSleepMs = report.totalUnpluggedDeepSleepMs,
                totalUnpluggedAwakeMs = report.totalUnpluggedAwakeMs
            )
        }

        // Sleep & Standby Forensic Audit (Screen-Off sessions >= 5 min)
        item {
            SleepSessionsCard(
                sessions = report.sleepSessions,
                onOpenBatteryOptimization = {
                    onOpenSettings(SettingsAction.BATTERY_OPTIMIZATION, null)
                }
            )
        }

        // Ranked Diagnosed Root Causes
        item {
            Text(
                text = stringResource(R.string.section_diagnosed_causes),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        items(report.findings, key = { it.id }) { finding ->
            RootCauseCard(
                finding = finding,
                onActionClick = onOpenSettings
            )
        }

        // Per-App Drain Correlation Section
        item {
            Text(
                text = stringResource(R.string.section_app_culprits),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (!hasUsageAccess) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, telemetry.warning.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Security,
                                contentDescription = null,
                                tint = telemetry.warning,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = stringResource(R.string.usage_access_banner_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = stringResource(R.string.usage_access_banner_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { onOpenSettings(SettingsAction.USAGE_ACCESS, null) },
                            modifier = Modifier.testTag("grant_usage_access_button")
                        ) {
                            Text(stringResource(R.string.action_grant_usage_access))
                            Spacer(modifier = Modifier.size(6.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        } else if (report.appCulprits.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, telemetry.cardBorder)
                ) {
                    Text(
                        text = "Usage Access is active. App-level power attribution will populate as foreground sessions are observed across this time window.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(report.appCulprits, key = { it.packageName }) { app ->
                AppCulpritRow(
                    app = app,
                    onInspectApp = { pkg -> onOpenSettings(SettingsAction.APP_DETAILS, pkg) }
                )
            }
        }
    }
}

@Composable
fun WindowSelectorRow(
    selectedWindow: AnalysisWindow,
    onSelectWindow: (AnalysisWindow) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AnalysisWindow.entries.forEach { window ->
            FilterChip(
                selected = selectedWindow == window,
                onClick = { onSelectWindow(window) },
                label = {
                    Text(
                        text = window.label,
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                modifier = Modifier.testTag("window_chip_${window.name.lowercase()}")
            )
        }
    }
}

@Composable
private fun AppCulpritRow(
    app: AppDrainAttribution,
    onInspectApp: (String) -> Unit
) {
    val telemetry = LocalTelemetryColors.current
    val accent = if (app.isHighIntensity) telemetry.critical else telemetry.charging

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onInspectApp(app.packageName) }
            .minimumInteractiveComponentSize()
            .testTag("app_culprit_${app.packageName}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, telemetry.cardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = accent.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Apps,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${RootCauseAnalyzer.formatDuration(app.totalForegroundMs)} active · ${app.wakeupCount} wakeups",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                if (app.hasEnergyEstimate) {
                    Text(
                        text = String.format(Locale.US, "%.1f%% total", app.shareOfTotalDrainPct),
                        style = TelemetryTextStyles.MetricValue,
                        color = accent
                    )
                    val methodTag = if (app.isUniformTimeEstimate) "uniform time est." else String.format(Locale.US, "%.0f%% apps", app.sharePct)
                    Text(
                        text = String.format(Locale.US, "%.1f mAh · %s", app.estimatedMah, methodTag),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Usage only",
                        style = TelemetryTextStyles.MetricSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "No drain recorded",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
