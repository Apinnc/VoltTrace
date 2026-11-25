package com.example.domain

import com.example.data.local.AppDrainEventEntity
import com.example.data.local.BatterySampleEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class OvernightReportMode {
    OVERNIGHT_STANDBY,
    BEFORE_PLUGGED_IN,
    RECENT_SESSION,
    AWAITING_DATA
}

enum class ChargeEvidenceSource {
    COUNTER_MEASURED,
    MIXED,
    ESTIMATED
}

data class WatchInvestigationConfig(
    val active: Boolean = false,
    val title: String = "",
    val suspectLabel: String = "",
    val suspectPackage: String? = null,
    val triggerDrainPctPerHour: Float = 1.8f,
    val triggerPeakMw: Float = 2_200f,
    val armedAtMs: Long = 0L,
    val matchCount: Int = 0,
    val lastMatchedAtMs: Long = 0L,
    val lastMatchedEventId: String = "",
    val lastMatchSummary: String = ""
)

data class OvernightBaseline(
    val nightsCount: Int,
    val typicalLossPct: Float?,
    val typicalDrainPctPerHour: Float?,
    val typicalMahLost: Float?,
    val typicalDeepSleepPct: Float?,
    val deltaDrainPctPerHour: Float?,
    val isElevatedVsBaseline: Boolean,
    val statusLabel: String
)

data class OvernightKeySegment(
    val id: String,
    val startIndexInWindow: Int,
    val endIndexInWindow: Int,
    val peakIndexInWindow: Int,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val chargeLostMah: Float,
    val counterMeasuredMah: Float = 0f,
    val estimatedMah: Float = 0f,
    val chargeEvidenceSource: ChargeEvidenceSource = ChargeEvidenceSource.ESTIMATED,
    val counterMeasured: Boolean,
    val levelDropPct: Int,
    val shareOfNightLossPct: Float,
    val peakPowerMw: Float,
    val peakPowerTimestampMs: Long,
    val awakePct: Float?,
    val deepSleepMs: Long?,
    val cpuAwakeMs: Long?,
    val suspectLabel: String?,
    val suspectPackage: String?,
    val headline: String,
    val whatPhoneRecorded: List<String>,
    val whatRemainsUnknown: List<String>,
    val sourceReadings: List<String>,
    val severity: CauseSeverity
)

data class OvernightReplayReport(
    val mode: OvernightReportMode,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val durationMs: Long,
    val pluggedInAtMs: Long?,
    val samples: List<BatterySampleEntity>,
    val startLevelPct: Int,
    val endLevelPct: Int,
    val levelLostPct: Int,
    val chargeLostMah: Float,
    val counterMeasuredMah: Float = 0f,
    val estimatedMah: Float = 0f,
    val chargeEvidenceSource: ChargeEvidenceSource = ChargeEvidenceSource.ESTIMATED,
    val counterMeasured: Boolean,
    val drainRatePctPerHour: Float,
    val deepSleepMs: Long?,
    val cpuAwakeMs: Long?,
    val deepSleepPct: Float?,
    val peakDischargeMw: Float,
    val peakDischargeTimestampMs: Long?,
    val baseline: OvernightBaseline,
    val keySegment: OvernightKeySegment?,
    val segments: List<OvernightKeySegment>,
    val whatPhoneRecordedSummary: List<String>,
    val whatRemainsUnknown: List<String>,
    val suggestedWatchTitle: String,
    val suggestedWatchSuspect: String,
    val suggestedWatchPackage: String?,
    val suggestedTriggerRatePctHr: Float,
    val suggestedTriggerPeakMw: Float,
    val watchTriggeredThisWindow: Boolean,
    val watchMatchReason: String?,
    val watchMatchEventId: String? = null,
    val watchMatchEventTimestampMs: Long? = null
)

/**
 * Pure Kotlin Overnight Replay & Personal Baseline analyzer.
 *
 * Designed around the 10-second morning test:
 * 1. How much battery was lost (counter-first mAh + SOC % + personal baseline comparison)
 * 2. Which part of the night mattered most (highest-impact burst segment)
 * 3. What the phone actually recorded (strictly separating observed hardware readings from unknowns)
 * 4. Automatic "Before you plugged in" fallback when the phone charged overnight
 * 5. Persistent "Watch for this again" recurrence matching
 */
object OvernightReplayEngine {

    private const val MIN_OVERNIGHT_MS = 30L * 60_000L
    private const val MIN_FALLBACK_MS = 20L * 60_000L
    private const val MAX_INTERVAL_GAP_MS = 4L * 60L * 60_000L

    private data class LocalNightWindow(
        val nightKey: String,
        val startMs: Long,
        val endMs: Long
    )

    private data class UnpluggedRun(
        val samples: List<BatterySampleEntity>,
        val startMs: Long,
        val endMs: Long,
        val durationMs: Long,
        val overnightOverlapMs: Long,
        val screenOffDurationMs: Long,
        val chargeLostMah: Float,
        val counterMeasuredMah: Float,
        val estimatedMah: Float,
        val chargeEvidenceSource: ChargeEvidenceSource,
        val counterMeasured: Boolean,
        val startLevelPct: Int,
        val endLevelPct: Int,
        val levelLostPct: Int,
        val drainRatePctPerHour: Float,
        val deepSleepMs: Long?,
        val cpuAwakeMs: Long?,
        val deepSleepPct: Float?,
        val pluggedInAtMs: Long?,
        val nightKey: String? = null
    )

    private data class WatchMatchEvaluation(
        val triggered: Boolean,
        val reason: String?,
        val eventId: String?,
        val eventTimestampMs: Long?
    )

