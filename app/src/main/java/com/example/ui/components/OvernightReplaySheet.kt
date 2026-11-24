package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.BatterySampleEntity
import com.example.domain.CauseSeverity
import com.example.domain.OvernightReplayEngine
import com.example.domain.OvernightReplayReport
import com.example.domain.OvernightReportMode
import com.example.domain.RootCauseAnalyzer
import com.example.domain.WatchInvestigationConfig
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryTextStyles
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OvernightReplayBottomSheet(
    report: OvernightReplayReport,
    watchConfig: WatchInvestigationConfig,
    onDismiss: () -> Unit,
    onToggleWatch: () -> Unit,
    onInspectSample: (BatterySampleEntity) -> Unit
) {
    val telemetry = LocalTelemetryColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val samples = report.samples
    val initialIdx = report.keySegment?.peakIndexInWindow ?: 0

    var cursorIdx by remember(report.windowStartMs, samples.size) {
        mutableIntStateOf(initialIdx.coerceIn(0, (samples.size - 1).coerceAtLeast(0)))
    }
    var isPlaying by remember { mutableStateOf(false) }
    var showSourceReadings by remember { mutableStateOf(false) }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // One-tap animated replay loop across the overnight/fallback window
    LaunchedEffect(isPlaying, samples.size) {
        if (!isPlaying || samples.size < 2) return@LaunchedEffect
        if (cursorIdx >= samples.lastIndex) {
            cursorIdx = 0
        }
        while (isPlaying && cursorIdx < samples.lastIndex) {
            delay(850L)
            cursorIdx++
        }
        isPlaying = false
    }

    val safeIdx = cursorIdx.coerceIn(0, (samples.size - 1).coerceAtLeast(0))
    val activeSample = samples.getOrNull(safeIdx)
    val isFallback = report.mode == OvernightReportMode.BEFORE_PLUGGED_IN
    val keySeg = report.keySegment

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("overnight_replay_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (isFallback) Icons.Filled.BatteryChargingFull else Icons.Filled.NightsStay,
                            contentDescription = null,
                            tint = if (isFallback) telemetry.charging else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (isFallback) "Before You Plugged In — Replay" else "Overnight Replay",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "${OvernightReplayEngine.formatClock(report.windowStartMs)} → ${OvernightReplayEngine.formatClock(report.windowEndMs)} · ${RootCauseAnalyzer.formatDuration(report.durationMs)} · ${report.baseline.statusLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "-${report.levelLostPct}%",
                        style = TelemetryTextStyles.MetricValue,
                        color = if (report.baseline.isElevatedVsBaseline) telemetry.warning else telemetry.healthy
                    )
                    Text(
                        text = String.format(Locale.US, "-%.0f mAh", report.chargeLostMah),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Interactive Replay Canvas
            if (samples.size >= 2) {
                val crosshairColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(175.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .pointerInput(samples) {
                            detectTapGestures { offset ->
                                isPlaying = false
                                if (samples.isNotEmpty() && size.width > 0) {
                                    val ratio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    cursorIdx = (ratio * (samples.size - 1)).toInt()
                                }
                            }
                        }
                        .pointerInput(samples) {
                            detectHorizontalDragGestures { change, _ ->
                                isPlaying = false
                                if (samples.isNotEmpty() && size.width > 0) {
                                    val ratio = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    cursorIdx = (ratio * (samples.size - 1)).toInt()
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 12.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val count = samples.size
                        val stepX = w / (count - 1).coerceAtLeast(1)

                        // Subtle gridlines
                        listOf(0.25f, 0.5f, 0.75f).forEach { frac ->
                            drawLine(
                                color = telemetry.gridLine.copy(alpha = 0.5f),
                                start = Offset(0f, h * frac),
                                end = Offset(w, h * frac),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                            )
                        }

                        // Highlight band for Key Segment ("which part of the night mattered")
                        if (keySeg != null) {
                            val x1 = keySeg.startIndexInWindow * stepX
                            val x2 = (keySeg.endIndexInWindow * stepX).coerceAtLeast(x1 + 8.dp.toPx())
                            drawRect(
                                color = telemetry.critical.copy(alpha = 0.18f),
                                topLeft = Offset(x1, 0f),
                                size = Size(x2 - x1, h)
                            )
                        }

                        // Power curve (amber)
                        val maxW = (samples.maxOfOrNull { it.powerWatts } ?: 1f).coerceAtLeast(1.0f)
                        val powerPts = samples.mapIndexed { idx, s ->
                            val x = idx * stepX
                            val y = h - ((s.powerWatts / maxW).coerceIn(0.05f, 0.94f) * h)
                            Offset(x, y)
                        }
                        val powerPath = Path().apply {
                            moveTo(powerPts.first().x, powerPts.first().y)
                            for (i in 1 until powerPts.size) lineTo(powerPts[i].x, powerPts[i].y)
                        }
                        drawPath(
                            path = powerPath,
                            color = telemetry.warning,
                            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        // Battery SOC curve (volt green)
                        val minPct = (samples.minOfOrNull { it.levelPct } ?: 0) - 1
                        val maxPct = ((samples.maxOfOrNull { it.levelPct } ?: 100) + 1).coerceAtLeast(minPct + 2)
                        val pctSpan = (maxPct - minPct).toFloat()
                        val socPts = samples.mapIndexed { idx, s ->
                            val x = idx * stepX
                            val y = h - (((s.levelPct - minPct) / pctSpan).coerceIn(0.06f, 0.94f) * h)
                            Offset(x, y)
                        }
                        val socPath = Path().apply {
                            moveTo(socPts.first().x, socPts.first().y)
                            for (i in 1 until socPts.size) lineTo(socPts[i].x, socPts[i].y)
                        }
                        drawPath(
                            path = socPath,
                            color = telemetry.healthy,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        // Active replay cursor
                        val cx = safeIdx * stepX
                        drawLine(
                            color = crosshairColor,
                            start = Offset(cx, 0f),
                            end = Offset(cx, h),
                            strokeWidth = 1.5.dp.toPx()
                        )
                        drawCircle(
                            color = telemetry.warning,
                            radius = 5.dp.toPx(),
                            center = powerPts[safeIdx]
                        )
                        drawCircle(
                            color = telemetry.healthy,
                            radius = 4.5.dp.toPx(),
                            center = socPts[safeIdx]
                        )
                    }
                }

                // Transport Bar: Prev / Play-Pause / Next + Jump to Key Window
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                isPlaying = false
                                cursorIdx = (safeIdx - 1).coerceAtLeast(0)
                            },
                            enabled = safeIdx > 0,
                            modifier = Modifier.testTag("replay_step_prev")
                        ) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous reading")
                        }

                        Button(
                            onClick = { isPlaying = !isPlaying },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("replay_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause replay" else "Play replay",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(if (isPlaying) "Pause" else "Play")
                        }

                        FilledTonalIconButton(
                            onClick = {
                                isPlaying = false
                                cursorIdx = (safeIdx + 1).coerceAtMost(samples.lastIndex)
                            },
                            enabled = safeIdx < samples.lastIndex,
                            modifier = Modifier.testTag("replay_step_next")
                        ) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "Next reading")
                        }
                    }

                    if (keySeg != null) {
                        AssistChip(
                            onClick = {
                                isPlaying = false
                                cursorIdx = keySeg.peakIndexInWindow.coerceIn(0, samples.lastIndex)
                            },
                            label = {
                                Text(
                                    text = "Key Peak",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Bolt,
                                    contentDescription = null,
                                    tint = telemetry.critical,
                                    modifier = Modifier.size(15.dp)
                                )
                            },
                            modifier = Modifier.testTag("replay_jump_key_window")
                        )
                    }
                }
            }

            // Live Stepped Sample Readout
            if (activeSample != null) {
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
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Step ${safeIdx + 1}/${samples.size} · ${timeFmt.format(Date(activeSample.timestampMs))}",
                                style = TelemetryTextStyles.MetricSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${activeSample.levelPct}%  ·  ${String.format(Locale.US, "%+.2f W", activeSample.powerSignedWatts)}",
                                style = TelemetryTextStyles.MetricSmall,
                                color = if (activeSample.powerMw >= 2_200f) telemetry.critical else telemetry.warning
                            )
                        }

                        val counterStr = if (activeSample.chargeCounterMicroAh > 0L) {
                            String.format(Locale.US, "Counter: %.0f mAh", activeSample.chargeCounterMicroAh / 1000f)
                        } else {
                            "Counter unavailable"
                        }
                        val appStr = (activeSample.foregroundLabel ?: activeSample.foregroundPackage)
                            ?.takeIf { it.isNotBlank() } ?: "No foreground app logged"
                        Text(
                            text = "${String.format(Locale.US, "%+.0f mA @ %.3f V · %.1f°C", activeSample.currentSignedMa, activeSample.voltageVolts, activeSample.temperatureCelsius)} · $counterStr",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${if (activeSample.isScreenOn) "Screen ON (${activeSample.screenBrightnessPct}%)" else "Screen OFF"} · ${activeSample.networkTransport} · $appStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        FilledTonalButton(
                            onClick = { onInspectSample(activeSample) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("replay_inspect_sample_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ManageSearch,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Drill Down Apps & Services at This Reading")
                        }
                    }
                }
            }

            // Night Chapters (clickable segments showing what happened in each part of the night)
            if (report.segments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Night Breakdown (Tap a Chapter to Jump)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    report.segments.forEach { seg ->
                        val segAccent = when (seg.severity) {
                            CauseSeverity.CRITICAL -> telemetry.critical
                            CauseSeverity.ELEVATED -> telemetry.warning
                            else -> telemetry.healthy
                        }
                        val isCurrent = safeIdx in seg.startIndexInWindow..seg.endIndexInWindow
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .clickable {
                                    isPlaying = false
                                    cursorIdx = seg.peakIndexInWindow.coerceIn(0, (samples.size - 1).coerceAtLeast(0))
                                },
                            shape = MaterialTheme.shapes.medium,
                            color = if (isCurrent) segAccent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, if (isCurrent) segAccent.copy(alpha = 0.55f) else telemetry.cardBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = seg.headline,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                seg.whatPhoneRecorded.forEach { line ->
                                    Text(
                                        text = "• $line",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // What Remains Unknown
            if (report.whatRemainsUnknown.isNotEmpty()) {
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
                                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "What Remains Unknown",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        report.whatRemainsUnknown.forEach { unk ->
                            Text(
                                text = "• $unk",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Expandable Verbatim Source Readings for Key Segment
            if (keySeg != null && keySeg.sourceReadings.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showSourceReadings = !showSourceReadings },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("replay_toggle_source_readings")
                ) {
                    Icon(
                        imageVector = if (showSourceReadings) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(if (showSourceReadings) "Hide Key Window Source Readings" else "Show Verbatim Source Readings")
                }

                if (showSourceReadings) {
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
                            keySeg.sourceReadings.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Watch for this again CTA
            Button(
                onClick = onToggleWatch,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("replay_watch_again_button")
            ) {
                Icon(
                    imageVector = if (watchConfig.active) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = if (watchConfig.active) {
                        "Stop Watching (${watchConfig.title.ifBlank { watchConfig.suspectLabel }})"
                    } else {
                        "Watch for this again (${report.suggestedWatchSuspect})"
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
