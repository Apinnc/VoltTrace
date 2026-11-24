package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.CauseSeverity
import com.example.domain.RootCauseCategory
import com.example.domain.RootCauseFinding
import com.example.domain.SettingsAction
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryTextStyles

@Composable
fun RootCauseCard(
    finding: RootCauseFinding,
    onActionClick: (SettingsAction, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    val accent = when (finding.severity) {
        CauseSeverity.CRITICAL -> telemetry.critical
        CauseSeverity.ELEVATED -> telemetry.warning
        CauseSeverity.MODERATE -> telemetry.charging
        CauseSeverity.HEALTHY -> telemetry.healthy
    }

    val severityLabel = when (finding.severity) {
        CauseSeverity.CRITICAL -> "CRITICAL"
        CauseSeverity.ELEVATED -> "ELEVATED"
        CauseSeverity.MODERATE -> "MODERATE"
        CauseSeverity.HEALTHY -> "HEALTHY"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("root_cause_card_${finding.id}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = accent.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = categoryIcon(finding.category),
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = accent.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = severityLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = finding.headlineMetric,
                            style = TelemetryTextStyles.MetricSmall,
                            color = accent
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = finding.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Text(
                text = finding.explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Quantitative telemetry evidence box
            if (finding.evidencePoints.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        finding.evidencePoints.forEach { bullet ->
                            Text(
                                text = "• $bullet",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = finding.recommendation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )

                if (finding.actionLabel != null && finding.settingsAction != SettingsAction.NONE) {
                    FilledTonalButton(
                        onClick = { onActionClick(finding.settingsAction, finding.targetPackage) },
                        modifier = Modifier.testTag("action_${finding.id}")
                    ) {
                        Text(
                            text = finding.actionLabel,
                            style = MaterialTheme.typography.labelLarge
                        )
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
    }
}

private fun categoryIcon(category: RootCauseCategory): ImageVector = when (category) {
    RootCauseCategory.DISPLAY_POWER -> Icons.Filled.BrightnessHigh
    RootCauseCategory.CELLULAR_RADIO -> Icons.Filled.SignalCellularAlt
    RootCauseCategory.WIFI_RADIO -> Icons.Filled.Wifi
    RootCauseCategory.GPS_LOCATION -> Icons.Filled.LocationOn
    RootCauseCategory.BLUETOOTH_RADIO -> Icons.Filled.Bluetooth
    RootCauseCategory.WAKELOCK_STANDBY -> Icons.Filled.NightsStay
    RootCauseCategory.THERMAL_STRESS -> Icons.Filled.Thermostat
    RootCauseCategory.HIGH_POWER_SPIKES -> Icons.Filled.ElectricBolt
    RootCauseCategory.APP_CULPRIT -> Icons.Filled.Apps
    RootCauseCategory.HEALTHY_PROFILE -> Icons.Filled.CheckCircle
}
