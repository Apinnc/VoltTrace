package com.example.domain

import com.example.data.hardware.ObservedAppUsage
import com.example.data.local.AppDrainEventEntity
import com.example.data.local.BatterySampleEntity
import com.example.data.local.EnergyMeterSnapshotEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class WindowUsageEventItem(
    val timestampMs: Long,
    val packageName: String,
    val appLabel: String,
    val eventLabel: String,
    val isBackgroundService: Boolean
)

data class TimelineSpikeMarker(
    val sampleIndex: Int,
    val sample: BatterySampleEntity,
    val label: String,
    val reasonTag: String,
    val severity: CauseSeverity
)

data class SpikeForensicReport(
    val targetIndex: Int,
    val targetSample: BatterySampleEntity,
    val prevSample: BatterySampleEntity?,
    val nextSample: BatterySampleEntity?,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val classification: String,
    val severity: CauseSeverity,
    val summaryHeadline: String,
    val chargeLostBeforeMah: Float?,
    val chargeLostAfterMah: Float?,
    val counterMeasured: Boolean,
    val cpuAwakeBeforeMs: Long?,
    val deepSleepBeforeMs: Long?,
    val awakePctBefore: Float?,
    val cpuAwakeAfterMs: Long?,
    val deepSleepAfterMs: Long?,
    val awakePctAfter: Float?,
    val voltageDeltaMv: Int?,
    val tempDeltaC: Float?,
    val stateChanges: List<String>,
    val liveHardwareBreakdown: List<HardwareSubsystemStatus>,
    val correlatedEvents: List<WindowUsageEventItem>,
    val correlatedAppUsage: List<ObservedAppUsage>,
    val energyMeterDeltas: List<EnergyMeterSummary>,
    val likelyDrivers: List<String>,
    val whatRemainsUnknown: List<String>,
    val sourceReadings: List<String>
)

/**
 * Pure Kotlin forensic analyzer for drilling down on any specific timestamp or power
 * spike on the VoltTrace timeline.
 */
object SpikeInspector {

    /**
     * Identifies notable spikes in the current timeline window so the user can jump directly
     * to unexplained standby bursts or high-wattage discharge peaks.
     */
    fun detectNotableSpikes(samples: List<BatterySampleEntity>): List<TimelineSpikeMarker> {
        if (samples.size < 2) return emptyList()
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val candidates = mutableListOf<Pair<Float, TimelineSpikeMarker>>()

        for (i in samples.indices) {
            val s = samples[i]
            val prev = samples.getOrNull(i - 1)
            val next = samples.getOrNull(i + 1)

            val prevPower = prev?.powerMw ?: 0f
            val nextPower = next?.powerMw ?: 0f
            val isLocalPeak = s.powerMw >= prevPower && s.powerMw >= nextPower

            // Prioritize discharging spikes, especially screen-off standby spikes
            if (!s.isCharging) {
                val isStandbySpike = !s.isScreenOn && s.powerMw >= 1_500f && (isLocalPeak || s.powerMw >= 2_500f)
                val isActiveSpike = s.isScreenOn && s.powerMw >= 3_800f && isLocalPeak
                val hasLargeCounterDrop = prev != null && !prev.isCharging &&
                    prev.chargeCounterMicroAh > 0L && s.chargeCounterMicroAh > 0L &&
                    ((prev.chargeCounterMicroAh - s.chargeCounterMicroAh) / 1000f) >= 85f

                if (isStandbySpike || isActiveSpike || hasLargeCounterDrop) {
                    val reason = when {
                        isStandbySpike -> "Screen-Off Spike"
                        isActiveSpike -> "Active Load Peak"
                        else -> "Heavy Charge Drop"
                    }
                    val severity = when {
                        !s.isScreenOn && s.powerMw >= 3_000f -> CauseSeverity.CRITICAL
                        s.powerMw >= 6_000f -> CauseSeverity.CRITICAL
                        else -> CauseSeverity.ELEVATED
                    }
                    val score = s.powerMw + (if (!s.isScreenOn) 3_500f else 0f)
                    candidates.add(
                        score to TimelineSpikeMarker(
                            sampleIndex = i,
                            sample = s,
                            label = String.format(
                                Locale.US,
                                "%s · -%.1f W",
                                timeFmt.format(Date(s.timestampMs)),
                                s.powerWatts
                            ),
                            reasonTag = reason,
                            severity = severity
                        )
                    )
                }
            }
        }

        // Deduplicate markers within 5 minutes of a higher-scoring spike
        val selected = mutableListOf<TimelineSpikeMarker>()
        candidates.sortedByDescending { it.first }.forEach { (_, marker) ->
            val tooClose = selected.any {
                abs(it.sample.timestampMs - marker.sample.timestampMs) < 5L * 60_000L
            }
            if (!tooClose && selected.size < 6) {
                selected.add(marker)
            }
        }
        return selected.sortedBy { it.sample.timestampMs }
    }

