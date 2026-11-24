package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.CauseSeverity
import com.example.domain.HardwareComponentAttribution
import com.example.domain.HardwareComponentType
import com.example.domain.HardwareSubsystemStatus
import com.example.domain.RootCauseAnalyzer
import com.example.domain.SettingsAction
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryTextStyles
import java.util.Locale

@Composable
fun hardwareComponentColor(type: HardwareComponentType): Color {
    val telemetry = LocalTelemetryColors.current
    return when (type) {
        HardwareComponentType.DISPLAY -> telemetry.healthy
        HardwareComponentType.CELLULAR -> telemetry.critical
        HardwareComponentType.WIFI -> telemetry.charging
        HardwareComponentType.GPS -> telemetry.warning
        HardwareComponentType.BLUETOOTH -> telemetry.standby
        HardwareComponentType.CPU_SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

fun hardwareComponentIcon(type: HardwareComponentType): ImageVector = when (type) {
    HardwareComponentType.DISPLAY -> Icons.Filled.BrightnessHigh
    HardwareComponentType.CELLULAR -> Icons.Filled.SignalCellularAlt
    HardwareComponentType.WIFI -> Icons.Filled.Wifi
    HardwareComponentType.GPS -> Icons.Filled.LocationOn
    HardwareComponentType.BLUETOOTH -> Icons.Filled.Bluetooth
    HardwareComponentType.CPU_SYSTEM -> Icons.Filled.Memory
}

/**
 * Live Hardware Subsystems Monitor card showing real-time state & estimated power draw
 * for Display, Cellular Radio, Wi-Fi, GPS, and Bluetooth.
 */
@Composable
fun LiveHardwareSubsystemsCard(
    subsystems: List<HardwareSubsystemStatus>,
    onOpenSettings: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    val totalLiveMw = subsystems.sumOf { it.liveEstimatedMw.toDouble() }.toFloat()

    Surface(
        modifier = modifier.fillMaxWidth(),
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Hardware Subsystems",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Display, Cellular, Wi-Fi, GPS & Bluetooth",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = String.format(Locale.US, "~%.0f mW", totalLiveMw),
                    style = TelemetryTextStyles.MetricSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                subsystems.forEach { item ->
                    val accent = hardwareComponentColor(item.component)
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onOpenSettings(item.settingsAction) }
                            .minimumInteractiveComponentSize()
                            .testTag("live_hw_${item.component.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                color = if (item.isActive) accent.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = hardwareComponentIcon(item.component),
                                        contentDescription = item.component.displayName,
                                        tint = if (item.isActive) accent
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = item.component.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (item.isActive) accent
                                                else MaterialTheme.colorScheme.outline
                                            )
                                    )
                                }
                                Text(
                                    text = item.liveDetail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (item.liveEstimatedMw > 0.5f) {
                                        String.format(Locale.US, "%.0f mW", item.liveEstimatedMw)
                                    } else {
                                        "0 mW"
                                    },
                                    style = TelemetryTextStyles.MetricSmall,
                                    color = if (item.isActive) accent
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (item.isActive) "ACTIVE" else "OFF",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Comprehensive Hardware Power Attribution card for the Causes screen showing
 * segmented proportion bar + per-hardware mAh, % share, duty cycle, and settings shortcut.
 */
@Composable
fun HardwareAttributionBreakdownCard(
    breakdown: List<HardwareComponentAttribution>,
    totalDischargedMah: Float,
    hasLongitudinalData: Boolean,
    onOpenSettings: (SettingsAction) -> Unit,
    attributionMode: String = "Modeled Estimate",
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, telemetry.cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Hardware Power Attribution",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (hasLongitudinalData) {
                            String.format(Locale.US, "%.1f mAh attributed · %s", totalDischargedMah, attributionMode)
                        } else {
                            "Live hardware proportion · $attributionMode"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Segmented horizontal power bar
            val positiveSlices = breakdown.filter { it.sharePct >= 1f }
            if (positiveSlices.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    positiveSlices.forEach { slice ->
                        Box(
                            modifier = Modifier
                                .weight(slice.sharePct.coerceAtLeast(1f))
                                .height(14.dp)
                                .background(hardwareComponentColor(slice.component))
                        )
                    }
                }
            }

            // Detailed per-component rows (Display, Cellular, Wi-Fi, GPS, Bluetooth, CPU/System)
            var expandedComponent by remember { mutableStateOf<HardwareComponentType?>(null) }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                breakdown.forEach { item ->
                    val accent = hardwareComponentColor(item.component)
                    val isExpanded = expandedComponent == item.component
                    val severityColor = when (item.severity) {
                        CauseSeverity.CRITICAL -> telemetry.critical
                        CauseSeverity.ELEVATED -> telemetry.warning
                        CauseSeverity.MODERATE -> telemetry.charging
                        CauseSeverity.HEALTHY -> telemetry.healthy
                    }

                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable {
                                expandedComponent = if (isExpanded) null else item.component
                            }
                            .minimumInteractiveComponentSize()
                            .testTag("hw_attribution_${item.component.name.lowercase()}")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    color = accent.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = hardwareComponentIcon(item.component),
                                            contentDescription = item.component.displayName,
                                            tint = accent,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = item.component.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Surface(
                                            color = severityColor.copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = item.verdict,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = severityColor,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = item.liveDetail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = String.format(Locale.US, "%.0f%%", item.sharePct),
                                        style = TelemetryTextStyles.MetricValue,
                                        color = accent
                                    )
                                    Text(
                                        text = if (item.attributedMah > 0.05f) {
                                            String.format(Locale.US, "%.1f mAh", item.attributedMah)
                                        } else {
                                            String.format(Locale.US, "%.0f mW", item.avgPowerMwWhenActive)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Proportional fill track
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth((item.sharePct / 100f).coerceIn(0.02f, 1f))
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(accent)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val dutyText = if (item.activeDurationMs > 0L) {
                                    "Active ${RootCauseAnalyzer.formatDuration(item.activeDurationMs)} (${item.activeDutyPct.toInt()}%)"
                                } else {
                                    if (item.isCurrentlyActive) "Active now" else "Currently off"
                                }
                                Text(
                                    text = "$dutyText · Avg ${item.avgPowerMwWhenActive.toInt()} mW",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Icon(
                                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = "Toggle ${item.component.displayName} forensics",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            AnimatedVisibility(visible = isExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    item.forensicDetails.forEach { detail ->
                                        Text(
                                            text = "• $detail",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            onClick = { onOpenSettings(item.settingsAction) },
                                            modifier = Modifier.testTag("hw_settings_${item.component.name.lowercase()}")
                                        ) {
                                            Text("Open ${item.component.displayName} Settings")
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
