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
                    if (observedApps.isNotEmpty()) {
                        val windowSamples = dao.getSamplesInRange(usageStartMs, current.timestampMs)
                            .ifEmpty {
                                recentSamples.filter { it.timestampMs in usageStartMs..current.timestampMs }
                            }
                        val anchorBefore = dao.getLatestSampleAtOrBefore(usageStartMs) ?: previous
                        val events = computeAppDrainEvents(
                            observedApps = observedApps,
                            windowStartMs = usageStartMs,
                            windowEndMs = current.timestampMs,
                            samplesInWindow = windowSamples,
                            fallbackPrevSample = anchorBefore,
                            currentSample = current,
                            ratedCapacityMah = settings.ratedCapacityMah
                        )
                        if (events.isNotEmpty()) {
                            events
                                .map { it.timestampMs to it.intervalDurationMs }
                                .distinct()
                                .forEach { (evEndMs, evDurMs) ->
                                    dao.deleteAppEventsForWindow(evEndMs, evDurMs)
                                }
                            dao.insertAppEvents(events)
                        }
                    }
                    // Advance cursor ONLY after sample and app-drain database writes succeed.
                    prefs.setLastAppUsageCursorMs(current.timestampMs)
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

        private data class SubIntervalInfo(
            val startMs: Long,
            val endMs: Long,
            val durationMs: Long,
            val isConfirmedDischarge: Boolean,
            val dischargeMah: Float,
            val avgPowerMw: Float
        )

        /**
         * Attributes only the foreground-covered fraction of confirmed discharging sub-intervals'
         * battery loss to apps active during those discharging sub-intervals.
         *
         * - Preserves discharge attribution from discharging sub-intervals that occurred before a plug-in,
         *   even when [currentSample] is already charging.
         * - Excludes app usage that occurred during charging or plug-transition sub-intervals.
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
            if (observedApps.isEmpty() || windowEndMs <= windowStartMs) return emptyList()

            val candidates = buildList {
                if (fallbackPrevSample != null) add(fallbackPrevSample)
                addAll(samplesInWindow)
                add(currentSample)
            }.distinctBy { it.timestampMs }.sortedBy { it.timestampMs }

            val latestBeforeStart = candidates.lastOrNull { it.timestampMs <= windowStartMs }
            val insideOrAfter = candidates.filter { it.timestampMs > windowStartMs && it.timestampMs <= windowEndMs }
            val chain = buildList {
                if (latestBeforeStart != null) add(latestBeforeStart)
                addAll(insideOrAfter)
            }.distinctBy { it.timestampMs }.sortedBy { it.timestampMs }

            if (chain.size < 2) return emptyList()

            val subIntervals = mutableListOf<SubIntervalInfo>()
            for (i in 0 until chain.lastIndex) {
                val a = chain[i]
                val b = chain[i + 1]
                val subStart = maxOf(a.timestampMs, windowStartMs)
                val subEnd = minOf(b.timestampMs, windowEndMs)
                val subDurMs = subEnd - subStart
                if (subDurMs <= 0L) continue

                val isConfirmedDischarge = !a.isCharging && !b.isCharging &&
                    !a.isConnectedToExternalPower && !b.isConnectedToExternalPower
                val fullPairDtMs = (b.timestampMs - a.timestampMs).coerceAtLeast(1L)
                val dtHours = fullPairDtMs / 3_600_000f
                val aPower = if (a.hasUncertainLegacyCurrentUnit) 0f else a.powerMw
                val bPower = if (b.hasUncertainLegacyCurrentUnit) 0f else b.powerMw
                val avgMw = listOf(aPower, bPower).filter { it > 0f }.ifEmpty { listOf(180f) }.average().toFloat()

                val fullPairMah = if (!isConfirmedDischarge) {
                    0f
                } else {
                    when {
                        a.chargeCounterMicroAh > 0L && b.chargeCounterMicroAh > 0L ->
                            ((a.chargeCounterMicroAh - b.chargeCounterMicroAh) / 1000f).coerceIn(0f, 3000f)
                        aPower > 10f || bPower > 10f -> {
                            val avgV = ((a.voltageVolts + b.voltageVolts) / 2f).coerceIn(3.2f, 4.5f)
                            (avgMw * dtHours) / avgV
                        }
                        a.levelPct > b.levelPct -> ((a.levelPct - b.levelPct) / 100f) * ratedCapacityMah
                        else -> 0f
                    }
                }
                val subMah = fullPairMah * (subDurMs.toFloat() / fullPairDtMs.toFloat())
                subIntervals.add(
                    SubIntervalInfo(
                        startMs = subStart,
                        endMs = subEnd,
                        durationMs = subDurMs,
                        isConfirmedDischarge = isConfirmedDischarge,
                        dischargeMah = subMah,
                        avgPowerMw = avgMw
                    )
                )
            }

            val dischargingSubs = subIntervals.filter { it.isConfirmedDischarge && it.dischargeMah > 0.01f }
            if (dischargingSubs.isEmpty()) return emptyList()

            val windowHasNonDischargeSub = subIntervals.any { !it.isConfirmedDischarge }
            val appMahByPkg = mutableMapOf<String, Float>()
            val appFgInDischargeByPkg = mutableMapOf<String, Long>()
            val appPowerWeightedByPkg = mutableMapOf<String, Float>()

            for (sub in dischargingSubs) {
                val fgInSubByPkg = mutableMapOf<String, Long>()
                for (app in observedApps) {
                    val fgInSub = if (app.activeIntervals.isNotEmpty()) {
                        app.activeIntervals.sumOf { (ivS, ivE) ->
                            (minOf(ivE, sub.endMs) - maxOf(ivS, sub.startMs)).coerceAtLeast(0L)
                        }.coerceAtMost(sub.durationMs)
                    } else {
                        val appStart = if (app.windowStartMs > 0L) app.windowStartMs else windowStartMs
                        val appEnd = if (app.windowEndMs > appStart) app.windowEndMs else windowEndMs
                        val overlapsNonDischarge = windowHasNonDischargeSub && subIntervals.any { other ->
                            !other.isConfirmedDischarge && minOf(appEnd, other.endMs) > maxOf(appStart, other.startMs)
                        }
                        if (overlapsNonDischarge) {
                            // Coarse app summary spans a charging/transition sub-interval without exact session bounds
                            0L
                        } else {
                            val totalDischargeOverlap = dischargingSubs.sumOf { d ->
                                (minOf(appEnd, d.endMs) - maxOf(appStart, d.startMs)).coerceAtLeast(0L)
                            }
                            val subOverlap = (minOf(appEnd, sub.endMs) - maxOf(appStart, sub.startMs)).coerceAtLeast(0L)
                            if (totalDischargeOverlap > 0L && subOverlap > 0L) {
                                ((app.foregroundMs.toDouble() * subOverlap.toDouble()) / totalDischargeOverlap.toDouble())
                                    .toLong()
                                    .coerceAtMost(sub.durationMs)
                            } else {
                                0L
                            }
                        }
                    }
                    if (fgInSub > 0L) {
                        fgInSubByPkg[app.packageName] = fgInSub
                    }
                }

                val totalSubFgMs = fgInSubByPkg.values.sum()
                if (totalSubFgMs <= 0L) continue

                val subCoverage = (totalSubFgMs.toFloat() / sub.durationMs.toFloat()).coerceIn(0f, 1f)
                val subAppPoolMah = sub.dischargeMah * subCoverage

                for ((pkg, fgMs) in fgInSubByPkg) {
                    val share = fgMs.toFloat() / totalSubFgMs.toFloat()
                    val allocatedMah = abs(subAppPoolMah * share)
                    appMahByPkg[pkg] = (appMahByPkg[pkg] ?: 0f) + allocatedMah
                    appFgInDischargeByPkg[pkg] = (appFgInDischargeByPkg[pkg] ?: 0L) + fgMs
                    appPowerWeightedByPkg[pkg] = (appPowerWeightedByPkg[pkg] ?: 0f) + sub.avgPowerMw * fgMs.toFloat()
                }
            }

            if (appMahByPkg.isEmpty()) return emptyList()

            val effectiveStartMs = dischargingSubs.minOf { it.startMs }
            val effectiveEndMs = dischargingSubs.maxOf { it.endMs }
            val effectiveDurationMs = (effectiveEndMs - effectiveStartMs).coerceAtLeast(1L)

            return observedApps.mapNotNull { app ->
                val mah = appMahByPkg[app.packageName] ?: return@mapNotNull null
                val fgMs = appFgInDischargeByPkg[app.packageName] ?: return@mapNotNull null
                if (mah <= 0.01f || fgMs <= 0L) return@mapNotNull null
                val avgPower = (appPowerWeightedByPkg[app.packageName] ?: 180f) / fgMs.toFloat()
                AppDrainEventEntity(
                    timestampMs = effectiveEndMs,
                    intervalDurationMs = effectiveDurationMs,
                    packageName = app.packageName,
                    appLabel = app.appLabel,
                    foregroundMs = fgMs,
                    launchCount = app.launchCount,
                    estimatedMah = mah,
                    avgPowerMw = avgPower
                )
            }
        }
    }
}
