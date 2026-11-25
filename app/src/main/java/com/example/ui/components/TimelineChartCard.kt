package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.BatterySampleEntity
import com.example.domain.CauseSeverity
import com.example.domain.TimelineSpikeMarker
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryTextStyles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ChartOverlayMetric(val label: String) {
    POWER("Power (W)"),
    TEMP("Temp (°C)"),
    VOLTAGE("Voltage (V)")
}

/**
 * Maps a sample timestamp linearly onto horizontal chart width [0f..width] so uneven
 * sampling intervals (e.g., 1-minute live bursts vs 2-hour standby gaps) render proportionally.
 */
internal fun timelineTimestampToX(
    timestampMs: Long,
    minTs: Long,
    spanMs: Long,
    width: Float
): Float {
    if (width <= 0f || spanMs <= 0L) return 0f
    val frac = ((timestampMs - minTs).toDouble() / spanMs.toDouble()).toFloat().coerceIn(0f, 1f)
    return frac * width
}

/**
 * Resolves a horizontal touch/drag X coordinate to the index of the sample whose timestamp
 * is nearest to that time position.
 */
internal fun nearestSampleIndexForTouchX(
    touchX: Float,
    width: Float,
    samples: List<BatterySampleEntity>
): Int {
    if (samples.size <= 1 || width <= 0f) return 0
    val minTs = samples.first().timestampMs
    val maxTs = samples.last().timestampMs
    val spanMs = maxTs - minTs
    if (spanMs <= 0L) {
        val ratio = (touchX / width).coerceIn(0f, 1f)
        return (ratio * (samples.size - 1)).toInt().coerceIn(0, samples.lastIndex)
    }
    val frac = (touchX / width).coerceIn(0f, 1f).toDouble()
    val targetTs = minTs + (frac * spanMs.toDouble()).toLong()
    var bestIdx = 0
    var bestDist = Long.MAX_VALUE
    for (i in samples.indices) {
        val dist = kotlin.math.abs(samples[i].timestampMs - targetTs)
        if (dist < bestDist) {
            bestDist = dist
            bestIdx = i
        }
    }
    return bestIdx
}

