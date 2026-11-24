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
    AWAITING_DATA
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
    val watchMatchReason: String?
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

    private data class UnpluggedRun(
        val samples: List<BatterySampleEntity>,
        val startMs: Long,
        val endMs: Long,
        val durationMs: Long,
        val overnightOverlapMs: Long,
        val screenOffDurationMs: Long,
        val chargeLostMah: Float,
        val counterMeasured: Boolean,
        val startLevelPct: Int,
        val endLevelPct: Int,
        val levelLostPct: Int,
        val drainRatePctPerHour: Float,
        val deepSleepMs: Long?,
        val cpuAwakeMs: Long?,
        val deepSleepPct: Float?,
        val pluggedInAtMs: Long?
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

        val runs = extractUnpluggedRuns(sorted, ratedCapacityMah, timeZone)
        if (runs.isEmpty()) {
            return emptyReport(nowMs)
        }

        // Look within the last 24 hours relative to `nowMs` for the most recent night or fallback
        val recentCutoffMs = nowMs - 24L * 60L * 60_000L
        val recentRuns = runs.filter { it.endMs >= recentCutoffMs }

        // Check if the phone spent >= 2 hours charging during the recent overnight window
        val recentOvernightChargingMs = computeOvernightChargingMs(
            sorted = sorted,
            sinceMs = recentCutoffMs,
            timeZone = timeZone
        )

        // 1. Primary: genuine unplugged overnight standby run (>= 2h overnight overlap, >= 50% screen-off,
        //    and NOT superseded by a multi-hour overnight charging session right after it)
        val overnightCandidate = recentRuns
            .filter { run ->
                val followedByOvernightCharge = run.pluggedInAtMs != null && recentOvernightChargingMs >= 2L * 60L * 60_000L
                !followedByOvernightCharge &&
                    run.durationMs >= MIN_OVERNIGHT_MS &&
                    run.overnightOverlapMs >= 2L * 60L * 60_000L &&
                    run.screenOffDurationMs >= (run.durationMs * 0.45f).toLong()
            }
            .maxByOrNull { it.overnightOverlapMs + it.durationMs / 2L }

        // 2. Fallback: "Before you plugged in" — phone charged overnight or recently; pick latest meaningful unplugged stretch before plug-in
        val (selectedRun, mode) = if (overnightCandidate != null) {
            overnightCandidate to OvernightReportMode.OVERNIGHT_STANDBY
        } else {
            val prePlugCandidate = runs
                .filter { run ->
                    run.endMs >= (nowMs - 48L * 60L * 60_000L) &&
                        (run.durationMs >= MIN_FALLBACK_MS || run.chargeLostMah >= 15f)
                }
                .maxByOrNull { it.endMs }
            if (prePlugCandidate != null) {
                prePlugCandidate to OvernightReportMode.BEFORE_PLUGGED_IN
            } else {
                return emptyReport(nowMs)
            }
        }

        // 3. Personal Baseline from prior distinct nights (excluding selectedRun)
        val priorNights = runs.filter { run ->
            run.endMs < selectedRun.startMs - 60L * 60_000L &&
                run.durationMs >= MIN_OVERNIGHT_MS &&
                (run.overnightOverlapMs >= MIN_OVERNIGHT_MS || run.screenOffDurationMs >= 45L * 60_000L)
        }.takeLast(14)

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
            val counterTag = if (selectedRun.counterMeasured) "battery charge counter" else "SOC percentage estimate"
            add(
                String.format(
                    Locale.US,
                    "Measured -%.0f mAh (%d%% → %d%%) via %s across %d samples.",
                    selectedRun.chargeLostMah,
                    selectedRun.startLevelPct,
                    selectedRun.endLevelPct,
                    counterTag,
                    selectedRun.samples.size
                )
            )
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

        // 8. Evaluate if an already-armed "Watch for this again" investigation triggered on THIS window
        val (watchTriggered, watchMatchReason) = evaluateWatchMatch(
            watchConfig = watchConfig,
            run = selectedRun,
            peakMw = peakMw,
            keySegment = keySegment
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
            watchTriggeredThisWindow = watchTriggered,
            watchMatchReason = watchMatchReason
        )
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
        val runs = mutableListOf<UnpluggedRun>()
        var current = mutableListOf<BatterySampleEntity>()

        fun flush(pluggedInAtMs: Long?) {
            if (current.size >= 2) {
                buildRun(current.toList(), ratedCapacityMah, timeZone, pluggedInAtMs)?.let { runs.add(it) }
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
        return runs
    }

    private fun buildRun(
        samples: List<BatterySampleEntity>,
        ratedCapacityMah: Int,
        timeZone: TimeZone,
        pluggedInAtMs: Long?
    ): UnpluggedRun? {
        if (samples.size < 2) return null
        val startMs = samples.first().timestampMs
        val endMs = samples.last().timestampMs
        val durationMs = endMs - startMs
        if (durationMs <= 0L) return null

        var totalMah = 0f
        var counterUsed = false
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
            totalMah += pairMah
            if (isCounter) counterUsed = true

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

        val startPct = samples.first().levelPct
        val endPct = samples.last().levelPct
        val levelLost = (startPct - endPct).coerceAtLeast(0)
        val hours = durationMs / 3_600_000f
        val effectivePctDrop = if (counterUsed && ratedCapacityMah > 0) {
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
            counterMeasured = counterUsed,
            startLevelPct = startPct,
            endLevelPct = endPct,
            levelLostPct = levelLost,
            drainRatePctPerHour = ratePctHr,
            deepSleepMs = deepSleepMs,
            cpuAwakeMs = cpuAwakeMs,
            deepSleepPct = deepSleepPct,
            pluggedInAtMs = pluggedInAtMs
        )
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
            val segMah = grp.sumOf { it.mah.toDouble() }.toFloat()
            val segCounter = grp.any { it.counter }
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

            val matchingAppEvents = appEvents.filter { ev ->
                ev.timestampMs in (startSample.timestampMs - 10L * 60_000L)..(endSample.timestampMs + 10L * 60_000L)
            }.sortedByDescending { it.foregroundMs }

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
        peakMw: Float,
        keySegment: OvernightKeySegment?
    ): Pair<Boolean, String?> {
        if (!watchConfig.active) return false to null
        // Only evaluate runs that ended AFTER the watch was armed
        if (run.endMs <= watchConfig.armedAtMs) return false to null

        val reasons = mutableListOf<String>()
        if (run.drainRatePctPerHour >= watchConfig.triggerDrainPctPerHour) {
            reasons.add(
                String.format(
                    Locale.US,
                    "Standby drain hit %.1f%%/hr (watch threshold ≥ %.1f%%/hr)",
                    run.drainRatePctPerHour,
                    watchConfig.triggerDrainPctPerHour
                )
            )
        }
        if (peakMw >= watchConfig.triggerPeakMw) {
            reasons.add(
                String.format(
                    Locale.US,
                    "Screen-off peak hit -%.2f W (watch threshold ≥ %.2f W)",
                    peakMw / 1000f,
                    watchConfig.triggerPeakMw / 1000f
                )
            )
        }
        val suspect = watchConfig.suspectLabel.trim()
        if (suspect.isNotEmpty() && keySegment?.suspectLabel != null &&
            keySegment.suspectLabel.equals(suspect, ignoreCase = true)
        ) {
            reasons.add("$suspect reappeared during the key drain window")
        }

        return if (reasons.isNotEmpty()) {
            true to reasons.joinToString(" · ")
        } else {
            false to null
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
