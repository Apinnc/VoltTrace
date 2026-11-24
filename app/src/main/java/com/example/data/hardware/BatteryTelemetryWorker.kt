package com.example.data.hardware

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.AppDrainEventEntity
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

            val previous = dao.getLatestSample()
            val current = reader.readCurrentSample(source = "WORK")
            dao.insertSample(current)

            // Capture Android 15+ PowerMonitor hardware energy snapshots if exposed by device HAL
            val powerDiscovery = PowerMonitorDiscovery(applicationContext)
            val meterSnapshots = powerDiscovery.captureSnapshots(current.timestampMs)
            if (meterSnapshots.isNotEmpty()) {
                dao.insertEnergyMeterSnapshots(meterSnapshots)
            }

            // Correlate per-app foreground sessions over the elapsed interval if Usage Access is granted
            if (previous != null && reader.hasUsageStatsPermission()) {
                val intervalMs = (current.timestampMs - previous.timestampMs)
                    .coerceIn(60_000L, 4 * 60 * 60_000L)
                val observedApps = reader.queryAppUsageWindow(
                    startMs = current.timestampMs - intervalMs,
                    endMs = current.timestampMs
                )
                if (observedApps.isNotEmpty() && !current.isCharging) {
                    val totalFgMs = observedApps.sumOf { it.foregroundMs }.coerceAtLeast(1L)
                    val intervalHours = intervalMs / 3_600_000f
                    val avgPowerMw = ((previous.powerMw + current.powerMw) / 2f).coerceAtLeast(180f)
                    val avgVoltageV = ((previous.voltageVolts + current.voltageVolts) / 2f)
                        .coerceAtLeast(3.7f)

                    val measuredMah = when {
                        previous.chargeCounterMicroAh > 0L && current.chargeCounterMicroAh > 0L -> {
                            ((previous.chargeCounterMicroAh - current.chargeCounterMicroAh) / 1000f)
                                .coerceAtLeast(0f)
                        }

                        else -> (avgPowerMw * intervalHours) / avgVoltageV
                    }

                    if (measuredMah > 0.05f) {
                        val events = observedApps.map { app ->
                            val share = app.foregroundMs.toFloat() / totalFgMs.toFloat()
                            AppDrainEventEntity(
                                timestampMs = current.timestampMs,
                                intervalDurationMs = intervalMs,
                                packageName = app.packageName,
                                appLabel = app.appLabel,
                                foregroundMs = app.foregroundMs,
                                launchCount = app.launchCount,
                                estimatedMah = abs(measuredMah * share),
                                avgPowerMw = avgPowerMw
                            )
                        }
                        dao.insertAppEvents(events)
                    }
                }
            }

            // Evaluate critical alerts (overheating & abnormal standby drain only)
            val settings = prefs.settingsFlow.first()
            val since24h = current.timestampMs - 24L * 60L * 60L * 1000L
            val recentSamples = dao.observeSamplesSince(since24h).first()
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

            // Evaluate armed "Watch for this again" investigation in the background
            if (settings.watchConfig.active) {
                val recentAppEvents = dao.observeAppEventsSince(since24h).first()
                val overnight = OvernightReplayEngine.analyze(
                    allSamples = recentSamples,
                    appEvents = recentAppEvents,
                    watchConfig = settings.watchConfig,
                    ratedCapacityMah = settings.ratedCapacityMah,
                    nowMs = current.timestampMs
                )
                if (overnight.watchTriggeredThisWindow && !overnight.watchMatchReason.isNullOrBlank()) {
                    BatteryAlertManager.notifyWatchMatchIfNeeded(
                        context = applicationContext,
                        title = settings.watchConfig.title,
                        reason = overnight.watchMatchReason,
                        matchedAtMs = overnight.windowEndMs,
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
}
