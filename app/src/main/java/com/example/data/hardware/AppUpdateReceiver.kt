package com.example.data.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.MonitorPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-registers WorkManager telemetry and attempts to restore the opt-in
 * sleep-friendly foreground monitor after an in-place app upgrade
 * (MY_PACKAGE_REPLACED — requires zero extra manifest permissions).
 */
class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = MonitorPreferences(appContext)
                val settings = runCatching { prefs.settingsFlow.first() }.getOrNull()
                if (settings != null) {
                    TelemetryScheduler.setPeriodicMonitoring(
                        appContext,
                        settings.backgroundMonitoringEnabled
                    )
                    if (settings.highPrecisionMonitorEnabled) {
                        HighPrecisionMonitorService.setRunning(appContext, true)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