    fun analyze(
        allSamples: List<BatterySampleEntity>,
        appEvents: List<AppDrainEventEntity> = emptyList(),
        watchConfig: WatchInvestigationConfig = WatchInvestigationConfig(),
        ratedCapacityMah: Int = 4500,
        nowMs: Long = allSamples.maxOfOrNull { it.timestampMs } ?: System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): OvernightReplayReport {
        val sorted = allSamples.sortedBy { it.timestampMs }
        if (sorted.size < 2) {
            return emptyReport(nowMs)
        }

        val rawRuns = extractUnpluggedRuns(sorted, ratedCapacityMah, timeZone)
        if (rawRuns.isEmpty()) {
            return emptyReport(nowMs)
        }

        // Clip every unplugged stretch to distinct local night windows [21:00..10:00] so daytime
        // drain is never attributed to overnight loss and multi-night spans split cleanly per night.
        val clippedNightRuns = extractClippedLocalNightRuns(sorted, ratedCapacityMah, timeZone)

        val recentCutoffMs = nowMs - 24L * 60L * 60_000L

        // 1. Primary: genuine clipped local-night standby run (>= 2h inside the local night,
        //    >= 45% screen-off, and NOT superseded by >= 2h overnight charging during that same night).
        val qualifyingRecentNights = clippedNightRuns.filter { run ->
            if (run.endMs < recentCutoffMs) return@filter false
            val nightWin = run.nightKey?.let { localNightWindowForKey(it, timeZone) }
            val nightChargingMs = if (nightWin != null) {
                computeChargingMsInRange(sorted, nightWin.startMs, nightWin.endMs)
            } else {
                0L
            }
            val followedByOvernightCharge = run.pluggedInAtMs != null && nightChargingMs >= 2L * 60L * 60_000L
            !followedByOvernightCharge &&
                run.durationMs >= 2L * 60L * 60_000L &&
                run.overnightOverlapMs >= 2L * 60L * 60_000L &&
                run.screenOffDurationMs >= (run.durationMs * 0.45f).toLong()
        }
        // Prefer the most recent local night (not an earlier night with slightly longer duration)
        val overnightCandidate = qualifyingRecentNights
            .groupBy { it.nightKey }
            .maxByOrNull { (_, runsInNight) -> runsInNight.maxOf { it.endMs } }
            ?.value
            ?.maxByOrNull { it.durationMs }

        // 2. Fallback: require an observed plug-in/charging transition for "Before you plugged in";
        //    otherwise show a neutral "Recent Session" report without claiming the phone charged overnight.
        val recentChargingMs = computeOvernightChargingMs(
            sorted = sorted,
            sinceMs = nowMs - 36L * 60L * 60_000L,
            timeZone = timeZone
        )
        val (selectedRun, mode) = if (overnightCandidate != null) {
            overnightCandidate to OvernightReportMode.OVERNIGHT_STANDBY
        } else {
            val fallbackCandidate = rawRuns
                .filter { run ->
                    run.endMs >= (nowMs - 48L * 60L * 60_000L) &&
                        (run.durationMs >= MIN_FALLBACK_MS || run.chargeLostMah >= 15f)
                }
                .maxByOrNull { it.endMs }
            if (fallbackCandidate != null) {
                val observedPlugIn = fallbackCandidate.pluggedInAtMs != null ||
                    (recentChargingMs > 0L && sorted.any { it.isCharging && it.timestampMs >= fallbackCandidate.endMs })
                val fallbackMode = if (observedPlugIn) {
                    OvernightReportMode.BEFORE_PLUGGED_IN
                } else {
                    OvernightReportMode.RECENT_SESSION
                }
                fallbackCandidate to fallbackMode
            } else {
                return emptyReport(nowMs)
            }
        }

        // 3. Personal Baseline strictly from prior DISTINCT local nights (never daytime sessions)
        val currentNightKey = selectedRun.nightKey
        val priorNights = clippedNightRuns
            .filter { run ->
                val nk = run.nightKey ?: return@filter false
                val isStrictlyEarlierNight = if (currentNightKey != null) {
                    nk < currentNightKey
                } else {
                    run.endMs < selectedRun.startMs - 60L * 60_000L
                }
                isStrictlyEarlierNight &&
                    run.durationMs >= MIN_OVERNIGHT_MS &&
                    run.overnightOverlapMs >= MIN_OVERNIGHT_MS &&
                    run.screenOffDurationMs >= (run.durationMs * 0.45f).toLong()
            }
            .groupBy { it.nightKey!! }
            .toSortedMap()
            .values
            .map { runsInNight -> runsInNight.maxByOrNull { it.durationMs }!! }
            .takeLast(14)

        val baseline = computeBaseline(selectedRun, priorNights)

        // 4. Identify segments inside the selected run to pinpoint "which part of the night mattered"
        val segments = buildSegments(
            run = selectedRun,
            appEvents = appEvents,
            ratedCapacityMah = ratedCapacityMah,
            timeZone = timeZone
        )
        val keySegment = segments.maxByOrNull { seg ->
            seg.chargeLostMah + (if (seg.severity == CauseSeverity.CRITICAL) 40f else 0f)
        }

        // 5. Peak discharge inside the selected run
        var peakMw = 0f
        var peakTs: Long? = null
        selectedRun.samples.forEach { s ->
            if (!s.isCharging && s.powerMw > peakMw) {
                peakMw = s.powerMw
                peakTs = s.timestampMs
            }
        }

        // 6. Plain-English "What the phone actually recorded" summary (scannable in < 5 seconds)
        val recordedSummary = buildList {
            when (selectedRun.chargeEvidenceSource) {
                ChargeEvidenceSource.COUNTER_MEASURED -> add(
                    String.format(
                        Locale.US,
                        "Measured -%.0f mAh (%d%% → %d%%) via battery charge counter across %d samples.",
                        selectedRun.chargeLostMah,
                        selectedRun.startLevelPct,
                        selectedRun.endLevelPct,
                        selectedRun.samples.size
                    )
                )
                ChargeEvidenceSource.MIXED -> add(
                    String.format(
                        Locale.US,
                        "Recorded -%.0f mAh (%d%% → %d%%) via mixed evidence (%.0f mAh charge counter + %.0f mAh SOC estimate) across %d samples.",
                        selectedRun.chargeLostMah,
                        selectedRun.startLevelPct,
                        selectedRun.endLevelPct,
                        selectedRun.counterMeasuredMah,
                        selectedRun.estimatedMah,
                        selectedRun.samples.size
                    )
                )
                ChargeEvidenceSource.ESTIMATED -> add(
                    String.format(
                        Locale.US,
                        "Estimated -%.0f mAh (%d%% → %d%%) via SOC percentage estimate across %d samples.",
                        selectedRun.chargeLostMah,
                        selectedRun.startLevelPct,
                        selectedRun.endLevelPct,
                        selectedRun.samples.size
                    )
                )
            }
            if (selectedRun.deepSleepMs != null && selectedRun.cpuAwakeMs != null && selectedRun.deepSleepPct != null) {
                add(
                    String.format(
                        Locale.US,
                        "Kernel clocks: %s deep sleep (%.0f%%) · %s CPU awake.",
                        RootCauseAnalyzer.formatDuration(selectedRun.deepSleepMs),
                        selectedRun.deepSleepPct,
                        RootCauseAnalyzer.formatDuration(selectedRun.cpuAwakeMs)
                    )
                )
            } else {
                add("Boot clocks were unavailable or crossed a reboot for part of this window.")
            }
            val peakTimestamp = peakTs
            if (peakMw >= 300f && peakTimestamp != null) {
                add(
                    String.format(
                        Locale.US,
                        "Peak draw hit -%.2f W at %s.",
                        peakMw / 1000f,
                        formatClock(peakTimestamp, timeZone)
                    )
                )
            }
            val observedApp = keySegment?.suspectLabel
            if (!observedApp.isNullOrBlank()) {
                add("Observed activity during peak window: $observedApp.")
            } else {
                val cellCount = selectedRun.samples.count { it.cellularActive }
                val wifiCount = selectedRun.samples.count { it.wifiConnected }
                val radioNote = when {
                    cellCount > wifiCount -> "Cellular modem active (${cellCount}/${selectedRun.samples.size} samples)"
                    wifiCount > 0 -> "Wi-Fi connected (${wifiCount}/${selectedRun.samples.size} samples)"
                    else -> "Radios idle/offline"
                }
                add("Radio state recorded: $radioNote.")
            }
        }

        val unknowns = buildList {
            if (keySegment?.suspectLabel == null) {
                add("No foreground app or foreground-service name was emitted during the key window — wakeups may belong to an OS job or background sync.")
            }
            add("Ordinary Android permissions cannot inspect kernel partial-wakelock holder names (requires ADB dumpsys batterystats).")
            if (selectedRun.samples.any { it.gpsEnabled }) {
                add("Location toggle was ON, but Android does not expose actual third-party GPS fix duration.")
            }
        }

        // 7. Suggested "Watch for this again" rule tailored to this night
        val suggestedSuspect = keySegment?.suspectLabel
            ?: if (selectedRun.samples.count { it.cellularActive } > selectedRun.samples.size / 3) {
                "Cellular Standby Drain"
            } else {
                "Screen-Off Standby Wakeups"
            }
        val suggestedPkg = keySegment?.suspectPackage
        val suggestedTriggerRate = max(
            1.5f,
            baseline.typicalDrainPctPerHour?.let { it * 1.45f }
                ?: (selectedRun.drainRatePctPerHour * 0.85f).coerceIn(1.5f, 4.0f)
        )
        val suggestedTriggerPeakMw = max(1_800f, (peakMw * 0.75f).coerceIn(1_800f, 5_000f))
        val keyTimeRange = keySegment?.let {
            "${formatClock(it.startMs, timeZone)}–${formatClock(it.endMs, timeZone)}"
        } ?: "Overnight"
        val suggestedTitle = "Watch: $suggestedSuspect ($keyTimeRange)"

        // 8. Evaluate if an already-armed "Watch for this again" investigation triggered on POST-ARM evidence
        val watchEval = evaluateWatchMatch(
            watchConfig = watchConfig,
            run = selectedRun,
            segments = segments,
            ratedCapacityMah = ratedCapacityMah
        )

        return OvernightReplayReport(
            mode = mode,
            windowStartMs = selectedRun.startMs,
            windowEndMs = selectedRun.endMs,
            durationMs = selectedRun.durationMs,
            pluggedInAtMs = selectedRun.pluggedInAtMs,
            samples = selectedRun.samples,
            startLevelPct = selectedRun.startLevelPct,
            endLevelPct = selectedRun.endLevelPct,
            levelLostPct = selectedRun.levelLostPct,
            chargeLostMah = selectedRun.chargeLostMah,
            counterMeasuredMah = selectedRun.counterMeasuredMah,
            estimatedMah = selectedRun.estimatedMah,
            chargeEvidenceSource = selectedRun.chargeEvidenceSource,
            counterMeasured = selectedRun.counterMeasured,
            drainRatePctPerHour = selectedRun.drainRatePctPerHour,
            deepSleepMs = selectedRun.deepSleepMs,
            cpuAwakeMs = selectedRun.cpuAwakeMs,
            deepSleepPct = selectedRun.deepSleepPct,
            peakDischargeMw = peakMw,
            peakDischargeTimestampMs = peakTs,
            baseline = baseline,
            keySegment = keySegment,
            segments = segments,
            whatPhoneRecordedSummary = recordedSummary,
            whatRemainsUnknown = unknowns,
            suggestedWatchTitle = suggestedTitle,
            suggestedWatchSuspect = suggestedSuspect,
            suggestedWatchPackage = suggestedPkg,
            suggestedTriggerRatePctHr = suggestedTriggerRate,
            suggestedTriggerPeakMw = suggestedTriggerPeakMw,
            watchTriggeredThisWindow = watchEval.triggered,
            watchMatchReason = watchEval.reason,
            watchMatchEventId = watchEval.eventId,
            watchMatchEventTimestampMs = watchEval.eventTimestampMs
        )
    }

