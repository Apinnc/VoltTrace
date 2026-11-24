package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.CauseSeverity
import com.example.domain.OvernightReplayEngine
import com.example.domain.OvernightReplayReport
import com.example.domain.OvernightReportMode
import com.example.domain.RootCauseAnalyzer
import com.example.domain.WatchInvestigationConfig
import com.example.ui.theme.LocalTelemetryColors
import com.example.ui.theme.TelemetryTextStyles
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.VoltTraceTheme
import java.util.Locale

@Composable
fun OvernightMorningReportCard(
    report: OvernightReplayReport,
    watchConfig: WatchInvestigationConfig,
    onOpenReplay: () -> Unit,
    onToggleWatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    val isFallback = report.mode == OvernightReportMode.BEFORE_PLUGGED_IN
    val isAwaiting = report.mode == OvernightReportMode.AWAITING_DATA

    val headerAccent = when {
        report.watchTriggeredThisWindow -> telemetry.critical
        report.baseline.isElevatedVsBaseline -> telemetry.warning
        isFallback -> telemetry.charging
        isAwaiting -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> telemetry.healthy
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("overnight_morning_report_card"),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (report.watchTriggeredThisWindow || report.baseline.isElevatedVsBaseline) {
                headerAccent.copy(alpha = 0.55f)
            } else {
                telemetry.cardBorder
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Mode Badge + Time Span
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = headerAccent.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (isFallback) Icons.Filled.BatteryChargingFull else Icons.Filled.NightsStay,
                            contentDescription = null,
                            tint = headerAccent,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = when (report.mode) {
                                OvernightReportMode.OVERNIGHT_STANDBY -> stringResource(R.string.overnight_morning_report_badge)
                                OvernightReportMode.BEFORE_PLUGGED_IN -> stringResource(R.string.overnight_before_plugged_in_badge)
                                OvernightReportMode.AWAITING_DATA -> stringResource(R.string.overnight_awaiting_badge)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = headerAccent
                        )
                    }
                }

                if (!isAwaiting) {
                    val rangeText = "${OvernightReplayEngine.formatClock(report.windowStartMs)} → ${OvernightReplayEngine.formatClock(report.windowEndMs)}"
                    Text(
                        text = "$rangeText (${RootCauseAnalyzer.formatDuration(report.durationMs)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isAwaiting) {
                Text(
                    text = "Your first Morning Report is calibrating",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = report.whatPhoneRecordedSummary.firstOrNull()
                        ?: "Leave VoltTrace running overnight or across an unplugged stretch to measure loss, build your personal baseline, and unlock One-Tap Replay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // "Before you plugged in" clarity subtitle
                if (isFallback) {
                    val pluggedClock = report.pluggedInAtMs?.let { OvernightReplayEngine.formatClock(it) }
                    Text(
                        text = if (pluggedClock != null) {
                            "Phone charged overnight (plugged in at $pluggedClock) — showing your last unplugged stretch before plugging in."
                        } else {
                            "Phone charged overnight — showing your most recent unplugged stretch."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = telemetry.charging
                    )
                }

                // 1. HOW MUCH BATTERY WAS LOST (2-second glance)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "-${report.levelLostPct}%",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = headerAccent,
                                modifier = Modifier.testTag("overnight_loss_pct")
                            )
                            Text(
                                text = String.format(Locale.US, "-%.0f mAh", report.chargeLostMah),
                                style = TelemetryTextStyles.MetricValue,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .padding(bottom = 4.dp)
                                    .testTag("overnight_loss_mah")
                            )
                        }
                        val counterTag = if (report.counterMeasured) "Counter-measured" else "Estimated from %"
                        val sleepTag = report.deepSleepPct?.let {
                            String.format(Locale.US, " · %.0f%% deep sleep", it)
                        }.orEmpty()
                        Text(
                            text = "${report.startLevelPct}% → ${report.endLevelPct}% · ${String.format(Locale.US, "%.1f%%/hr", report.drainRatePctPerHour)} · $counterTag$sleepTag",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // PERSONAL BASELINE COMPARISON STRIP
                val baselineTint = when {
                    report.baseline.isElevatedVsBaseline -> telemetry.warning
                    report.baseline.nightsCount >= 2 -> telemetry.healthy
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("overnight_baseline_pill"),
                    shape = MaterialTheme.shapes.medium,
                    color = baselineTint.copy(alpha = 0.11f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (report.baseline.isElevatedVsBaseline) Icons.AutoMirrored.Filled.TrendingUp else Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = baselineTint,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = report.baseline.statusLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = baselineTint
                        )
                    }
                }

                // WATCH MATCHED ALERT BANNER (if an armed investigation triggered on this night!)
                if (report.watchTriggeredThisWindow && !report.watchMatchReason.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overnight_watch_matched_banner"),
                        shape = MaterialTheme.shapes.medium,
                        color = telemetry.critical.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, telemetry.critical.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.NotificationsActive,
                                contentDescription = null,
                                tint = telemetry.critical,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Watch Triggered: ${watchConfig.title.ifBlank { "Overnight Anomaly" }}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = telemetry.critical
                                )
                                Text(
                                    text = report.watchMatchReason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // 2. WHICH PART OF THE NIGHT MATTERED (4-second glance + mini sparkline)
                report.keySegment?.let { keySeg ->
                    val segAccent = when (keySeg.severity) {
                        CauseSeverity.CRITICAL -> telemetry.critical
                        CauseSeverity.ELEVATED -> telemetry.warning
                        else -> telemetry.healthy
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overnight_key_segment_card"),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, segAccent.copy(alpha = 0.35f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Which Part Mattered Most",
                                style = MaterialTheme.typography.labelSmall,
                                color = segAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = keySeg.headline,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (report.samples.size >= 2) {
                                MiniOvernightSparkline(
                                    report = report,
                                    highlightAccent = segAccent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                )
                            }
                        }
                    }
                }

                // 3. WHAT THE PHONE ACTUALLY RECORDED (7-second glance)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "What Your Phone Recorded",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    report.whatPhoneRecordedSummary.take(3).forEach { fact ->
                        Text(
                            text = "• $fact",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // ACTIVE WATCH INDICATOR (when armed and waiting for next night)
                if (watchConfig.active && !report.watchTriggeredThisWindow) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Visibility,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Armed: ${watchConfig.title.ifBlank { watchConfig.suspectLabel }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            TextButton(
                                onClick = onToggleWatch,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("overnight_watch_disarm_inline")
                            ) {
                                Text("Clear", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // ONE-TAP REPLAY + WATCH FOR THIS AGAIN ACTIONS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenReplay,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("overnight_replay_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = if (isFallback) {
                                stringResource(R.string.action_replay_window)
                            } else {
                                stringResource(R.string.action_replay_night)
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    if (watchConfig.active) {
                        FilledTonalButton(
                            onClick = onToggleWatch,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("overnight_watch_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = stringResource(R.string.action_watching_active),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = onToggleWatch,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("overnight_watch_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = stringResource(R.string.action_watch_again),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniOvernightSparkline(
    report: OvernightReplayReport,
    highlightAccent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val telemetry = LocalTelemetryColors.current
    val samples = report.samples
    val keySeg = report.keySegment

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val count = samples.size
            if (count < 2) return@Canvas
            val w = size.width
            val h = size.height
            val stepX = w / (count - 1).coerceAtLeast(1)

            // Highlight band for the part of the night that mattered most
            if (keySeg != null) {
                val x1 = keySeg.startIndexInWindow * stepX
                val x2 = (keySeg.endIndexInWindow * stepX).coerceAtLeast(x1 + 6.dp.toPx())
                drawRect(
                    color = highlightAccent.copy(alpha = 0.22f),
                    topLeft = Offset(x1, 0f),
                    size = Size(x2 - x1, h)
                )
            }

            // Power trace inside the night
            val maxW = (samples.maxOfOrNull { it.powerWatts } ?: 1f).coerceAtLeast(1.0f)
            val powerPath = Path()
            samples.forEachIndexed { idx, s ->
                val x = idx * stepX
                val y = h - ((s.powerWatts / maxW).coerceIn(0.05f, 0.95f) * h)
                if (idx == 0) powerPath.moveTo(x, y) else powerPath.lineTo(x, y)
            }
            drawPath(
                path = powerPath,
                color = telemetry.warning.copy(alpha = 0.9f),
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Battery SOC slope
            val minPct = (samples.minOfOrNull { it.levelPct } ?: 0) - 1
            val maxPct = ((samples.maxOfOrNull { it.levelPct } ?: 100) + 1).coerceAtLeast(minPct + 2)
            val pctSpan = (maxPct - minPct).toFloat()
            val socPath = Path()
            samples.forEachIndexed { idx, s ->
                val x = idx * stepX
                val y = h - (((s.levelPct - minPct) / pctSpan).coerceIn(0.05f, 0.95f) * h)
                if (idx == 0) socPath.moveTo(x, y) else socPath.lineTo(x, y)
            }
            drawPath(
                path = socPath,
                color = telemetry.healthy,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewOvernightMorningReportCard() {
    VoltTraceTheme(themeMode = ThemeMode.DARK) {
        OvernightMorningReportCard(
            report = OvernightReplayEngine.createSyntheticPreviewReport(OvernightReportMode.OVERNIGHT_STANDBY),
            watchConfig = WatchInvestigationConfig(),
            onOpenReplay = {},
            onToggleWatch = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewBeforePluggedInFallbackCard() {
    VoltTraceTheme(themeMode = ThemeMode.DARK) {
        OvernightMorningReportCard(
            report = OvernightReplayEngine.createSyntheticPreviewReport(OvernightReportMode.BEFORE_PLUGGED_IN),
            watchConfig = WatchInvestigationConfig(),
            onOpenReplay = {},
            onToggleWatch = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
