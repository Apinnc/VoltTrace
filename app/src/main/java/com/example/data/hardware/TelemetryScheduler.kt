package com.example.data.hardware

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object TelemetryScheduler {
    private const val PERIODIC_WORK_NAME = "volttrace_periodic_telemetry"

    fun setPeriodicMonitoring(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<BatteryTelemetryWorker>(
                15, TimeUnit.MINUTES
            ).build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } else {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }
}
