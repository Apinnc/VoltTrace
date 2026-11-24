package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.CauseSeverity
import com.example.domain.RootCauseAnalyzer
import com.example.domain.SettingsAction
import com.example.domain.SpikeForensicReport
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryTextStyles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpikeDrillDownBottomSheet(
    report: SpikeForensicReport,
    hasUsageAccess: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: (SettingsAction, String?) -> Unit
) {
    val telemetry = LocalTelemetryColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dateTimeFmt = remember { SimpleDateFormat("MMM d · HH:mm:ss", Locale.getDefault()) }
    val clockFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var showRawReadings by remember { mutableStateOf(false) }

    val target = report.targetSample
    val accent = when {
        target.isCharging -> telemetry.charging
        report.severity == CauseSeverity.CRITICAL -> telemetry.critical
        report.severity == CauseSeverity.ELEVATED -> telemetry.warning
        else -> telemetry.healthy
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("spike_drill_down_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header row: badge + timestamp + signed wattage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        color = accent.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = if (target.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.Bolt,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = report.classification,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = accent
                            )
                        }
                    }

                    Text(
                        text = dateTimeFmt.format(Date(target.timestampMs)),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Sample #${report.targetIndex + 1} · Source: ${target.source} · ${if (target.isScreenOn) "Screen ON (${target.screenBrightnessPct}%)" else "Screen OFF"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = String.format(Locale.US, "%+.2f W", target.powerSignedWatts),
                        style = TelemetryTextStyles.MetricValue,
                        color = accent
                    )
                    Text(
                        text = "${target.levelPct}% SOC",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Summary headline card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
            ) {
                Text(
                    text = report.summaryHeadline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp)
                )
            }

            // Electrical & Charge Counter metrics grid
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Electrical & Counter Snapshot",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ForensicMetricTile(
                        label = "Current",
                        value = String.format(Locale.US, "%+.0f mA", target.currentSignedMa),
                        detail = if (target.isCharging) "Into battery (${target.pluggedType})" else "Out of battery",
                        accent = accent,
                        modifier = Modifier.weight(1f)
                    )
                    ForensicMetricTile(
                        label = "Voltage",
                        value = String.format(Locale.US, "%.3f V", target.voltageVolts),
                        detail = report.voltageDeltaMv?.let {
                            String.format(Locale.US, "%+d mV vs prev", it)
                        } ?: "No prior sample",
                        accent = if ((report.voltageDeltaMv ?: 0) <= -40) telemetry.warning else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ForensicMetricTile(
                        label = "Cell Temp",
                        value = String.format(Locale.US, "%.1f°C", target.temperatureCelsius),
                        detail = report.tempDeltaC?.let {
                            String.format(Locale.US, "%+.1f°C vs prev", it)
                        } ?: "Nominal",
                        accent = if (target.temperatureCelsius >= 38f) telemetry.critical else telemetry.healthy,
                        modifier = Modifier.weight(1f)
                    )
                    val chargeText = when {
                        target.isCharging -> "Charging"
                        report.chargeLostBeforeMah != null -> String.format(Locale.US, "-%.1f mAh", report.chargeLostBeforeMah)
                        report.chargeLostAfterMah != null -> String.format(Locale.US, "-%.1f mAh", report.chargeLostAfterMah)
                        else -> "—"
                    }
                    val chargeSub = when {
                        target.isCharging -> "No discharge loss"
                        report.counterMeasured -> "Battery counter (before)"
                        report.chargeLostBeforeMah != null -> "Estimated from SOC %"
                        else -> "Single boundary sample"
                    }
                    ForensicMetricTile(
                        label = "Interval Charge Loss",
                        value = chargeText,
                        detail = chargeSub,
                        accent = if ((report.chargeLostBeforeMah ?: 0f) >= 35f) telemetry.warning else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Kernel Deep Sleep vs CPU Awake section (when boot clocks available)
            if (report.awakePctBefore != null || report.awakePctAfter != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, telemetry.cardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.NightsStay,
                                contentDescription = null,
                                tint = telemetry.standby,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Kernel Deep Sleep vs CPU Awake",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (report.awakePctBefore != null && report.cpuAwakeBeforeMs != null && report.deepSleepBeforeMs != null) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Leading into spike: %.0f%% CPU awake (%s awake · %s deep sleep)",
                                    report.awakePctBefore,
                                    RootCauseAnalyzer.formatDuration(report.cpuAwakeBeforeMs),
                                    RootCauseAnalyzer.formatDuration(report.deepSleepBeforeMs)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (report.awakePctBefore >= 60f && !target.isScreenOn) {
                                    telemetry.warning
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (report.awakePctAfter != null && report.cpuAwakeAfterMs != null && report.deepSleepAfterMs != null) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Following spike: %.0f%% CPU awake (%s awake · %s deep sleep)",
                                    report.awakePctAfter,
                                    RootCauseAnalyzer.formatDuration(report.cpuAwakeAfterMs),
                                    RootCauseAnalyzer.formatDuration(report.deepSleepAfterMs)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Likely Drivers
            ForensicBulletSection(
                title = "Likely Drivers Behind This Spike",
                icon = Icons.Filled.WarningAmber,
                iconTint = accent,
                bullets = report.likelyDrivers
            )

            // State Changes at this boundary
            ForensicBulletSection(
                title = "What Changed at This Timestamp",
                icon = Icons.Filled.Memory,
                iconTint = MaterialTheme.colorScheme.primary,
                bullets = report.stateChanges
            )

            // Apps & Background Services Active Around This Window
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, telemetry.cardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Apps,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            text = "Apps & Background Services (±10 min)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (!hasUsageAccess) {
                        Text(
                            text = "Grant Android Usage Access so VoltTrace can show which apps and background foreground-services fired during overnight spikes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { onOpenSettings(SettingsAction.USAGE_ACCESS, null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("spike_grant_usage_access_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VerifiedUser,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Grant Usage Access")
                        }
                    } else {
                        val bgEvents = report.correlatedEvents.filter { it.isBackgroundService }
                        val fgEvents = report.correlatedEvents.filter { !it.isBackgroundService }

                        if (bgEvents.isNotEmpty()) {
                            Text(
                                text = "Background Foreground-Service Events",
                                style = MaterialTheme.typography.labelMedium,
                                color = telemetry.warning
                            )
                            bgEvents.take(10).forEach { ev ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${clockFmt.format(Date(ev.timestampMs))} · ${ev.appLabel}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = ev.eventLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = telemetry.warning
                                        )
                                    }
                                    if (ev.packageName.isNotBlank() && ev.packageName != "android") {
                                        TextButton(
                                            onClick = { onOpenSettings(SettingsAction.APP_DETAILS, ev.packageName) },
                                            modifier = Modifier.testTag("spike_inspect_app_${ev.packageName}")
                                        ) {
                                            Text("Inspect")
                                            Spacer(modifier = Modifier.size(4.dp))
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = "Open ${ev.appLabel} settings",
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (fgEvents.isNotEmpty()) {
                            if (bgEvents.isNotEmpty()) HorizontalDivider(color = telemetry.cardBorder)
                            Text(
                                text = "Screen & App Activity Events",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            fgEvents.take(10).forEach { ev ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${clockFmt.format(Date(ev.timestampMs))} · ${ev.appLabel}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = ev.eventLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (ev.packageName.isNotBlank() && ev.packageName != "android") {
                                        TextButton(
                                            onClick = { onOpenSettings(SettingsAction.APP_DETAILS, ev.packageName) }
                                        ) {
                                            Text("Inspect")
                                        }
                                    }
                                }
                            }
                        }

                        if (report.correlatedAppUsage.isNotEmpty()) {
                            if (report.correlatedEvents.isNotEmpty()) HorizontalDivider(color = telemetry.cardBorder)
                            Text(
                                text = "Apps Active in Enclosing Window",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            report.correlatedAppUsage.take(5).forEach { app ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.appLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "${RootCauseAnalyzer.formatDuration(app.foregroundMs)} active · ${app.launchCount} events",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = { onOpenSettings(SettingsAction.APP_DETAILS, app.packageName) }
                                    ) {
                                        Text("App Info")
                                    }
                                }
                            }
                        }

                        if (report.correlatedEvents.isEmpty() && report.correlatedAppUsage.isEmpty()) {
                            Text(
                                text = "No user-space app launches or foreground-service starts were emitted by Android UsageEvents in this window. Activity was likely an OS/kernel sync, push wakeup, or charging trickle.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Hardware Subsystem / Energy Meter Snapshot
            if (report.energyMeterDeltas.isNotEmpty() || report.liveHardwareBreakdown.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, telemetry.cardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (report.energyMeterDeltas.isNotEmpty()) {
                                "Hardware PowerMonitor Rail Deltas"
                            } else {
                                "Modeled Hardware Snapshot (Instant)"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (report.energyMeterDeltas.isNotEmpty()) {
                            report.energyMeterDeltas.take(6).forEach { meter ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${meter.meterName} (${meter.meterType})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.2f mWh (%.1f mAh)", meter.deltaMWh, meter.estimatedMah),
                                        style = TelemetryTextStyles.MetricSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else {
                            report.liveHardwareBreakdown
                                .filter { it.isActive || it.liveEstimatedMw >= 15f }
                                .take(5)
                                .forEach { hw ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${hw.component.displayName} · ${hw.liveDetail}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = String.format(Locale.US, "~%.0f mW", hw.liveEstimatedMw),
                                            style = TelemetryTextStyles.MetricSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                        }
                    }
                }
            }

            // What Remains Unknown
            ForensicBulletSection(
                title = "What Remains Unknown (Android Permission Limits)",
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                bullets = report.whatRemainsUnknown
            )

            // Expandable Source Readings
            OutlinedButton(
                onClick = { showRawReadings = !showRawReadings },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("spike_toggle_source_readings")
            ) {
                Icon(
                    imageVector = if (showRawReadings) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(if (showRawReadings) "Hide Source Telemetry Readings" else "Show Verbatim Source Readings")
            }

            if (showRawReadings) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, telemetry.cardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        report.sourceReadings.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ForensicMetricTile(
    label: String,
    value: String,
    detail: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = TelemetryTextStyles.MetricSmall,
                color = accent
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ForensicBulletSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    bullets: List<String>
) {
    if (bullets.isEmpty()) return
    val telemetry = LocalTelemetryColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, telemetry.cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            bullets.forEach { line ->
                Text(
                    text = "• $line",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