@Composable
fun TimelineChartCard(
    samples: List<BatterySampleEntity>,
    spikeMarkers: List<TimelineSpikeMarker> = emptyList(),
    isInvestigating: Boolean = false,
    onDrillDownSample: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    var overlay by remember { mutableStateOf(ChartOverlayMetric.POWER) }
    var selectedIdx by remember(samples.size) { mutableIntStateOf((samples.size - 1).coerceAtLeast(0)) }

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val chartCd = stringResource(R.string.cd_timeline_chart)
    val drillDownCd = stringResource(R.string.cd_drill_down_sample)

    val overlayColor = when (overlay) {
        ChartOverlayMetric.POWER -> telemetry.warning
        ChartOverlayMetric.TEMP -> telemetry.critical
        ChartOverlayMetric.VOLTAGE -> telemetry.charging
    }
    val crosshairColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val safeSelectedIdx = selectedIdx.coerceIn(0, (samples.size - 1).coerceAtLeast(0))
    val inspected = samples.getOrNull(safeSelectedIdx)
    val spikeIndexSet = remember(spikeMarkers) { spikeMarkers.associateBy { it.sampleIndex } }

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
            // Overlay selector chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChartOverlayMetric.entries.forEach { metric ->
                    FilterChip(
                        selected = overlay == metric,
                        onClick = { overlay = metric },
                        label = {
                            Text(
                                text = metric.label,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        modifier = Modifier.testTag("chart_overlay_${metric.name.lowercase()}")
                    )
                }
            }

            // Inspected sample readout bar with direct Drill Down button
            if (inspected != null) {
                val powerAccent = when {
                    inspected.isCharging -> telemetry.charging
                    !inspected.isScreenOn && inspected.powerMw >= 1_500f -> telemetry.critical
                    inspected.powerMw >= 3_800f -> telemetry.warning
                    else -> MaterialTheme.colorScheme.primary
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = timeFormatter.format(Date(inspected.timestampMs)),
                                style = TelemetryTextStyles.MetricSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${inspected.levelPct}%  ·  ${String.format(Locale.US, "%+.2f W", inspected.powerSignedWatts)}  ·  ${String.format(Locale.US, "%.1f°C", inspected.temperatureCelsius)}",
                                style = TelemetryTextStyles.MetricSmall,
                                color = powerAccent
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val activeHwTags = buildList {
                                if (inspected.isScreenOn) add("DSP ${inspected.screenBrightnessPct}%") else add("OFF")
                                if (inspected.cellularActive) add("CELL")
                                if (inspected.wifiConnected) add("WIFI")
                                if (inspected.gpsEnabled) add("LOC")
                                if (inspected.bluetoothEnabled) add("BT")
                                if (inspected.isCharging) add("CHG")
                            }.joinToString(" · ")

                            Text(
                                text = activeHwTags,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )

                            FilledTonalButton(
                                onClick = { onDrillDownSample(safeSelectedIdx) },
                                enabled = !isInvestigating,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .testTag("timeline_drill_down_button")
                                    .semantics { contentDescription = drillDownCd }
                            ) {
                                if (isInvestigating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ManageSearch,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.size(5.dp))
                                Text(
                                    text = stringResource(R.string.action_drill_down),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }

            // Dual-axis interactive Canvas chart (timestamp-proportional horizontal axis)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(195.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .semantics { contentDescription = chartCd }
                    .pointerInput(samples) {
                        detectTapGestures { offset ->
                            if (samples.isNotEmpty() && size.width > 0) {
                                val padPx = 8.dp.toPx()
                                val innerW = (size.width - padPx * 2f).coerceAtLeast(1f)
                                selectedIdx = nearestSampleIndexForTouchX(
                                    touchX = offset.x - padPx,
                                    width = innerW,
                                    samples = samples
                                )
                            }
                        }
                    }
                    .pointerInput(samples) {
                        detectHorizontalDragGestures { change, _ ->
                            if (samples.isNotEmpty() && size.width > 0) {
                                val padPx = 8.dp.toPx()
                                val innerW = (size.width - padPx * 2f).coerceAtLeast(1f)
                                selectedIdx = nearestSampleIndexForTouchX(
                                    touchX = change.position.x - padPx,
                                    width = innerW,
                                    samples = samples
                                )
                            }
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 12.dp)
            ) {
                if (samples.size < 2) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Collecting longitudinal samples — tap Log Snapshot or let VoltTrace monitor over time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                } else {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val count = samples.size
                        val minTs = samples.first().timestampMs
                        val spanMs = (samples.last().timestampMs - minTs).coerceAtLeast(0L)
                        val fallbackStepX = w / (count - 1).coerceAtLeast(1)
                        fun sampleX(idx: Int): Float = if (spanMs > 0L) {
                            timelineTimestampToX(samples[idx].timestampMs, minTs, spanMs, w)
                        } else {
                            idx * fallbackStepX
                        }

                        // Horizontal reference grid (25%, 50%, 75%)
                        listOf(0.25f, 0.5f, 0.75f).forEach { frac ->
                            val y = h * frac
                            drawLine(
                                color = telemetry.gridLine.copy(alpha = 0.55f),
                                start = Offset(0f, y),
                                end = Offset(w, y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                            )
                        }

                        // Background bands: Charging (cyan tint) vs Screen-On (green tint)
                        for (i in 0 until count - 1) {
                            val s = samples[i]
                            val bandColor = when {
                                s.isCharging -> telemetry.charging.copy(alpha = 0.14f)
                                s.isScreenOn -> telemetry.screenOnBand
                                else -> null
                            }
                            if (bandColor != null) {
                                val x1 = sampleX(i)
                                val x2 = sampleX(i + 1)
                                drawRect(
                                    color = bandColor,
                                    topLeft = Offset(x1, 0f),
                                    size = Size((x2 - x1).coerceAtLeast(0f), h)
                                )
                            }
                        }

                        // Secondary overlay curve (Power / Temp / Voltage)
                        val secValues = samples.map { s ->
                            when (overlay) {
                                ChartOverlayMetric.POWER -> s.powerWatts
                                ChartOverlayMetric.TEMP -> s.temperatureCelsius
                                ChartOverlayMetric.VOLTAGE -> s.voltageVolts
                            }
                        }
                        val secMin = if (overlay == ChartOverlayMetric.POWER) 0f else (secValues.minOrNull() ?: 0f) * 0.9f
                        val secMax = ((secValues.maxOrNull() ?: 1f) * 1.1f).coerceAtLeast(secMin + 0.5f)
                        val secSpan = (secMax - secMin).coerceAtLeast(0.1f)

                        val secPoints = secValues.mapIndexed { i, v ->
                            val x = sampleX(i)
                            val y = h - (((v - secMin) / secSpan).coerceIn(0.05f, 0.95f) * h)
                            Offset(x, y)
                        }

                        if (overlay == ChartOverlayMetric.POWER) {
                            // Color-code power trace segments: cyan = Charging input, amber = Discharging drain
                            for (i in 0 until secPoints.size - 1) {
                                val segCharging = samples[i].isCharging || samples[i + 1].isCharging
                                drawLine(
                                    color = (if (segCharging) telemetry.charging else telemetry.warning).copy(alpha = 0.9f),
                                    start = secPoints[i],
                                    end = secPoints[i + 1],
                                    strokeWidth = 2.1.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }
                        } else {
                            val secPath = Path().apply {
                                moveTo(secPoints.first().x, secPoints.first().y)
                                for (i in 1 until secPoints.size) {
                                    lineTo(secPoints[i].x, secPoints[i].y)
                                }
                            }
                            drawPath(
                                path = secPath,
                                color = overlayColor.copy(alpha = 0.85f),
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }

                        // Highlight detected discharging spike markers on the curve
                        if (overlay == ChartOverlayMetric.POWER) {
                            spikeIndexSet.forEach { (idx, marker) ->
                                val pt = secPoints.getOrNull(idx) ?: return@forEach
                                val markerColor = if (marker.severity == CauseSeverity.CRITICAL) {
                                    telemetry.critical
                                } else {
                                    telemetry.warning
                                }
                                drawCircle(
                                    color = markerColor.copy(alpha = 0.28f),
                                    radius = 8.dp.toPx(),
                                    center = pt
                                )
                                drawCircle(
                                    color = markerColor,
                                    radius = 4.dp.toPx(),
                                    center = pt
                                )
                            }
                        }

                        // Primary Battery % curve
                        val pctPoints = samples.mapIndexed { i, s ->
                            val x = sampleX(i)
                            val y = h - ((s.levelPct.coerceIn(0, 100) / 100f) * h * 0.92f + h * 0.04f)
                            Offset(x, y)
                        }

                        val pctPath = Path().apply {
                            moveTo(pctPoints.first().x, pctPoints.first().y)
                            for (i in 1 until pctPoints.size) {
                                lineTo(pctPoints[i].x, pctPoints[i].y)
                            }
                        }

                        val pctFill = Path().apply {
                            addPath(pctPath)
                            lineTo(pctPoints.last().x, h)
                            lineTo(pctPoints.first().x, h)
                            close()
                        }

                        drawPath(
                            path = pctFill,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    telemetry.healthy.copy(alpha = 0.22f),
                                    telemetry.healthy.copy(alpha = 0.0f)
                                )
                            )
                        )

                        drawPath(
                            path = pctPath,
                            color = telemetry.healthy,
                            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        // Scrubbing crosshair
                        val cx = pctPoints[safeSelectedIdx].x
                        val cy = pctPoints[safeSelectedIdx].y
                        drawLine(
                            color = crosshairColor,
                            start = Offset(cx, 0f),
                            end = Offset(cx, h),
                            strokeWidth = 1.2.dp.toPx()
                        )
                        drawCircle(
                            color = telemetry.healthy,
                            radius = 5.dp.toPx(),
                            center = Offset(cx, cy)
                        )
                    }
                }
            }

            // Legend row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendChip(color = telemetry.healthy, label = "Battery %")
                LegendChip(
                    color = overlayColor,
                    label = if (overlay == ChartOverlayMetric.POWER) "Drain (W)" else overlay.label
                )
                LegendChip(
                    color = telemetry.charging,
                    label = if (overlay == ChartOverlayMetric.POWER) "Charge (+W)" else "Charging"
                )
                LegendChip(color = telemetry.healthy.copy(alpha = 0.35f), label = "Screen On")
            }

            // One-tap Detected Spikes jump chips
            if (spikeMarkers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.section_detected_spikes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        spikeMarkers.forEach { marker ->
                            val chipTint = if (marker.severity == CauseSeverity.CRITICAL) {
                                telemetry.critical
                            } else {
                                telemetry.warning
                            }
                            AssistChip(
                                onClick = {
                                    selectedIdx = marker.sampleIndex
                                    onDrillDownSample(marker.sampleIndex)
                                },
                                label = {
                                    Text(
                                        text = "${marker.label} · ${marker.reasonTag}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Bolt,
                                        contentDescription = null,
                                        tint = chipTint,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                border = BorderStroke(1.dp, chipTint.copy(alpha = 0.45f)),
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = chipTint.copy(alpha = 0.1f)
                                ),
                                modifier = Modifier.testTag("spike_chip_${marker.sampleIndex}")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendChip(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
                .padding(horizontal = 5.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
