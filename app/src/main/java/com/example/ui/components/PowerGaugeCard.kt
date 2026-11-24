package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.BatterySampleEntity
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryTextStyles
import java.util.Locale

@Composable
fun PowerGaugeCard(
    sample: BatterySampleEntity,
    overallDrainPctPerHour: Float,
    estimatedRemainingHours: Float?,
    efficiencyScore: Int,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    val accentColor = when {
        sample.isCharging -> telemetry.charging
        sample.levelPct <= 15 || sample.powerWatts >= 4.0f -> telemetry.critical
        sample.levelPct <= 30 || sample.powerWatts >= 2.5f -> telemetry.warning
        else -> telemetry.healthy
    }

    val animatedProgress by animateFloatAsState(
        targetValue = (sample.levelPct / 100f).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "battery_arc_progress"
    )

    val statusText = when {
        sample.isCharging && sample.levelPct >= 100 -> stringResource(R.string.status_full)
        sample.isCharging -> "${stringResource(R.string.status_charging)} · ${sample.pluggedType}"
        else -> stringResource(R.string.status_discharging)
    }

    val gaugeCd = stringResource(R.string.cd_battery_gauge)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, telemetry.cardBorder),
        tonalElevation = 2.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = if (telemetry.isDark) 0.16f else 0.10f),
                            Color.Transparent
                        ),
                        center = Offset(280f, 180f),
                        radius = 650f
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Header status pill + Efficiency score
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = accentColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelMedium,
                                color = accentColor
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "SCORE $efficiencyScore/100",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // Gauge + Live Readouts Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Radial Arc Gauge
                    Box(
                        modifier = Modifier
                            .size(144.dp)
                            .semantics { contentDescription = gaugeCd },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 13.dp.toPx()
                            val inset = strokeWidth / 2f + 4.dp.toPx()
                            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                            val topLeft = Offset(inset, inset)

                            // Track
                            drawArc(
                                color = telemetry.gridLine,
                                startAngle = 135f,
                                sweepAngle = 270f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )

                            // Active progress arc
                            drawArc(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        accentColor.copy(alpha = 0.65f),
                                        accentColor,
                                        accentColor
                                    )
                                ),
                                startAngle = 135f,
                                sweepAngle = 270f * animatedProgress,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (sample.isCharging) {
                                Icon(
                                    imageVector = Icons.Filled.Bolt,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = "${sample.levelPct}%",
                                style = TelemetryTextStyles.HeroMono,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (sample.isCharging) {
                                    "CHARGING"
                                } else {
                                    String.format(Locale.US, "%.1f%%/hr", overallDrainPctPerHour)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = accentColor
                            )
                        }
                    }

                    // Right telemetry column
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TelemetryReadoutRow(
                            icon = Icons.Filled.ElectricBolt,
                            label = if (sample.isCharging) "Charge Input" else "Live Discharge",
                            value = if (sample.powerMw > 1f) {
                                String.format(Locale.US, "%+.2f W", sample.powerSignedWatts)
                            } else {
                                "Est. Active"
                            },
                            subValue = String.format(Locale.US, "%+.0f mA · %.2f V", sample.currentSignedMa, sample.voltageVolts),
                            tint = accentColor
                        )
                        TelemetryReadoutRow(
                            icon = Icons.Filled.Thermostat,
                            label = "Cell Thermal",
                            value = String.format(Locale.US, "%.1f°C", sample.temperatureCelsius),
                            subValue = if (estimatedRemainingHours != null && !sample.isCharging) {
                                String.format(Locale.US, "Est. %.1fh left", estimatedRemainingHours)
                            } else {
                                "${sample.technology} · ${if (sample.isPowerSaveMode) "Saver ON" else "Normal"}"
                            },
                            tint = if (sample.temperatureCelsius >= 38f) telemetry.warning else telemetry.charging
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryReadoutRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    subValue: String,
    tint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            color = tint.copy(alpha = 0.12f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = TelemetryTextStyles.MetricValue,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun LiveOscilloscopeCard(
    samplesMw: List<Float>,
    isCharging: Boolean,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    val traceColor = if (isCharging) telemetry.charging else telemetry.healthy
    val latestMw = samplesMw.lastOrNull() ?: 0f
    val peakMw = samplesMw.maxOrNull() ?: 0f
    val oscilloscopeCd = stringResource(R.string.cd_oscilloscope)

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
                        text = stringResource(R.string.oscilloscope_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.oscilloscope_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = String.format(Locale.US, "%.0f mW", latestMw),
                        style = TelemetryTextStyles.MetricValue,
                        color = traceColor
                    )
                    Text(
                        text = String.format(Locale.US, "PEAK %.0f mW", peakMw),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .semantics { contentDescription = oscilloscopeCd }
                    .padding(vertical = 8.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Subtle oscilloscope grid
                    val rows = 3
                    for (r in 1..rows) {
                        val y = h * (r / (rows + 1f))
                        drawLine(
                            color = telemetry.gridLine.copy(alpha = 0.65f),
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    if (samplesMw.isEmpty()) return@Canvas

                    val minVal = (samplesMw.minOrNull() ?: 0f) * 0.85f
                    val maxVal = ((samplesMw.maxOrNull() ?: 500f) * 1.15f).coerceAtLeast(minVal + 200f)
                    val span = (maxVal - minVal).coerceAtLeast(1f)

                    val points = if (samplesMw.size == 1) {
                        listOf(
                            Offset(0f, h - ((samplesMw[0] - minVal) / span) * h),
                            Offset(w, h - ((samplesMw[0] - minVal) / span) * h)
                        )
                    } else {
                        val stepX = w / (samplesMw.size - 1).coerceAtLeast(1)
                        samplesMw.mapIndexed { idx, value ->
                            val x = idx * stepX
                            val norm = ((value - minVal) / span).coerceIn(0.05f, 0.95f)
                            Offset(x, h - norm * h)
                        }
                    }

                    val linePath = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            lineTo(points[i].x, points[i].y)
                        }
                    }

                    val fillPath = Path().apply {
                        addPath(linePath)
                        lineTo(points.last().x, h)
                        lineTo(points.first().x, h)
                        close()
                    }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                traceColor.copy(alpha = 0.28f),
                                traceColor.copy(alpha = 0.0f)
                            )
                        )
                    )

                    drawPath(
                        path = linePath,
                        color = traceColor,
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )

                    // Live pulsing terminal dot
                    val lastPt = points.last()
                    drawCircle(
                        color = traceColor.copy(alpha = 0.25f),
                        radius = 7.dp.toPx(),
                        center = lastPt
                    )
                    drawCircle(
                        color = traceColor,
                        radius = 3.5.dp.toPx(),
                        center = lastPt
                    )
                }
            }
        }
    }
}
