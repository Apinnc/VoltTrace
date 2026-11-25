package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.hardware.BatteryHardwareReader
import com.example.data.hardware.EnergyMeterInventory
import com.example.data.hardware.HighPrecisionMonitorService
import com.example.data.hardware.ObservedAppUsage
import com.example.data.hardware.PowerMonitorDiscovery
import com.example.data.hardware.TelemetryScheduler
import com.example.data.local.AppDrainEventEntity
import com.example.data.local.BatteryDao
import com.example.data.local.BatterySampleEntity
import com.example.data.local.EnergyMeterSnapshotEntity
import com.example.data.local.MonitorPreferences
import com.example.data.local.UserMonitorSettings
import com.example.domain.BatteryAnalysisReport
import com.example.domain.DiagnosticReportGenerator
import com.example.domain.ExportFormat
import com.example.domain.WindowUsageEventItem
import com.example.ui.theme.ThemeMode
import com.example.widget.VoltTraceWidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class BatteryRepository(
    private val context: Context,
    private val batteryDao: BatteryDao,
    private val hardwareReader: BatteryHardwareReader,
    private val powerMonitorDiscovery: PowerMonitorDiscovery,
    private val preferences: MonitorPreferences
) {
    val settingsFlow: Flow<UserMonitorSettings> = preferences.settingsFlow
    val totalSampleCountFlow: Flow<Int> = batteryDao.observeSampleCount()

    fun observeSamplesSince(sinceMs: Long): Flow<List<BatterySampleEntity>> =
        batteryDao.observeSamplesSince(sinceMs)

    fun observeAppEventsSince(sinceMs: Long): Flow<List<AppDrainEventEntity>> =
        batteryDao.observeAppEventsSince(sinceMs)

    fun observeEnergyMetersSince(sinceMs: Long): Flow<List<EnergyMeterSnapshotEntity>> =
        batteryDao.observeEnergyMetersSince(sinceMs)

    fun readLiveSample(source: String = "LIVE"): BatterySampleEntity =
        hardwareReader.readCurrentSample(source = source)

    fun hasUsageStatsPermission(): Boolean = hardwareReader.hasUsageStatsPermission()

    suspend fun discoverEnergyMeters(): EnergyMeterInventory = withContext(Dispatchers.IO) {
        val inventory = powerMonitorDiscovery.discoverAndSample()
        if (inventory.apiSupported && inventory.meters.isNotEmpty()) {
            val snapshots = powerMonitorDiscovery.captureSnapshots()
            if (snapshots.isNotEmpty()) {
                batteryDao.insertEnergyMeterSnapshots(snapshots)
            }
        }
        inventory
    }

    suspend fun ensureHighPrecisionServiceSynced() = withContext(Dispatchers.IO) {
        val settings = runCatching { preferences.settingsFlow.first() }.getOrNull() ?: return@withContext
        if (settings.highPrecisionMonitorEnabled && !HighPrecisionMonitorService.isRunning) {
            HighPrecisionMonitorService.setRunning(context, true)
        }
    }

    suspend fun queryRecentAppUsage(sinceMs: Long): List<ObservedAppUsage> =
        withContext(Dispatchers.IO) {
            hardwareReader.queryAppUsageWindow(sinceMs, System.currentTimeMillis())
        }

    suspend fun queryAppUsageRange(startMs: Long, endMs: Long): List<ObservedAppUsage> =
        withContext(Dispatchers.IO) {
            hardwareReader.queryAppUsageWindow(startMs, endMs)
        }

    suspend fun queryDetailedEventsAround(startMs: Long, endMs: Long): List<WindowUsageEventItem> =
        withContext(Dispatchers.IO) {
            hardwareReader.queryDetailedEventsAround(startMs, endMs)
        }

    suspend fun getEnergyMetersInRange(startMs: Long, endMs: Long): List<EnergyMeterSnapshotEntity> =
        withContext(Dispatchers.IO) {
            batteryDao.getEnergyMetersInRange(startMs, endMs)
        }

    suspend fun getAppEventsInRange(startMs: Long, endMs: Long): List<AppDrainEventEntity> =
        withContext(Dispatchers.IO) {
            batteryDao.getAppEventsInRange(startMs, endMs)
        }

    suspend fun recordSample(sample: BatterySampleEntity): Long = withContext(Dispatchers.IO) {
        val id = batteryDao.insertSample(sample)
        runCatching { VoltTraceWidgetUpdater.updateAllWidgets(context) }
        id
    }

    suspend fun recordSampleIfSignificant(sample: BatterySampleEntity, minIntervalMs: Long = 60_000L) {
        withContext(Dispatchers.IO) {
            val latest = batteryDao.getLatestSample()
            if (latest != null && (sample.timestampMs - latest.timestampMs) < 3_000L &&
                sample.isCharging == latest.isCharging &&
                sample.isScreenOn == latest.isScreenOn
            ) {
                return@withContext
            }
            val shouldInsert = latest == null ||
                (sample.timestampMs - latest.timestampMs) >= minIntervalMs ||
                sample.levelPct != latest.levelPct ||
                sample.isCharging != latest.isCharging ||
                sample.isScreenOn != latest.isScreenOn ||
                sample.networkTransport != latest.networkTransport ||
                sample.gpsEnabled != latest.gpsEnabled ||
                sample.bluetoothEnabled != latest.bluetoothEnabled ||
                sample.wifiEnabled != latest.wifiEnabled
            if (shouldInsert) {
                batteryDao.insertSample(sample)
            }
        }
    }

    suspend fun exportDiagnosticReport(
        format: ExportFormat,
        report: BatteryAnalysisReport,
        samples: List<BatterySampleEntity>,
        latestSample: BatterySampleEntity?
    ): Uri = withContext(Dispatchers.IO) {
        DiagnosticReportGenerator.writeExportToCacheUri(
            context = context,
            format = format,
            report = report,
            samples = samples,
            latestSample = latestSample
        )
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        batteryDao.clearAllSamples()
        batteryDao.clearAllAppEvents()
        batteryDao.clearAllEnergyMeters()
        preferences.setLastAppUsageCursorMs(0L)
        DiagnosticReportGenerator.clearExportCache(context)
    }

    suspend fun setBackgroundMonitoring(enabled: Boolean) {
        preferences.setBackgroundMonitoring(enabled)
        TelemetryScheduler.setPeriodicMonitoring(context, enabled)
    }

    suspend fun setHighPrecisionMonitor(enabled: Boolean) {
        preferences.setHighPrecisionMonitor(enabled)
        HighPrecisionMonitorService.setRunning(context, enabled)
        withContext(Dispatchers.IO) {
            runCatching { VoltTraceWidgetUpdater.updateAllWidgets(context) }
        }
    }

    suspend fun setCriticalAlertsEnabled(enabled: Boolean) {
        preferences.setCriticalAlertsEnabled(enabled)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        preferences.setThemeMode(mode)
    }

    suspend fun setThermalAlertCelsius(tempC: Float) {
        preferences.setThermalAlertCelsius(tempC)
    }

    suspend fun setRatedCapacityMah(capacityMah: Int) {
        preferences.setRatedCapacityMah(capacityMah)
    }

    suspend fun armWatchInvestigation(config: com.example.domain.WatchInvestigationConfig) {
        preferences.armWatchInvestigation(config)
    }

    suspend fun clearWatchInvestigation() {
        preferences.clearWatchInvestigation()
    }

    suspend fun recordWatchMatch(matchedEventId: String, matchedAtMs: Long, summary: String) {
        preferences.recordWatchMatch(matchedEventId, matchedAtMs, summary)
    }
}