    private fun computeChargingMsInRange(
        sorted: List<BatterySampleEntity>,
        rangeStartMs: Long,
        rangeEndMs: Long
    ): Long {
        var total = 0L
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val dt = b.timestampMs - a.timestampMs
            if (dt <= 0L || dt > 12L * 60L * 60_000L) continue
            if (a.isCharging && b.isCharging) {
                val overlapStart = max(a.timestampMs, rangeStartMs)
                val overlapEnd = minOf(b.timestampMs, rangeEndMs)
                if (overlapEnd > overlapStart) {
                    total += (overlapEnd - overlapStart)
                }
            }
        }
        return total
    }

    private fun computeOvernightChargingMs(
        sorted: List<BatterySampleEntity>,
        sinceMs: Long,
        timeZone: TimeZone
    ): Long {
        var total = 0L
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (b.timestampMs < sinceMs) continue
            val dt = b.timestampMs - a.timestampMs
            if (dt <= 0L || dt > 12L * 60L * 60_000L) continue
            if (a.isCharging && b.isCharging) {
                total += computeOvernightOverlapMs(a.timestampMs, b.timestampMs, timeZone)
            }
        }
        return total
    }

    private fun extractUnpluggedRuns(
        sorted: List<BatterySampleEntity>,
        ratedCapacityMah: Int,
        timeZone: TimeZone
    ): List<UnpluggedRun> {
        val stretches = extractRawUnpluggedStretches(sorted)
        return stretches.mapNotNull { (slice, pluggedInAtMs) ->
            buildRun(slice, ratedCapacityMah, timeZone, pluggedInAtMs, nightKey = null)
        }
    }

    private fun extractClippedLocalNightRuns(
        sorted: List<BatterySampleEntity>,
        ratedCapacityMah: Int,
        timeZone: TimeZone
    ): List<UnpluggedRun> {
        val stretches = extractRawUnpluggedStretches(sorted)
        val results = mutableListOf<UnpluggedRun>()
        for ((slice, pluggedInAtMs) in stretches) {
            if (slice.size < 2) continue
            val nights = overlappingLocalNights(slice.first().timestampMs, slice.last().timestampMs, timeZone)
            for (night in nights) {
                val clipped = clipSamplesToWindow(slice, night.startMs, night.endMs)
                if (clipped.size < 2) continue
                val nightPlugIn = pluggedInAtMs?.takeIf { it in night.startMs..(night.endMs + 15L * 60_000L) }
                buildRun(clipped, ratedCapacityMah, timeZone, nightPlugIn, nightKey = night.nightKey)?.let {
                    results.add(it)
                }
            }
        }
        return results
    }

    private fun extractRawUnpluggedStretches(
        sorted: List<BatterySampleEntity>
    ): List<Pair<List<BatterySampleEntity>, Long?>> {
        val stretches = mutableListOf<Pair<List<BatterySampleEntity>, Long?>>()
        var current = mutableListOf<BatterySampleEntity>()

        fun flush(pluggedInAtMs: Long?) {
            if (current.size >= 2) {
                stretches.add(current.toList() to pluggedInAtMs)
            }
            current = mutableListOf()
        }

        for (i in sorted.indices) {
            val s = sorted[i]
            if (s.isCharging) {
                flush(pluggedInAtMs = s.timestampMs)
            } else {
                val prev = current.lastOrNull()
                if (prev != null && (s.timestampMs - prev.timestampMs) > MAX_INTERVAL_GAP_MS) {
                    flush(pluggedInAtMs = null)
                }
                current.add(s)
            }
        }
        flush(pluggedInAtMs = null)
        return stretches
    }

    private fun overlappingLocalNights(
        startMs: Long,
        endMs: Long,
        timeZone: TimeZone
    ): List<LocalNightWindow> {
        if (endMs <= startMs) return emptyList()
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = startMs
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { this.timeZone = timeZone }
        val windows = mutableListOf<LocalNightWindow>()
        while (cal.timeInMillis <= endMs) {
            val winStart = cal.timeInMillis
            val key = keyFmt.format(Date(winStart))
            val endCal = (cal.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 10)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val winEnd = endCal.timeInMillis
            if (winEnd > startMs && winStart < endMs) {
                windows.add(LocalNightWindow(nightKey = key, startMs = winStart, endMs = winEnd))
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return windows
    }

    private fun localNightWindowForKey(nightKey: String, timeZone: TimeZone): LocalNightWindow? {
        val parts = nightKey.split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        val startCal = Calendar.getInstance(timeZone).apply {
            set(Calendar.YEAR, y)
            set(Calendar.MONTH, m - 1)
            set(Calendar.DAY_OF_MONTH, d)
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = (startCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return LocalNightWindow(nightKey = nightKey, startMs = startCal.timeInMillis, endMs = endCal.timeInMillis)
    }

    private fun clipSamplesToWindow(
        slice: List<BatterySampleEntity>,
        windowStartMs: Long,
        windowEndMs: Long
    ): List<BatterySampleEntity> {
        val inside = slice.filter { it.timestampMs in windowStartMs..windowEndMs }.toMutableList()

        // If an interval crosses windowStartMs without an exact sample at windowStartMs, interpolate the boundary
        val hasExactStart = inside.firstOrNull()?.timestampMs == windowStartMs
        if (!hasExactStart) {
            val beforeStart = slice.lastOrNull { it.timestampMs < windowStartMs }
            val afterStart = slice.firstOrNull { it.timestampMs > windowStartMs }
            if (beforeStart != null && afterStart != null &&
                (afterStart.timestampMs - beforeStart.timestampMs) <= MAX_INTERVAL_GAP_MS
            ) {
                inside.add(0, interpolateBoundarySample(beforeStart, afterStart, windowStartMs))
            }
        }

        // If an interval crosses windowEndMs without an exact sample at windowEndMs, interpolate the boundary
        val hasExactEnd = inside.lastOrNull()?.timestampMs == windowEndMs
        if (!hasExactEnd) {
            val beforeEnd = slice.lastOrNull { it.timestampMs < windowEndMs }
            val afterEnd = slice.firstOrNull { it.timestampMs > windowEndMs }
            if (beforeEnd != null && afterEnd != null &&
                (afterEnd.timestampMs - beforeEnd.timestampMs) <= MAX_INTERVAL_GAP_MS
            ) {
                inside.add(interpolateBoundarySample(beforeEnd, afterEnd, windowEndMs))
            }
        }

        return inside.distinctBy { it.timestampMs }.sortedBy { it.timestampMs }
    }

    private fun interpolateBoundarySample(
        a: BatterySampleEntity,
        b: BatterySampleEntity,
        targetTs: Long
    ): BatterySampleEntity {
        val span = (b.timestampMs - a.timestampMs).coerceAtLeast(1L)
        val frac = ((targetTs - a.timestampMs).toDouble() / span.toDouble()).coerceIn(0.0, 1.0)
        val level = (a.levelPct + (b.levelPct - a.levelPct) * frac).roundToInt()
        val voltage = (a.voltageMv + (b.voltageMv - a.voltageMv) * frac).roundToInt()
        val temp = (a.temperatureDeciC + (b.temperatureDeciC - a.temperatureDeciC) * frac).roundToInt()
        val current = (a.currentMicroA + (b.currentMicroA - a.currentMicroA) * frac).toLong()
        val counter = if (a.chargeCounterMicroAh > 0L && b.chargeCounterMicroAh > 0L) {
            (a.chargeCounterMicroAh + (b.chargeCounterMicroAh - a.chargeCounterMicroAh) * frac).toLong()
        } else {
            0L
        }
        val validClocks = a.elapsedRealtimeMs > 0L && b.elapsedRealtimeMs > a.elapsedRealtimeMs &&
            a.uptimeMs > 0L && b.uptimeMs >= a.uptimeMs
        val elapsed = if (validClocks) {
            (a.elapsedRealtimeMs + (b.elapsedRealtimeMs - a.elapsedRealtimeMs) * frac).toLong()
        } else {
            0L
        }
        val uptime = if (validClocks) {
            (a.uptimeMs + (b.uptimeMs - a.uptimeMs) * frac).toLong()
        } else {
            0L
        }
        val template = if (frac >= 0.5) b else a
        return template.copy(
            id = 0L,
            timestampMs = targetTs,
            levelPct = level,
            voltageMv = voltage,
            temperatureDeciC = temp,
            currentMicroA = current,
            chargeCounterMicroAh = counter,
            elapsedRealtimeMs = elapsed,
            uptimeMs = uptime
        )
    }

    private fun buildRun(
        samples: List<BatterySampleEntity>,
        ratedCapacityMah: Int,
        timeZone: TimeZone,
        pluggedInAtMs: Long?,
        nightKey: String?
    ): UnpluggedRun? {
        if (samples.size < 2) return null
        val startMs = samples.first().timestampMs
        val endMs = samples.last().timestampMs
        val durationMs = endMs - startMs
        if (durationMs <= 0L) return null

        var counterMeasuredMah = 0f
        var estimatedMah = 0f
        var counterIntervalCount = 0
        var estimatedIntervalCount = 0
        var screenOffMs = 0L
        var overnightOverlapMs = 0L
        var validElapsedMs = 0L
        var validAwakeMs = 0L

        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]
            val dt = b.timestampMs - a.timestampMs
            if (dt <= 0L || dt > MAX_INTERVAL_GAP_MS) continue

            val (pairMah, isCounter) = pairChargeLoss(a, b, ratedCapacityMah)
            if (isCounter) {
                counterMeasuredMah += pairMah
                counterIntervalCount++
            } else {
                estimatedMah += pairMah
                estimatedIntervalCount++
            }

            if (!a.isScreenOn && !b.isScreenOn) {
                screenOffMs += dt
            }
            overnightOverlapMs += computeOvernightOverlapMs(a.timestampMs, b.timestampMs, timeZone)

            if (a.elapsedRealtimeMs > 0L && b.elapsedRealtimeMs > a.elapsedRealtimeMs &&
                a.uptimeMs > 0L && b.uptimeMs >= a.uptimeMs
            ) {
                val dtElapsed = b.elapsedRealtimeMs - a.elapsedRealtimeMs
                if (abs(dtElapsed - dt) <= max(60_000L, dt / 2L)) {
                    val awake = (b.uptimeMs - a.uptimeMs).coerceIn(0L, dtElapsed)
                    validElapsedMs += dtElapsed
                    validAwakeMs += awake
                }
            }
        }

        val totalMah = counterMeasuredMah + estimatedMah
        val evidenceSource = resolveChargeEvidenceSource(
            counterMeasuredMah = counterMeasuredMah,
            estimatedMah = estimatedMah,
            counterIntervals = counterIntervalCount,
            estimatedIntervals = estimatedIntervalCount
        )
        val counterUsedOnly = evidenceSource == ChargeEvidenceSource.COUNTER_MEASURED

        val startPct = samples.first().levelPct
        val endPct = samples.last().levelPct
        val levelLost = (startPct - endPct).coerceAtLeast(0)
        val hours = durationMs / 3_600_000f
        val effectivePctDrop = if (evidenceSource != ChargeEvidenceSource.ESTIMATED && ratedCapacityMah > 0) {
            (totalMah / ratedCapacityMah.toFloat()) * 100f
        } else {
            levelLost.toFloat()
        }
        val ratePctHr = if (hours > 0.05f) effectivePctDrop / hours else 0f

        val deepSleepMs = if (validElapsedMs > 0L) (validElapsedMs - validAwakeMs).coerceAtLeast(0L) else null
        val cpuAwakeMs = if (validElapsedMs > 0L) validAwakeMs else null
        val deepSleepPct = if (validElapsedMs > 0L && deepSleepMs != null) {
            (deepSleepMs.toFloat() / validElapsedMs.toFloat()) * 100f
        } else {
            null
        }

        return UnpluggedRun(
            samples = samples,
            startMs = startMs,
            endMs = endMs,
            durationMs = durationMs,
            overnightOverlapMs = overnightOverlapMs,
            screenOffDurationMs = screenOffMs,
            chargeLostMah = totalMah,
            counterMeasuredMah = counterMeasuredMah,
            estimatedMah = estimatedMah,
            chargeEvidenceSource = evidenceSource,
            counterMeasured = counterUsedOnly,
            startLevelPct = startPct,
            endLevelPct = endPct,
            levelLostPct = levelLost,
            drainRatePctPerHour = ratePctHr,
            deepSleepMs = deepSleepMs,
            cpuAwakeMs = cpuAwakeMs,
            deepSleepPct = deepSleepPct,
            pluggedInAtMs = pluggedInAtMs,
            nightKey = nightKey
        )
    }

    private fun resolveChargeEvidenceSource(
        counterMeasuredMah: Float,
        estimatedMah: Float,
        counterIntervals: Int,
        estimatedIntervals: Int
    ): ChargeEvidenceSource {
        return when {
            counterMeasuredMah > 0.01f && estimatedMah > 0.01f -> ChargeEvidenceSource.MIXED
            counterMeasuredMah > 0.01f -> ChargeEvidenceSource.COUNTER_MEASURED
            estimatedMah > 0.01f -> ChargeEvidenceSource.ESTIMATED
            counterIntervals > 0 && estimatedIntervals == 0 -> ChargeEvidenceSource.COUNTER_MEASURED
            counterIntervals > 0 && estimatedIntervals > 0 -> ChargeEvidenceSource.MIXED
            else -> ChargeEvidenceSource.ESTIMATED
        }
    }

    private fun computeBaseline(
        current: UnpluggedRun,
        priorNights: List<UnpluggedRun>
    ): OvernightBaseline {
        if (priorNights.isEmpty()) {
            return OvernightBaseline(
                nightsCount = 0,
                typicalLossPct = null,
                typicalDrainPctPerHour = null,
                typicalMahLost = null,
                typicalDeepSleepPct = null,
                deltaDrainPctPerHour = null,
                isElevatedVsBaseline = false,
                statusLabel = "Building your personal baseline (night 1)"
            )
        }

        val typicalRate = median(priorNights.map { it.drainRatePctPerHour })
        val typicalMah = median(priorNights.map { it.chargeLostMah })
        val typicalLoss = median(priorNights.map { it.levelLostPct.toFloat() })
        val sleepValues = priorNights.mapNotNull { it.deepSleepPct }
        val typicalSleep = if (sleepValues.isNotEmpty()) median(sleepValues) else null

        val deltaRate = current.drainRatePctPerHour - typicalRate
        val elevated = deltaRate >= 0.45f &&
            (typicalRate <= 0.1f || current.drainRatePctPerHour >= typicalRate * 1.35f)
        val improved = deltaRate <= -0.35f

        val label = when {
            priorNights.size < 2 && elevated -> String.format(
                Locale.US,
                "Calibrating (%d prior night) · +%.1f%%/hr higher",
                priorNights.size,
                deltaRate
            )
            priorNights.size < 2 -> "Calibrating baseline (${priorNights.size} prior night)"
            elevated -> String.format(
                Locale.US,
                "+%.1f%%/hr above your %.1f%%/hr usual",
                deltaRate,
                typicalRate
            )
            improved -> String.format(
                Locale.US,
                "%.1f%%/hr better than your %.1f%%/hr usual",
                abs(deltaRate),
                typicalRate
            )
            else -> String.format(
                Locale.US,
                "Typical for your phone (usual %.1f%%/hr)",
                typicalRate
            )
        }

        return OvernightBaseline(
            nightsCount = priorNights.size,
            typicalLossPct = typicalLoss,
            typicalDrainPctPerHour = typicalRate,
            typicalMahLost = typicalMah,
            typicalDeepSleepPct = typicalSleep,
            deltaDrainPctPerHour = deltaRate,
            isElevatedVsBaseline = elevated,
            statusLabel = label
        )
    }

    private fun buildSegments(
        run: UnpluggedRun,
        appEvents: List<AppDrainEventEntity>,
        ratedCapacityMah: Int,
        timeZone: TimeZone
    ): List<OvernightKeySegment> {
        val samples = run.samples
        if (samples.size < 2) return emptyList()

        // Step 1: Score each adjacent interval and merge adjacent elevated intervals into coherent episodes
        data class RawInterval(
            val startIdx: Int,
            val endIdx: Int,
            val dtMs: Long,
            val mah: Float,
            val counter: Boolean,
            val rateMahHr: Float,
            val peakMw: Float,
            val peakIdx: Int,
            val awakeMs: Long?,
            val sleepMs: Long?,
            val isElevated: Boolean
        )

        val avgMahHr = if (run.durationMs > 0L) {
            run.chargeLostMah / (run.durationMs / 3_600_000f)
        } else {
            0f
        }

        val intervals = mutableListOf<RawInterval>()
        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]
            val dt = b.timestampMs - a.timestampMs
            if (dt <= 0L) continue
            val (mah, isCounter) = pairChargeLoss(a, b, ratedCapacityMah)
            val rateMahHr = if (dt > 0L) mah / (dt / 3_600_000f) else 0f
            val peakIdx = if (b.powerMw >= a.powerMw) i + 1 else i
            val peakMw = max(a.powerMw, b.powerMw)

            var awakeMs: Long? = null
            var sleepMs: Long? = null
            if (a.elapsedRealtimeMs > 0L && b.elapsedRealtimeMs > a.elapsedRealtimeMs &&
                a.uptimeMs > 0L && b.uptimeMs >= a.uptimeMs
            ) {
                val dtElapsed = b.elapsedRealtimeMs - a.elapsedRealtimeMs
                if (abs(dtElapsed - dt) <= max(60_000L, dt / 2L)) {
                    val aw = (b.uptimeMs - a.uptimeMs).coerceIn(0L, dtElapsed)
                    awakeMs = aw
                    sleepMs = (dtElapsed - aw).coerceAtLeast(0L)
                }
            }

            val awakeRatio = if (awakeMs != null && sleepMs != null && (awakeMs + sleepMs) > 0L) {
                awakeMs.toFloat() / (awakeMs + sleepMs).toFloat()
            } else {
                0f
            }

            val elevated = peakMw >= 1_200f ||
                rateMahHr >= max(110f, avgMahHr * 1.65f) ||
                (!a.isScreenOn && !b.isScreenOn && awakeRatio >= 0.55f && mah >= 15f)

            intervals.add(
                RawInterval(
                    startIdx = i,
                    endIdx = i + 1,
                    dtMs = dt,
                    mah = mah,
                    counter = isCounter,
                    rateMahHr = rateMahHr,
                    peakMw = peakMw,
                    peakIdx = peakIdx,
                    awakeMs = awakeMs,
                    sleepMs = sleepMs,
                    isElevated = elevated
                )
            )
        }
        if (intervals.isEmpty()) return emptyList()

        // Merge consecutive intervals with matching elevated status into clean replay chapters
        val groups = mutableListOf<MutableList<RawInterval>>()
        intervals.forEach { iv ->
            val lastGroup = groups.lastOrNull()
            if (lastGroup != null && lastGroup.last().isElevated == iv.isElevated &&
                (samples[iv.endIdx].timestampMs - samples[lastGroup.first().startIdx].timestampMs) <= 2L * 60L * 60_000L
            ) {
                lastGroup.add(iv)
            } else {
                groups.add(mutableListOf(iv))
            }
        }

        val totalLossSafe = run.chargeLostMah.coerceAtLeast(1f)
        return groups.mapIndexed { idx, grp ->
            val sIdx = grp.first().startIdx
            val eIdx = grp.last().endIdx
            val startSample = samples[sIdx]
            val endSample = samples[eIdx]
            val segCounterMah = grp.filter { it.counter }.sumOf { it.mah.toDouble() }.toFloat()
            val segEstimatedMah = grp.filterNot { it.counter }.sumOf { it.mah.toDouble() }.toFloat()
            val segMah = segCounterMah + segEstimatedMah
            val segEvidenceSource = resolveChargeEvidenceSource(
                counterMeasuredMah = segCounterMah,
                estimatedMah = segEstimatedMah,
                counterIntervals = grp.count { it.counter },
                estimatedIntervals = grp.count { !it.counter }
            )
            val segCounter = segEvidenceSource == ChargeEvidenceSource.COUNTER_MEASURED
            val segDuration = (endSample.timestampMs - startSample.timestampMs).coerceAtLeast(1L)
            val bestPeak = grp.maxByOrNull { it.peakMw } ?: grp.first()
            val peakSample = samples[bestPeak.peakIdx]
            val sharePct = ((segMah / totalLossSafe) * 100f).coerceIn(0f, 100f)

            val hasClocks = grp.any { it.awakeMs != null && it.sleepMs != null }
            val sumAwake = if (hasClocks) grp.sumOf { it.awakeMs ?: 0L } else null
            val sumSleep = if (hasClocks) grp.sumOf { it.sleepMs ?: 0L } else null
            val awakePct = if (sumAwake != null && sumSleep != null && (sumAwake + sumSleep) > 0L) {
                (sumAwake.toFloat() / (sumAwake + sumSleep).toFloat()) * 100f
            } else {
                null
            }

            val segSlice = samples.subList(sIdx, eIdx + 1)
            val fgLabels = segSlice.mapNotNull { s ->
                (s.foregroundLabel ?: s.foregroundPackage)?.takeIf { it.isNotBlank() }
            }.distinct()
            val fgPkg = segSlice.mapNotNull { it.foregroundPackage?.takeIf { p -> p.isNotBlank() } }.firstOrNull()

            // Strictly intersect stored AppDrainEvent intervals [start, end] with [startSample, endSample]
            val matchingAppEvents = appEvents.mapNotNull { ev ->
                val evEnd = ev.timestampMs
                val evDur = ev.intervalDurationMs.coerceAtLeast(1L)
                val evStart = evEnd - evDur
                val overlapMs = minOf(evEnd, endSample.timestampMs) - max(evStart, startSample.timestampMs)
                if (overlapMs <= 0L) null else ev to overlapMs
            }.sortedByDescending { (ev, overlapMs) ->
                minOf(ev.foregroundMs, overlapMs)
            }.map { (ev, _) -> ev }

            val suspectLabel = fgLabels.firstOrNull() ?: matchingAppEvents.firstOrNull()?.appLabel
            val suspectPackage = fgPkg ?: matchingAppEvents.firstOrNull()?.packageName

            val anyScreenOn = segSlice.any { it.isScreenOn }
            val cellSamples = segSlice.count { it.cellularActive }
            val severity = when {
                !anyScreenOn && (bestPeak.peakMw >= 2_500f || (awakePct ?: 0f) >= 65f && segMah >= 50f) -> CauseSeverity.CRITICAL
                grp.first().isElevated || sharePct >= 45f -> CauseSeverity.ELEVATED
                else -> CauseSeverity.HEALTHY
            }

            val startClock = formatClock(startSample.timestampMs, timeZone)
            val endClock = formatClock(endSample.timestampMs, timeZone)
            val driverNote = when {
                suspectLabel != null -> "$suspectLabel active"
                cellSamples > 0 -> "Cellular radio active"
                anyScreenOn -> "Screen woke ON"
                (awakePct ?: 0f) >= 55f -> "CPU awake ${awakePct?.roundToInt()}%"
                else -> "Steady standby"
            }

            val headline = String.format(
                Locale.US,
                "%s – %s drove %.0f%% of loss (-%.0f mAh · %s)",
                startClock,
                endClock,
                sharePct,
                segMah,
                driverNote
            )

            val recorded = buildList {
                add(
                    String.format(
                        Locale.US,
                        "Charge: -%.0f mAh (%d%% → %d%%) across %s",
                        segMah,
                        startSample.levelPct,
                        endSample.levelPct,
                        RootCauseAnalyzer.formatDuration(segDuration)
                    )
                )
                if (awakePct != null && sumAwake != null && sumSleep != null) {
                    add(
                        String.format(
                            Locale.US,
                            "CPU awake %.0f%% (%s awake / %s deep sleep)",
                            awakePct,
                            RootCauseAnalyzer.formatDuration(sumAwake),
                            RootCauseAnalyzer.formatDuration(sumSleep)
                        )
                    )
                }
                add(
                    String.format(
                        Locale.US,
                        "Peak draw -%.2f W at %s (%.1f°C · %s)",
                        peakSample.powerWatts,
                        formatClock(peakSample.timestampMs, timeZone),
                        peakSample.temperatureCelsius,
                        if (peakSample.isScreenOn) "Screen ON" else "Screen OFF"
                    )
                )
                if (suspectLabel != null) {
                    add("Observed app/service activity: $suspectLabel")
                }
                if (cellSamples > 0) {
                    add("Cellular modem active during $cellSamples/${segSlice.size} readings")
                }
            }

            val segUnknowns = buildList {
                if (suspectLabel == null) {
                    add("No foreground app name was logged at sample boundaries in this window.")
                }
                if (!anyScreenOn) {
                    add("Kernel partial-wakelock holder names are inaccessible to standard Android apps.")
                }
            }

            val rawLines = buildList {
                add("START: ${formatRawLine(startSample, timeZone)}")
                if (bestPeak.peakIdx != sIdx && bestPeak.peakIdx != eIdx) {
                    add("PEAK:  ${formatRawLine(peakSample, timeZone)}")
                }
                add("END:   ${formatRawLine(endSample, timeZone)}")
            }

            OvernightKeySegment(
                id = "seg_${idx}_${startSample.timestampMs}",
                startIndexInWindow = sIdx,
                endIndexInWindow = eIdx,
                peakIndexInWindow = bestPeak.peakIdx,
                startMs = startSample.timestampMs,
                endMs = endSample.timestampMs,
                durationMs = segDuration,
                chargeLostMah = segMah,
                counterMeasuredMah = segCounterMah,
                estimatedMah = segEstimatedMah,
                chargeEvidenceSource = segEvidenceSource,
                counterMeasured = segCounter,
                levelDropPct = (startSample.levelPct - endSample.levelPct).coerceAtLeast(0),
                shareOfNightLossPct = sharePct,
                peakPowerMw = bestPeak.peakMw,
                peakPowerTimestampMs = peakSample.timestampMs,
                awakePct = awakePct,
                deepSleepMs = sumSleep,
                cpuAwakeMs = sumAwake,
                suspectLabel = suspectLabel,
                suspectPackage = suspectPackage,
                headline = headline,
                whatPhoneRecorded = recorded,
                whatRemainsUnknown = segUnknowns,
                sourceReadings = rawLines,
                severity = severity
            )
        }
    }

    private fun evaluateWatchMatch(
        watchConfig: WatchInvestigationConfig,
        run: UnpluggedRun,
        segments: List<OvernightKeySegment>,
        ratedCapacityMah: Int
    ): WatchMatchEvaluation {
        if (!watchConfig.active) {
            return WatchMatchEvaluation(false, null, null, null)
        }
        val armedAt = watchConfig.armedAtMs
        // Strictly evaluate readings recorded AFTER the watch was armed so old spikes never match.
        val postArmSamples = run.samples.filter { it.timestampMs > armedAt }
        if (postArmSamples.isEmpty()) {
            return WatchMatchEvaluation(false, null, null, null)
        }

        val reasons = mutableListOf<String>()
        var anchorEventId: String? = null
        var anchorEventTs: Long? = null

        // 1. Post-arm discharge rate check (strictly from post-arm intervals, >= 15 min span)
        val postArmRate = if (run.startMs >= armedAt) {
            run.drainRatePctPerHour
        } else if (postArmSamples.size >= 2) {
            val postSpanMs = postArmSamples.last().timestampMs - postArmSamples.first().timestampMs
            if (postSpanMs >= 15L * 60_000L) {
                var postMah = 0f
                var anyCounter = false
                for (i in 0 until postArmSamples.lastIndex) {
                    val (mah, isCounter) = pairChargeLoss(postArmSamples[i], postArmSamples[i + 1], ratedCapacityMah)
                    postMah += mah
                    if (isCounter) anyCounter = true
                }
                val pctDrop = if (anyCounter && ratedCapacityMah > 0) {
                    (postMah / ratedCapacityMah.toFloat()) * 100f
                } else {
                    (postArmSamples.first().levelPct - postArmSamples.last().levelPct).coerceAtLeast(0).toFloat()
                }
                pctDrop / (postSpanMs / 3_600_000f)
            } else {
                0f
            }
        } else {
            0f
        }

        if (postArmRate >= watchConfig.triggerDrainPctPerHour) {
            reasons.add(
                String.format(
                    Locale.US,
                    "Standby drain hit %.1f%%/hr (watch threshold ≥ %.1f%%/hr)",
                    postArmRate,
                    watchConfig.triggerDrainPctPerHour
                )
            )
            val rateStartMs = if (run.startMs >= armedAt) run.startMs else postArmSamples.first().timestampMs
            anchorEventId = "watch_rate_$rateStartMs"
            anchorEventTs = rateStartMs
        }

        // 2. Post-arm peak check with honest screen-state labeling
        val postArmDischarging = postArmSamples.filter { !it.isCharging }
        val postArmScreenOffPeak = postArmDischarging.filter { !it.isScreenOn }.maxByOrNull { it.powerMw }
        val postArmAnyPeak = postArmDischarging.maxByOrNull { it.powerMw }
        val triggeringPeak = when {
            postArmScreenOffPeak != null && postArmScreenOffPeak.powerMw >= watchConfig.triggerPeakMw -> postArmScreenOffPeak
            postArmAnyPeak != null && postArmAnyPeak.powerMw >= watchConfig.triggerPeakMw -> postArmAnyPeak
            else -> null
        }
        if (triggeringPeak != null) {
            val peakLabel = if (triggeringPeak.isScreenOn) "Screen-on peak" else "Screen-off peak"
            reasons.add(
                String.format(
                    Locale.US,
                    "%s hit -%.2f W (watch threshold ≥ %.2f W)",
                    peakLabel,
                    triggeringPeak.powerMw / 1000f,
                    watchConfig.triggerPeakMw / 1000f
                )
            )
            // Peak sample timestamp is stable across subsequent calm samples
            anchorEventId = "watch_peak_${triggeringPeak.timestampMs}"
            anchorEventTs = triggeringPeak.timestampMs
        }

        // 3. Post-arm suspect reappearance check
        val suspect = watchConfig.suspectLabel.trim()
        if (suspect.isNotEmpty()) {
            val matchingPostArmSample = postArmSamples.firstOrNull { s ->
                val lbl = (s.foregroundLabel ?: s.foregroundPackage).orEmpty()
                lbl.equals(suspect, ignoreCase = true) ||
                    (watchConfig.suspectPackage != null && s.foregroundPackage == watchConfig.suspectPackage)
            }
            val matchingPostArmSeg = segments.firstOrNull { seg ->
                seg.startMs >= armedAt && (
                    seg.suspectLabel.equals(suspect, ignoreCase = true) ||
                        (watchConfig.suspectPackage != null && seg.suspectPackage == watchConfig.suspectPackage)
                    )
            }
            if (matchingPostArmSample != null || matchingPostArmSeg != null) {
                reasons.add("$suspect reappeared after watch was armed")
                if (anchorEventId == null) {
                    val ts = matchingPostArmSample?.timestampMs ?: matchingPostArmSeg!!.startMs
                    anchorEventId = "watch_suspect_${suspect.lowercase(Locale.US)}_$ts"
                    anchorEventTs = ts
                }
            }
        }

        return if (reasons.isNotEmpty() && anchorEventId != null && anchorEventTs != null) {
            WatchMatchEvaluation(
                triggered = true,
                reason = reasons.joinToString(" · "),
                eventId = anchorEventId,
                eventTimestampMs = anchorEventTs
            )
        } else {
            WatchMatchEvaluation(false, null, null, null)
        }
    }

    private fun computeOvernightOverlapMs(startMs: Long, endMs: Long, timeZone: TimeZone): Long {
        if (endMs <= startMs) return 0L
        // Sample in 5-minute slices to handle midnight wrap cleanly across any timezone
        var overlap = 0L
        var cursor = startMs
        val step = 5L * 60_000L
        val cal = Calendar.getInstance(timeZone)
        while (cursor < endMs) {
            val next = (cursor + step).coerceAtMost(endMs)
            val mid = cursor + (next - cursor) / 2L
            cal.timeInMillis = mid
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            if (hour >= 21 || hour < 10) {
                overlap += (next - cursor)
            }
            cursor = next
        }
        return overlap
    }

    private fun pairChargeLoss(
        a: BatterySampleEntity,
        b: BatterySampleEntity,
        ratedCapacityMah: Int
    ): Pair<Float, Boolean> {
        if (a.chargeCounterMicroAh > 0L && b.chargeCounterMicroAh > 0L) {
            return ((a.chargeCounterMicroAh - b.chargeCounterMicroAh) / 1000f).coerceIn(0f, 3500f) to true
        }
        val pctDrop = (a.levelPct - b.levelPct).coerceAtLeast(0)
        return ((pctDrop / 100f) * ratedCapacityMah) to false
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2f
        }
    }

    fun formatClock(timestampMs: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { this.timeZone = timeZone }
        return fmt.format(Date(timestampMs))
    }

    private fun formatRawLine(s: BatterySampleEntity, timeZone: TimeZone): String {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US).apply { this.timeZone = timeZone }
        val fg = (s.foregroundLabel ?: s.foregroundPackage)?.takeIf { it.isNotBlank() } ?: "none"
        return String.format(
            Locale.US,
            "%s [%s] %d%% | %d µAh | %+.2f W | %.1f°C | scr=%s | %s | fg=%s",
            fmt.format(Date(s.timestampMs)),
            s.source,
            s.levelPct,
            s.chargeCounterMicroAh,
            s.powerSignedWatts,
            s.temperatureCelsius,
            if (s.isScreenOn) "ON" else "OFF",
            s.networkTransport,
            fg
        )
    }

    private fun emptyReport(nowMs: Long): OvernightReplayReport {
        return OvernightReplayReport(
            mode = OvernightReportMode.AWAITING_DATA,
            windowStartMs = nowMs,
            windowEndMs = nowMs,
            durationMs = 0L,
            pluggedInAtMs = null,
            samples = emptyList(),
            startLevelPct = 0,
            endLevelPct = 0,
            levelLostPct = 0,
            chargeLostMah = 0f,
            counterMeasured = false,
            drainRatePctPerHour = 0f,
            deepSleepMs = null,
            cpuAwakeMs = null,
            deepSleepPct = null,
            peakDischargeMw = 0f,
            peakDischargeTimestampMs = null,
            baseline = OvernightBaseline(
                nightsCount = 0,
                typicalLossPct = null,
                typicalDrainPctPerHour = null,
                typicalMahLost = null,
                typicalDeepSleepPct = null,
                deltaDrainPctPerHour = null,
                isElevatedVsBaseline = false,
                statusLabel = "Building your personal baseline"
            ),
            keySegment = null,
            segments = emptyList(),
            whatPhoneRecordedSummary = listOf(
                "Leave VoltTrace running during your next unplugged stretch or overnight to generate your first Morning Report."
            ),
            whatRemainsUnknown = emptyList(),
            suggestedWatchTitle = "Watch: Overnight Standby Wakeups",
            suggestedWatchSuspect = "Screen-Off Standby Wakeups",
            suggestedWatchPackage = null,
            suggestedTriggerRatePctHr = 1.8f,
            suggestedTriggerPeakMw = 2_200f,
            watchTriggeredThisWindow = false,
            watchMatchReason = null
        )
    }

    /**
     * Generates purely synthetic multi-night telemetry for Compose @Preview and tests.
     */
    fun createSyntheticPreviewReport(mode: OvernightReportMode = OvernightReportMode.OVERNIGHT_STANDBY): OvernightReplayReport {
        val tz = TimeZone.getTimeZone("UTC")
        val base = 1_739_487_600_000L // 2025-02-13T23:00:00Z
        val samples = mutableListOf<BatterySampleEntity>()
        var id = 1L

        fun mkSample(
            ts: Long,
            pct: Int,
            counterUah: Long,
            currentUa: Long,
            voltMv: Int,
            tempDeci: Int,
            elapsed: Long,
            uptime: Long,
            charging: Boolean = false,
            screenOn: Boolean = false,
            cell: Boolean = false,
            fg: String? = null
        ) = BatterySampleEntity(
            id = id++,
            timestampMs = ts,
            levelPct = pct,
            voltageMv = voltMv,
            temperatureDeciC = tempDeci,
            currentMicroA = currentUa,
            chargeCounterMicroAh = counterUah,
            isCharging = charging,
            pluggedType = if (charging) "AC" else "BATTERY",
            healthCode = 2,
            technology = "Li-ion",
            isScreenOn = screenOn,
            isPowerSaveMode = false,
            thermalStatus = 0,
            networkTransport = if (cell) "CELLULAR" else "WIFI",
            screenBrightnessPct = if (screenOn) 50 else 0,
            displayRefreshRateHz = 60f,
            displayAutoBrightness = true,
            gpsEnabled = false,
            wifiEnabled = true,
            wifiConnected = !cell,
            bluetoothEnabled = false,
            cellularActive = cell,
            airplaneModeOn = false,
            foregroundPackage = if (fg != null) "com.example.sync" else null,
            foregroundLabel = fg,
            source = "WORK",
            elapsedRealtimeMs = elapsed,
            uptimeMs = uptime
        )

        // Two calm prior baseline nights
        for (nightOffset in listOf(-2L, -1L)) {
            val nStart = base + nightOffset * 24L * 3_600_000L
            val eStart = 10_000_000L + (nightOffset + 2L) * 86_400_000L
            samples.add(mkSample(nStart, 90, 4_500_000L, -55_000L, 4220, 260, eStart, 3_000_000L))
            samples.add(mkSample(nStart + 4 * 3_600_000L, 88, 4_410_000L, -52_000L, 4205, 256, eStart + 4 * 3_600_000L, 4_200_000L))
            samples.add(mkSample(nStart + 7 * 3_600_000L, 87, 4_340_000L, -50_000L, 4190, 254, eStart + 7 * 3_600_000L, 5_100_000L))
        }

        if (mode == OvernightReportMode.BEFORE_PLUGGED_IN) {
            val preStart = base - 4 * 3_600_000L // 19:00 UTC
            val eStart = 180_000_000L
            samples.add(mkSample(preStart, 68, 3_400_000L, -180_000L, 4050, 290, eStart, 30_000_000L, screenOn = true))
            samples.add(mkSample(preStart + 90 * 60_000L, 62, 3_120_000L, -650_000L, 3980, 325, eStart + 90 * 60_000L, 34_000_000L, cell = true, fg = "Sample Cloud Sync"))
            samples.add(mkSample(preStart + 3 * 3_600_000L, 59, 2_980_000L, -120_000L, 3990, 285, eStart + 3 * 3_600_000L, 35_000_000L))
            samples.add(mkSample(base, 64, 3_200_000L, 2_200_000L, 4290, 315, eStart + 4 * 3_600_000L, 38_000_000L, charging = true))
            samples.add(mkSample(base + 7 * 3_600_000L, 100, 4_980_000L, 20_000L, 4390, 270, eStart + 11 * 3_600_000L, 40_000_000L, charging = true))
        } else {
            val eStart = 200_000_000L
            samples.add(mkSample(base, 90, 4_500_000L, -60_000L, 4225, 265, eStart, 50_000_000L))
            samples.add(mkSample(base + 135 * 60_000L, 89, 4_445_000L, -65_000L, 4210, 262, eStart + 135 * 60_000L, 50_700_000L))
            samples.add(mkSample(base + 150 * 60_000L, 87, 4_345_000L, -850_000L, 4085, 325, eStart + 150 * 60_000L, 51_550_000L, cell = true, fg = "Sample Cloud Sync"))
            samples.add(mkSample(base + 180 * 60_000L, 83, 4_160_000L, -920_000L, 4050, 342, eStart + 180 * 60_000L, 53_150_000L, cell = true, fg = "Sample Cloud Sync"))
            samples.add(mkSample(base + 450 * 60_000L, 81, 4_055_000L, -55_000L, 4140, 262, eStart + 450 * 60_000L, 54_200_000L))
        }

        return analyze(
            allSamples = samples,
            ratedCapacityMah = 5000,
            nowMs = samples.last().timestampMs + 15 * 60_000L,
            timeZone = tz
        )
    }
}
