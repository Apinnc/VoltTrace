package com.example

import android.app.Application
import com.example.data.hardware.BatteryAlertManager
import com.example.data.hardware.BatteryHardwareReader
import com.example.data.hardware.HighPrecisionMonitorService
import com.example.data.hardware.PowerMonitorDiscovery
import com.example.data.hardware.TelemetryScheduler
import com.example.data.local.MonitorPreferences
import com.example.data.local.VoltTraceDatabase
import com.example.data.repository.BatteryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VoltTraceApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: VoltTraceDatabase by lazy { VoltTraceDatabase.getInstance(this) }
    val preferences: MonitorPreferences by lazy { MonitorPreferences(this) }
    val hardwareReader: BatteryHardwareReader by lazy { BatteryHardwareReader(this) }
    val powerMonitorDiscovery: PowerMonitorDiscovery by lazy { PowerMonitorDiscovery(this) }
    val repository: BatteryRepository by lazy {
        BatteryRepository(
            context = this,
            batteryDao = database.batteryDao(),
            hardwareReader = hardwareReader,
            powerMonitorDiscovery = powerMonitorDiscovery,
            preferences = preferences
        )
    }

    override fun onCreate() {
        super.onCreate()
        BatteryAlertManager.ensureChannels(this)
        appScope.launch {
            runCatching {
                val settings = preferences.settingsFlow.first()
                TelemetryScheduler.setPeriodicMonitoring(
                    this@VoltTraceApp,
                    settings.backgroundMonitoringEnabled
                )
                if (settings.highPrecisionMonitorEnabled && !HighPrecisionMonitorService.isRunning) {
                    HighPrecisionMonitorService.setRunning(this@VoltTraceApp, true)
                }
            }
        }
    }
}