    /**
     * Produces a complete, source-traceable forensic breakdown for a single selected
     * timeline sample and its enclosing interval.
     */
    fun analyzeSpike(
        samples: List<BatterySampleEntity>,
        targetIndex: Int,
        usageEvents: List<WindowUsageEventItem>,
        appUsage: List<ObservedAppUsage>,
        appDrainEvents: List<AppDrainEventEntity>,
        energyMeters: List<EnergyMeterSnapshotEntity>,
        hasUsageAccess: Boolean,
        ratedCapacityMah: Int
    ): SpikeForensicReport {
        val safeIdx = targetIndex.coerceIn(0, (samples.size - 1).coerceAtLeast(0))
        val target = samples[safeIdx]
        val prev = samples.getOrNull(safeIdx - 1)
        val next = samples.getOrNull(safeIdx + 1)

        val windowStartMs = prev?.timestampMs?.coerceAtLeast(target.timestampMs - 20L * 60_000L)
            ?: (target.timestampMs - 10L * 60_000L)
        val windowEndMs = next?.timestampMs?.coerceAtMost(target.timestampMs + 10L * 60_000L)
            ?: (target.timestampMs + 5L * 60_000L)

        val (chargeBefore, counterBefore) = computePairChargeLoss(prev, target, ratedCapacityMah)
        val (chargeAfter, counterAfter) = computePairChargeLoss(target, next, ratedCapacityMah)
        val counterMeasured = counterBefore || counterAfter

        val clocksBefore = computeBootClockDeltas(prev, target)
        val clocksAfter = computeBootClockDeltas(target, next)

        val voltageDeltaMv = prev?.let { target.voltageMv - it.voltageMv }
        val tempDeltaC = prev?.let { target.temperatureCelsius - it.temperatureCelsius }

        val (classification, severity, headline) = when {
            target.isCharging -> Triple(
                "Charging Current (Not Battery Drain)",
                CauseSeverity.HEALTHY,
                String.format(
                    Locale.US,
                    "Plugged in (%s) drawing %+.2f W at %d%% SOC — power is entering the battery, not draining it.",
                    target.pluggedType,
                    target.powerSignedWatts,
                    target.levelPct
                )
            )
            !target.isScreenOn && target.powerMw >= 2_500f -> Triple(
                "Screen-Off Standby Power Spike",
                CauseSeverity.CRITICAL,
                String.format(
                    Locale.US,
                    "High -%.2f W draw while screen was OFF (%d%% SOC, %.1f°C). Indicates a background wakeup, service burst, or radio surge.",
                    target.powerWatts,
                    target.levelPct,
                    target.temperatureCelsius
                )
            )
            !target.isScreenOn && target.powerMw >= 1_000f -> Triple(
                "Elevated Screen-Off Background Draw",
                CauseSeverity.ELEVATED,
                String.format(
                    Locale.US,
                    "Elevated -%.2f W draw during screen-off standby (%d%% SOC).",
                    target.powerWatts,
                    target.levelPct
                )
            )
            target.isScreenOn && target.powerMw >= 3_800f -> Triple(
                "Active Screen-On Load Spike",
                CauseSeverity.ELEVATED,
                String.format(
                    Locale.US,
                    "Heavy -%.2f W active draw with display ON (%d%% brightness, %.0f Hz).",
                    target.powerWatts,
                    target.screenBrightnessPct,
                    target.displayRefreshRateHz
                )
            )
            else -> Triple(
                if (target.isScreenOn) "Normal Screen-On Telemetry" else "Normal Standby Telemetry",
                CauseSeverity.HEALTHY,
                String.format(
                    Locale.US,
                    "Measured %+.2f W at %d%% SOC (%.1f°C).",
                    target.powerSignedWatts,
                    target.levelPct,
                    target.temperatureCelsius
                )
            )
        }

        val stateChanges = buildList {
            if (prev != null) {
                if (prev.isCharging != target.isCharging) {
                    add(if (target.isCharging) "Charger connected (${target.pluggedType})" else "Unplugged from ${prev.pluggedType} to Battery")
                }
                if (prev.isScreenOn != target.isScreenOn) {
                    add(if (target.isScreenOn) "Screen woke ON (${target.screenBrightnessPct}% brightness)" else "Screen turned OFF")
                }
                if (prev.networkTransport != target.networkTransport) {
                    add("Network switched: ${prev.networkTransport} → ${target.networkTransport}")
                }
                if (prev.wifiConnected != target.wifiConnected && target.wifiEnabled && !target.wifiConnected) {
                    add("Wi-Fi disconnected (entered idle SSID scan)")
                }
                if (voltageDeltaMv != null && voltageDeltaMv <= -40) {
                    add("Voltage sag of ${voltageDeltaMv} mV (${prev.voltageMv} → ${target.voltageMv} mV) indicating sudden current load")
                }
                if (tempDeltaC != null && tempDeltaC >= 1.0f) {
                    add(String.format(Locale.US, "Cell warmed %+.1f°C (%.1f°C → %.1f°C)", tempDeltaC, prev.temperatureCelsius, target.temperatureCelsius))
                }
            }
            if (isEmpty()) {
                add("No abrupt radio or display state change at this exact sample boundary")
            }
        }

        val liveHardware = RootCauseAnalyzer.estimateLiveSubsystems(target)

        // Filter persisted Room AppDrainEvent rows overlapping the spike window
        val overlappingPersistedApps = appDrainEvents
            .filter { it.timestampMs in (windowStartMs - 15L * 60_000L)..(windowEndMs + 15L * 60_000L) }
            .groupBy { it.packageName }
            .map { (pkg, rows) ->
                ObservedAppUsage(
                    packageName = pkg,
                    appLabel = rows.last().appLabel,
                    foregroundMs = rows.sumOf { it.foregroundMs },
                    launchCount = rows.sumOf { it.launchCount }.coerceAtLeast(1)
                )
            }
        val mergedApps = (appUsage + overlappingPersistedApps)
            .groupBy { it.packageName }
            .map { (pkg, list) ->
                ObservedAppUsage(
                    packageName = pkg,
                    appLabel = list.first().appLabel,
                    foregroundMs = list.maxOf { it.foregroundMs },
                    launchCount = list.maxOf { it.launchCount }
                )
            }
            .sortedByDescending { it.foregroundMs }
            .take(8)

        // Summarize any PowerMonitor energy meters bracketing this spike
        val bracketingMeters = energyMeters.filter {
            it.timestampMs in (windowStartMs - 20L * 60_000L)..(windowEndMs + 20L * 60_000L)
        }
        val meterDeltas = RootCauseAnalyzer.summarizeEnergyMeters(
            snapshots = bracketingMeters,
            avgVoltageVolts = target.voltageVolts.coerceIn(3.4f, 4.4f)
        ).filter { it.deltaMWh > 0.01f }

        val bgServices = usageEvents.filter { it.isBackgroundService }
        val fgAppAtBoundary = (target.foregroundLabel ?: target.foregroundPackage)?.takeIf { it.isNotBlank() }

        val likelyDrivers = buildList {
            if (target.isCharging) {
                add("Device was actively plugged into ${target.pluggedType} power — current shown is charge input, not battery drain.")
            } else {
                if (bgServices.isNotEmpty()) {
                    val names = bgServices.map { it.appLabel }.distinct().take(4).joinToString(", ")
                    add("Background foreground-service activity recorded around this timestamp: $names.")
                }
                if (fgAppAtBoundary != null) {
                    add("Foreground app recorded at this sample: $fgAppAtBoundary.")
                } else if (mergedApps.isNotEmpty()) {
                    val top = mergedApps.first()
                    add("Most active app in surrounding window: ${top.appLabel} (${RootCauseAnalyzer.formatDuration(top.foregroundMs)}).")
                }
                if (clocksBefore != null && clocksBefore.awakePct >= 75f && !target.isScreenOn) {
                    add(
                        String.format(
                            Locale.US,
                            "CPU remained awake %.0f%% (%s awake / %s deep sleep) during the preceding interval, pointing to a partial wakelock or periodic wakeup loop.",
                            clocksBefore.awakePct,
                            RootCauseAnalyzer.formatDuration(clocksBefore.awakeMs),
                            RootCauseAnalyzer.formatDuration(clocksBefore.sleepMs)
                        )
                    )
                }
                if (target.cellularActive) {
                    add("Cellular modem was active (${target.networkTransport}${if (target.isMeteredNetwork) ", metered" else ""}), which draws significantly more power than idle Wi-Fi.")
                } else if (target.wifiEnabled && !target.wifiConnected) {
                    add("Wi-Fi was ON but disconnected, causing periodic background SSID scanning.")
                }
                if (target.isScreenOn) {
                    add("Display was ON at ${target.screenBrightnessPct}% brightness (${target.displayRefreshRateHz.toInt()} Hz).")
                }
                if (chargeBefore != null && chargeBefore >= 30f) {
                    add(
                        String.format(
                            Locale.US,
                            "Confirmed %.1f mAh real charge loss in the interval leading into this spike.",
                            chargeBefore
                        )
                    )
                }
                if (isEmpty()) {
                    add("Short transient burst with no foreground app or radio handoff logged at the sample boundary.")
                }
            }
        }

        val unknowns = buildList {
            if (!hasUsageAccess) {
                add("Usage Access permission is not granted — grant it on the Causes or Inspector tab so VoltTrace can list exact apps and background services active during spikes.")
            } else if (usageEvents.isEmpty() && !target.isCharging) {
                add("No foreground app opens or foreground-service starts were emitted by Android UsageEvents within this window — activity was likely an OS/kernel background job, sync, or partial wakelock.")
            }
            if (prev != null && (target.timestampMs - prev.timestampMs) >= 12L * 60_000L) {
                add("Gap of ${RootCauseAnalyzer.formatDuration(target.timestampMs - prev.timestampMs)} before this sample — shorter bursts inside that gap were not sampled.")
            }
            if (!target.isCharging && !target.isScreenOn) {
                add("Ordinary Android permissions cannot read kernel partial-wakelock holder names or UID wakeup counts (those require ADB dumpsys batterystats).")
            }
            if (target.gpsEnabled) {
                add("Location toggle was ON, but Android does not expose actual third-party GNSS satellite lock time.")
            }
            if (meterDeltas.isEmpty()) {
                add("No PowerMonitor hardware rail deltas bracketed this timestamp, so subsystem mW split is modeled.")
            }
        }

        val sourceReadings = buildList {
            prev?.let { add("BEFORE: ${formatLine(it)}") }
            add("TARGET: ${formatLine(target)}")
            next?.let { add("AFTER:  ${formatLine(it)}") }
        }

        return SpikeForensicReport(
            targetIndex = safeIdx,
            targetSample = target,
            prevSample = prev,
            nextSample = next,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            classification = classification,
            severity = severity,
            summaryHeadline = headline,
            chargeLostBeforeMah = chargeBefore,
            chargeLostAfterMah = chargeAfter,
            counterMeasured = counterMeasured,
            cpuAwakeBeforeMs = clocksBefore?.awakeMs,
            deepSleepBeforeMs = clocksBefore?.sleepMs,
            awakePctBefore = clocksBefore?.awakePct,
            cpuAwakeAfterMs = clocksAfter?.awakeMs,
            deepSleepAfterMs = clocksAfter?.sleepMs,
            awakePctAfter = clocksAfter?.awakePct,
            voltageDeltaMv = voltageDeltaMv,
            tempDeltaC = tempDeltaC,
            stateChanges = stateChanges,
            liveHardwareBreakdown = liveHardware,
            correlatedEvents = usageEvents,
            correlatedAppUsage = mergedApps,
            energyMeterDeltas = meterDeltas,
            likelyDrivers = likelyDrivers,
            whatRemainsUnknown = unknowns,
            sourceReadings = sourceReadings
        )
    }

