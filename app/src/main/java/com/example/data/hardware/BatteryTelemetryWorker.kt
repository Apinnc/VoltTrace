package com.example.data.hardware

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.AppDrainEventEntity
import com.example.data.local.BatterySampleEntity
import com.example.data.local.MonitorPreferences
import com.example.data.local.VoltTraceDatabase
import com.example.domain.AnalysisWindow
import com.example.domain.OvernightReplayEngine
import com.example.domain.RootCauseAnalyzer
import com.example.widget.VoltTraceWidgetUpdater
import kotlinx.coroutines.flow.first
import kotlin.math.abs

class BatteryTelemetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = VoltTraceDatabase.getInstance(applicationContext)
            val dao = db.batteryDao()
            val reader = BatteryHardwareReader(applicationContext)
            val prefs = MonitorPreferences(applicationContext)

            val settings = prefs.settingsFlow.first()
            val previous = dao.getLatestSample()
            val current = reader.readCurrentSample(source = "WORK")
            dao.insertSample(current)

            // Capture Android 15+ PowerMonitor hardware energy snapshots if exposed by device HAL
            val powerDiscovery = PowerMonitorDiscovery(applicationContext)
            val meterSnapshots = powerDiscovery.captureSnapshots(current.timestampMs)
            if (meterSnapshots.isNotEmpty()) {
                dao.insertEnergyMeterSnapshots(meterSnapshots)
            }

            val since24h = current.timestampMs - 24L * 60L * 60L * 1000L
            val recentSamples = dao.observeSamplesSince(since24h).first()

            // Correlate per-app foreground sessions using a dedicated persistent cursor so
            // 1-minute precision samples or live snapshots never truncate the 15-minute usage window.
            if (reader.hasUsageStatsPermission()) {
                val usageStartMs = resolveAppUsageWindowStart(
                    lastCursorMs = settings.lastAppUsageCursorMs,
                    fallbackSampleMs = previous?.timestampMs,
                    nowMs = current.timestampMs
                )
                val intervalMs = (current.timestampMs - usageStartMs).coerceAtLeast(0L)
                if (intervalMs >= 60_000L) {
                    val observedApps = reader.queryAppUsageWindow(
                        startMs = usageStartMs,
                        endMs = current.timestampMs
                    )
                    prefs.setLastAppUsageCursorMs(current.timestampMs)

                    if (observedApps.isNotEmpty() && !current.isCharging) {
                        val windowSamples = recentSamples.filter {
                            it.timestampMs in usageStartMs..current.timestampMs
                        }
                        val events = computeAppDrainEvents(
                            observedApps = observedApps,
                            windowStartMs = usageStartMs,
                            windowEndMs = current.timestampMs,
                            samplesInWindow = windowSamples,
                            fallbackPrevSample = previous,
                            currentSample = current,
                            ratedCapacityMah = settings.ratedCapacityMah
                        )
                        if (events.isNotEmpty()) {
                            dao.insertAppEvents(events)
                        }
                    }
                } else {
                    prefs.setLastAppUsageCursorMs(current.timestampMs)
                }
            }

            // Evaluate critical alerts (overheating & abnormal standby drain only)
            val recentMeters = dao.observeEnergyMetersSince(since24h).first()
            val report = RootCauseAnalyzer.analyze(
                samples = recentSamples,
                latestSample = current,
                appEvents = emptyList(),
                liveAppUsage = emptyList(),
                window = AnalysisWindow.TWENTY_FOUR_HOURS,
                ratedCapacityMah = settings.ratedCapacityMah,
                thermalAlertCelsius = settings.thermalAlertCelsius,
                energyMeterSnapshots = recentMeters
            )

            BatteryAlertManager.evaluateAndNotify(
                context = applicationContext,
                latestSample = current,
                sleepSessions = report.sleepSessions,
                settings = settings,
                preferences = prefs
            )

            // Evaluate armed "Watch for this again" investigation in the background using the
            // triggering event's stable ID and timestamp (never the advancing windowEndMs).
            if (settings.watchConfig.active) {
                val recentAppEvents = dao.observeAppEventsSince(since24h).first()
                val overnight = OvernightReplayEngine.analyze(
                    allSamples = recentSamples,
                    appEvents = recentAppEvents,
                    watchConfig = settings.watchConfig,
                    ratedCapacityMah = settings.ratedCapacityMah,
                    nowMs = current.timestampMs
                )
                val eventId = overnight.watchMatchEventId
                val eventTs = overnight.watchMatchEventTimestampMs
                if (overnight.watchTriggeredThisWindow &&
                    !overnight.watchMatchReason.isNullOrBlank() &&
                    !eventId.isNullOrBlank() &&
                    eventTs != null
                ) {
                    BatteryAlertManager.notifyWatchMatchIfNeeded(
                        context = applicationContext,
                        title = settings.watchConfig.title,
                        reason = overnight.watchMatchReason,
                        matchedEventId = eventId,
                        matchedAtMs = eventTs,
                        settings = settings,
                        preferences = prefs
                    )
                }
            }

            // Refresh home screen widgets with the latest telemetry & top driver
            VoltTraceWidgetUpdater.updateAllWidgets(applicationContext)

            // Retain 14 days of longitudinal telemetry locally
            val cutoffMs = current.timestampMs - 14L * 24L * 60L * 60L * 1000L
            dao.pruneSamplesOlderThan(cutoffMs)
            dao.pruneAppEventsOlderThan(cutoffMs)
            dao.pruneEnergyMetersOlderThan(cutoffMs)

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val MAX_USAGE_WINDOW_MS = 4L * 60L * 60_000L
        private const val DEFAULT_FALLBACK_WINDOW_MS = 15L * 60_000L

        /**
         * Resolves the start timestamp for app usage collection from the dedicated persisted
         * cursor rather than the latest battery sample from any source (e.g. 1-minute precision).
         */
        fun resolveAppUsageWindowStart(
            lastCursorMs: Long,
            fallbackSampleMs: Long?,
            nowMs: Long
        ): Long {
            val minAllowedStart = (nowMs - MAX_USAGE_WINDOW_MS).coerceAtLeast(0L)
            return when {
                lastCursorMs in 1L until nowMs -> lastCursorMs.coerceAtLeast(minAllowedStart)
                fallbackSampleMs != null && fallbackSampleMs < nowMs ->
                    fallbackSampleMs.coerceAtLeast(minAllowedStart)
                else -> (nowMs - DEFAULT_FALLBACK_WINDOW_MS).coerceAtLeast(0L)
            }
        }

        /**
         * Attributes only the foreground-covered fraction of an interval's battery discharge to
         * observed foreground apps so idle/screen-off drain remains unassigned rather than being
         * dumped onto a briefly used app.
         */
        fun computeAppDrainEvents(
            observedApps: List<ObservedAppUsage>,
            windowStartMs: Long,
            windowEndMs: Long,
            samplesInWindow: List<BatterySampleEntity>,
            fallbackPrevSample: BatterySampleEntity?,
            currentSample: BatterySampleEntity,
            ratedCapacityMah: Int
        ): List<AppDrainEventEntity> {
            val intervalMs = (windowEndMs - windowStartMs).coerceAtLeast(1L)
            if (observedApps.isEmpty() || currentSample.isCharging) return emptyList()

            val ordered = samplesInWindow.sortedBy { it.timestampMs }
            val startAnchor = ordered.firstOrNull() ?: fallbackPrevSample ?: return emptyList()
            val endAnchor = ordered.lastOrNull() ?: currentSample

            // Calculate total discharge across the usage interval (excluding charging sub-intervals)
            val measuredMah = if (ordered.size >= 2) {
                var sum = 0f
                for (i in 0 until ordered.lastIndex) {
                    val a = ordered[i]
                    val b = ordered[i + 1]
                    if (a.isCharging || b.isCharging) continue
                    val dtHours = ((b.timestampMs - a.timestampMs).coerceAtLeast(0L)) / 3_600_000f
                    if (dtHours <= 0f) continue
                    sum += when {
                        a.chargeCounterMicroAh > 0L && b.chargeCounterMicroAh > 0L ->
                            ((a.chargeCounterMicroAh - b.chargeCounterMicroAh) / 1000f).coerceIn(0f, 3000f)
                        a.powerMw > 10f || b.powerMw > 10f -> {
                            val avgMw = listOf(a.powerMw, b.powerMw).filter { it > 0f }.average().toFloat()
                            val avgV = ((a.voltageVolts + b.voltageVolts) / 2f).coerceIn(3.2f, 4.5f)
                            (avgMw * dtHours) / avgV
                        }
                        a.levelPct > b.levelPct -> ((a.levelPct - b.levelPct) / 100f) * ratedCapacityMah
                        else -> 0f
                    }
                }
                sum
            } else {
                val dtHours = intervalMs / 3_600_000f
                val avgV = ((startAnchor.voltageVolts + endAnchor.voltageVolts) / 2f).coerceIn(3.2f, 4.5f)
                when {
                    startAnchor.chargeCounterMicroAh > 0L && endAnchor.chargeCounterMicroAh > 0L ->
                        ((startAnchor.chargeCounterMicroAh - endAnchor.chargeCounterMicroAh) / 1000f)
                            .coerceIn(0f, 3000f)
                    startAnchor.powerMw > 10f || endAnchor.powerMw > 10f -> {
                        val avgMw = listOf(startAnchor.powerMw, endAnchor.powerMw)
                            .filter { it > 0f }.average().toFloat()
                        (avgMw * dtHours) / avgV
                    }
                    startAnchor.levelPct > endAnchor.levelPct ->
                        ((startAnchor.levelPct - endAnchor.levelPct) / 100f) * ratedCapacityMah
                    else -> 0f
                }
            }

            if (measuredMah <= 0.05f) return emptyList()

            val totalFgMs = observedApps.sumOf { it.foregroundMs.coerceAtLeast(0L) }
            if (totalFgMs <= 0L) return emptyList()

            // Scale app-attributable pool by actual foreground time coverage inside [windowStartMs, windowEndMs].
            // Remaining (1 - foregroundCoverage) stays unassigned idle/system energy.
            val foregroundCoverage = (totalFgMs.toFloat() / intervalMs.toFloat()).coerceIn(0f, 1f)
            val appPoolMah = measuredMah * foregroundCoverage

            val avgPowerMw = listOf(startAnchor.powerMw, endAnchor.powerMw)
                .filter { it > 0f }
                .ifEmpty { listOf(180f) }
                .average()
                .toFloat()

            return observedApps.map { app ->
                val share = app.foregroundMs.toFloat() / totalFgMs.toFloat()
                AppDrainEventEntity(
                    timestampMs = windowEndMs,
                    intervalDurationMs = intervalMs,
                    packageName = app.packageName,
                    appLabel = app.appLabel,
                    foregroundMs = app.foregroundMs,
                    launchCount = app.launchCount,
                    estimatedMah = abs(appPoolMah * share),
                    avgPowerMw = avgPowerMw
                )
            }
        }
    }
}
