package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.BatterySampleEntity
import com.example.domain.AnalysisWindow
import com.example.domain.BatteryAnalysisReport
import com.example.domain.RootCauseAnalyzer
import com.example.domain.SettingsAction
import com.example.domain.SpikeForensicReport
import com.example.domain.SpikeInspector
import com.example.ui.components.SpikeDrillDownBottomSheet
import com.example.ui.components.TimelineChartCard
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryTextStyles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TimelineScreen(
    samples: List<BatterySampleEntity>,
    report: BatteryAnalysisReport,
    selectedWindow: AnalysisWindow,
    hasUsageAccess: Boolean,
    selectedSpikeReport: SpikeForensicReport?,
    isInvestigatingSpike: Boolean,
    onSelectWindow: (AnalysisWindow) -> Unit,
    onInspectSample: (Int) -> Unit,
    onDismissSpike: () -> Unit,
    onOpenSettings: (SettingsAction, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    val timeFormatter = remember { SimpleDateFormat("MMM d · HH:mm:ss", Locale.getDefault()) }
    val spikeMarkers = remember(samples) { SpikeInspector.detectNotableSpikes(samples) }
    val recentLogIndexed = remember(samples) {
        samples.mapIndexed { idx, sample -> idx to sample }
            .asReversed()
            .take(40)
    }

    if (selectedSpikeReport != null) {
        SpikeDrillDownBottomSheet(
            report = selectedSpikeReport,
            hasUsageAccess = hasUsageAccess,
            onDismiss = onDismissSpike,
            onOpenSettings = onOpenSettings
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text(
                        text = stringResource(R.string.timeline_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${report.sampleCount} samples across ${RootCauseAnalyzer.formatDuration(report.spanDurationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                WindowSelectorRow(
                    selectedWindow = selectedWindow,
                    onSelectWindow = onSelectWindow
                )
            }
        }

        // Interactive Dual-Axis Chart with Spike Jump Chips & Drill Down
        item {
            TimelineChartCard(
                samples = samples,
                spikeMarkers = spikeMarkers,
                isInvestigating = isInvestigatingSpike,
                onDrillDownSample = onInspectSample
            )
        }

        // Active vs Standby Drain Comparison
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.section_active_vs_standby),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ComparisonStatCard(
                        icon = Icons.Filled.PhoneAndroid,
                        title = stringResource(R.string.label_screen_on),
                        primaryValue = String.format(Locale.US, "%.1f%%/hr", report.screenOnDrainRatePctPerHour),
                        subtitle = RootCauseAnalyzer.formatDuration(report.screenOnDurationMs),
                        accent = telemetry.healthy,
                        modifier = Modifier.weight(1f)
                    )
                    val standbyTooShort = report.screenOffDurationMs in 1L until (5L * 60_000L)
                    ComparisonStatCard(
                        icon = Icons.Filled.NightsStay,
                        title = stringResource(R.string.label_screen_off),
                        primaryValue = if (standbyTooShort) {
                            "—"
                        } else {
                            String.format(Locale.US, "%.1f%%/hr", report.screenOffDrainRatePctPerHour)
                        },
                        subtitle = when {
                            standbyTooShort -> "${RootCauseAnalyzer.formatDuration(report.screenOffDurationMs)} (< 5m min)"
                            report.screenOffDurationMs > 0L -> RootCauseAnalyzer.formatDuration(report.screenOffDurationMs)
                            else -> "Awaiting standby"
                        },
                        accent = if (!standbyTooShort && report.screenOffDrainRatePctPerHour >= 1.5f) {
                            telemetry.critical
                        } else {
                            telemetry.standby
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Window Time Accounting & Timestamped Charge/Discharge/Thermal Peaks
        item {
            WindowAccountingPeaksCard(report = report)
        }

        // Radio Efficiency Comparison (Wi-Fi vs Cellular)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.section_radio_efficiency),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ComparisonStatCard(
                        icon = Icons.Filled.Wifi,
                        title = stringResource(R.string.label_wifi),
                        primaryValue = String.format(Locale.US, "%.1f%%/hr", report.wifiDrainRatePctPerHour),
                        subtitle = "Low-power radio",
                        accent = telemetry.charging,
                        modifier = Modifier.weight(1f)
                    )
                    ComparisonStatCard(
                        icon = Icons.Filled.SignalCellularAlt,
                        title = stringResource(R.string.label_cellular),
                        primaryValue = String.format(Locale.US, "%.1f%%/hr", report.cellularDrainRatePctPerHour),
                        subtitle = "Baseband modem",
                        accent = telemetry.warning,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Recorded Sample Log Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.section_sample_log),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Tap any timestamp to drill down on apps, deep sleep, and hardware state",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(recentLogIndexed, key = { (_, sample) -> sample.id }) { (originalIndex, sample) ->
            TelemetryLogRow(
                sample = sample,
                formattedTime = timeFormatter.format(Date(sample.timestampMs)),
                onClick = { onInspectSample(originalIndex) }
            )
        }
    }
}

@Composable
private fun ComparisonStatCard(
    icon: ImageVector,
    title: String,
    primaryValue: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, telemetry.cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(17.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = primaryValue,
                style = TelemetryTextStyles.MetricValue,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TelemetryLogRow(
    sample: BatterySampleEntity,
    formattedTime: String,
    onClick: () -> Unit
) {
    val telemetry = LocalTelemetryColors.current
    val levelColor = when {
        sample.isCharging -> telemetry.charging
        !sample.isScreenOn && sample.powerMw >= 2_500f -> telemetry.critical
        sample.powerMw >= 3_800f -> telemetry.warning
        else -> telemetry.healthy
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .testTag("telemetry_log_row_${sample.id}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, telemetry.cardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (sample.isCharging) {
                            Icon(
                                imageVector = Icons.Filled.Bolt,
                                contentDescription = "Charging",
                                tint = telemetry.charging,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "${sample.levelPct}%  ·  ${String.format(Locale.US, "%+.2f W", sample.powerSignedWatts)}  ·  ${String.format(Locale.US, "%.1f°C", sample.temperatureCelsius)}",
                        style = TelemetryTextStyles.MetricSmall,
                        color = levelColor
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val badges = buildList {
                        if (sample.isScreenOn) add("DSP ${sample.screenBrightnessPct}%") else add("OFF")
                        if (sample.cellularActive) add("CELL")
                        if (sample.wifiConnected) add("WIFI") else if (sample.wifiEnabled) add("WIFI-IDLE")
                        if (sample.gpsEnabled) add("LOC-ON")
                        if (sample.bluetoothEnabled) add("BT")
                        if (sample.isCharging) add("CHG")
                    }
                    badges.forEach { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.cd_drill_down_sample),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun WindowAccountingPeaksCard(
    report: BatteryAnalysisReport
) {
    val telemetry = LocalTelemetryColors.current
    val standbyClockTotal = report.totalDeepSleepMs + report.totalStandbyAwakeMs
    val peakTempStamp = report.peakTempTimestampMs?.let { " @ ${RootCauseAnalyzer.formatClockTime(it)}" }.orEmpty()
    val peakTempState = if (report.peakTempWasCharging) "Chg" else "Dis"
    val peakDisStamp = report.peakDischargePowerTimestampMs?.let { " @ ${RootCauseAnalyzer.formatClockTime(it)}" }.orEmpty()
    val peakChgStamp = report.peakChargePowerTimestampMs?.let { " @ ${RootCauseAnalyzer.formatClockTime(it)}" }.orEmpty()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, telemetry.cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Window Accounting & Peaks",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PeakMetricCell(
                    label = "Peak Discharge",
                    value = String.format(Locale.US, "-%.2f W", report.peakDischargePowerMw / 1000f),
                    detail = if (peakDisStamp.isNotEmpty()) peakDisStamp.trim() else "Discharging",
                    accent = telemetry.warning,
                    modifier = Modifier.weight(1f)
                )
                PeakMetricCell(
                    label = "Peak Thermal",
                    value = String.format(Locale.US, "%.1f°C", report.peakTempC),
                    detail = "$peakTempState$peakTempStamp",
                    accent = if (report.peakTempC >= 38f) telemetry.critical else telemetry.healthy,
                    modifier = Modifier.weight(1f)
                )
            }

            if (report.chargingDurationMs > 0L || report.mixedTransitionDurationMs > 0L || standbyClockTotal > 0L) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (report.chargingDurationMs > 0L) {
                        PeakMetricCell(
                            label = "Charging (+${String.format(Locale.US, "%.0f mAh", report.totalChargedMah)})",
                            value = RootCauseAnalyzer.formatDuration(report.chargingDurationMs),
                            detail = if (report.peakChargePowerMw > 10f) {
                                String.format(Locale.US, "Peak +%.2f W%s", report.peakChargePowerMw / 1000f, peakChgStamp)
                            } else {
                                "Excluded from drain"
                            },
                            accent = telemetry.charging,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (standbyClockTotal > 0L) {
                        val sleepPct = (report.totalDeepSleepMs.toFloat() / standbyClockTotal.toFloat()) * 100f
                        PeakMetricCell(
                            label = "Kernel Deep Sleep",
                            value = String.format(Locale.US, "%.0f%%", sleepPct),
                            detail = "${RootCauseAnalyzer.formatDuration(report.totalDeepSleepMs)} asleep",
                            accent = if (sleepPct >= 80f) telemetry.healthy else telemetry.warning,
                            modifier = Modifier.weight(1f)
                        )
                    } else if (report.mixedTransitionDurationMs > 0L) {
                        PeakMetricCell(
                            label = "Mixed Transitions",
                            value = RootCauseAnalyzer.formatDuration(report.mixedTransitionDurationMs),
                            detail = "Excluded from standby",
                            accent = telemetry.standby,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeakMetricCell(
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