    private data class ClockDelta(val awakeMs: Long, val sleepMs: Long, val awakePct: Float)

    private fun computeBootClockDeltas(a: BatterySampleEntity?, b: BatterySampleEntity?): ClockDelta? {
        if (a == null || b == null) return null
        val dtWall = b.timestampMs - a.timestampMs
        if (dtWall <= 0L) return null
        if (a.elapsedRealtimeMs <= 0L || b.elapsedRealtimeMs <= a.elapsedRealtimeMs ||
            a.uptimeMs <= 0L || b.uptimeMs < a.uptimeMs
        ) {
            return null
        }
        val dtElapsed = b.elapsedRealtimeMs - a.elapsedRealtimeMs
        if (abs(dtElapsed - dtWall) > max(60_000L, dtWall / 2L)) return null
        val awake = (b.uptimeMs - a.uptimeMs).coerceIn(0L, dtElapsed)
        val sleep = (dtElapsed - awake).coerceAtLeast(0L)
        val pct = if (dtElapsed > 0L) (awake.toFloat() / dtElapsed.toFloat()) * 100f else 0f
        return ClockDelta(awake, sleep, pct)
    }

    private fun computePairChargeLoss(
        a: BatterySampleEntity?,
        b: BatterySampleEntity?,
        ratedCapacityMah: Int
    ): Pair<Float?, Boolean> {
        if (a == null || b == null || a.isCharging || b.isCharging) return null to false
        if (a.chargeCounterMicroAh > 0L && b.chargeCounterMicroAh > 0L) {
            return ((a.chargeCounterMicroAh - b.chargeCounterMicroAh) / 1000f).coerceIn(0f, 3000f) to true
        }
        val drop = (a.levelPct - b.levelPct).coerceAtLeast(0)
        return ((drop / 100f) * ratedCapacityMah) to false
    }

    private fun formatLine(s: BatterySampleEntity): String {
        val fg = (s.foregroundLabel ?: s.foregroundPackage)?.takeIf { it.isNotBlank() } ?: "none"
        return String.format(
            Locale.US,
            "%s [%s] %d%% | %d µAh | %+.2f W (%+.0f mA @ %d mV) | %.1f°C | scr=%s | %s | fg=%s",
            RootCauseAnalyzer.formatIsoUtc(s.timestampMs),
            s.source,
            s.levelPct,
            s.chargeCounterMicroAh,
            s.powerSignedWatts,
            s.currentSignedMa,
            s.voltageMv,
            s.temperatureCelsius,
            if (s.isScreenOn) "ON" else "OFF",
            s.networkTransport,
            fg
        )
    }
}
