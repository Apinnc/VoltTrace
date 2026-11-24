package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.BatterySampleEntity
import com.example.domain.BatteryAnalysisReport
import com.example.domain.HardwareSubsystemStatus
import com.example.domain.SettingsAction
import com.example.ui.components.LiveHardwareSubsystemsCard
import com.example.ui.components.LiveOscilloscopeCard
import com.example.ui.components.PowerGaugeCard
import com.example.ui.components.RootCauseCard
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryTextStyles
import com.example.ui.viewmodel.ProbeUiState
import java.util.Locale

@Composable
fun LiveScreen(
    liveSample: BatterySampleEntity,
    liveSubsystems: List<HardwareSubsystemStatus>,
    oscilloscopeMw: List<Float>,
    report: BatteryAnalysisReport,
    probeState: ProbeUiState,
    totalSampleCount: Int,
    highPrecisionEnabled: Boolean,
    onToggleHighPrecision: (Boolean) -> Unit,
    onCaptureSnapshot: () -> Unit,
    onToggleProbe: () -> Unit,
    onOpenSettings: (SettingsAction, String?) -> Unit,
    onNavigateToCauses: () -> Unit,
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
        // Screen Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "$totalSampleCount telemetry snapshots recorded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onCaptureSnapshot,
                    modifier = Modifier.testTag("log_snapshot_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.AddCircleOutline,
                        contentDescription = stringResource(R.string.cd_capture_snapshot),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.action_capture_snapshot),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // Signature Radial Battery & Power Gauge
        item {
            PowerGaugeCard(
                sample = liveSample,
                overallDrainPctPerHour = report.overallDrainRatePctPerHour,
                estimatedRemainingHours = report.estimatedRemainingHours,
                efficiencyScore = report.efficiencyScore
            )
        }

        // Opt-In 1-Minute High-Precision Foreground Monitor Control Strip
        item {
            val stripAccent = if (highPrecisionEnabled) telemetry.healthy else MaterialTheme.colorScheme.onSurfaceVariant
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    1.dp,
                    if (highPrecisionEnabled) telemetry.healthy.copy(alpha = 0.45f) else telemetry.cardBorder
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Sensors,
                        contentDescription = null,
                        tint = stripAccent,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (highPrecisionEnabled) {
                                "${stringResource(R.string.precision_badge_active)} ACTIVE"
                            } else {
                                stringResource(R.string.precision_monitor_title)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (highPrecisionEnabled) telemetry.healthy else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (highPrecisionEnabled) {
                                "60s foreground sampling + instant screen on/off tracking"
                            } else {
                                "Enable opt-in 1-minute foreground rail sampling"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = highPrecisionEnabled,
                        onCheckedChange = onToggleHighPrecision,
                        modifier = Modifier.testTag("live_precision_switch")
                    )
                }
            }
        }

        // Live Real-Time Hardware Subsystems Monitor (Display, Cellular, Wi-Fi, GPS, Bluetooth)
        item {
            LiveHardwareSubsystemsCard(
                subsystems = liveSubsystems,
                onOpenSettings = { action -> onOpenSettings(action, null) }
            )
        }

        // Live Oscilloscope Trace
        item {
            LiveOscilloscopeCard(
                samplesMw = oscilloscopeMw,
                isCharging = liveSample.isCharging
            )
        }

        // 30-Second Live Drain Stress Probe
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
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.probe_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.probe_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (probeState.isRunning) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Sampling hardware rails at 1 Hz...",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = "${probeState.elapsedSeconds}s / ${probeState.totalSeconds}s",
                                    style = TelemetryTextStyles.MetricSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            LinearProgressIndicator(
                                progress = {
                                    (probeState.elapsedSeconds.toFloat() / probeState.totalSeconds.toFloat())
                                        .coerceIn(0f, 1f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                            )
                        }
                    }

                    probeState.result?.let { res ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    ProbeMetricColumn(
                                        label = "Avg Draw",
                                        value = String.format(Locale.US, "%.2f W", res.avgPowerMw / 1000f)
                                    )
                                    ProbeMetricColumn(
                                        label = "Peak",
                                        value = String.format(Locale.US, "%.2f W", res.peakPowerMw / 1000f)
                                    )
                                    ProbeMetricColumn(
                                        label = "Voltage Δ",
                                        value = "${res.endVoltageMv - res.startVoltageMv} mV"
                                    )
                                    ProbeMetricColumn(
                                        label = "Temp Δ",
                                        value = String.format(Locale.US, "%+.1f°C", res.tempDeltaC)
                                    )
                                }
                                Text(
                                    text = "Top load: ${res.primaryHardwareLoad} · ${res.verdict}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onToggleProbe,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("drain_probe_button")
                    ) {
                        Icon(
                            imageVector = if (probeState.isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = if (probeState.isRunning) {
                                stringResource(R.string.action_stop_probe)
                            } else {
                                stringResource(R.string.action_start_probe)
                            }
                        )
                    }
                }
            }
        }

        // Top Root-Cause Preview
        report.findings.firstOrNull()?.let { topFinding ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Top Identified Cause",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        TextButton(
                            onClick = onNavigateToCauses,
                            modifier = Modifier.testTag("see_all_causes_button")
                        ) {
                            Text("Full Report")
                            Spacer(modifier = Modifier.size(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    RootCauseCard(
                        finding = topFinding,
                        onActionClick = onOpenSettings
                    )
                }
            }
        }
    }
}

@Composable
private fun ProbeMetricColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
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
