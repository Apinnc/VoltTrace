package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.hardware.BatteryHardwareReader
import com.example.data.local.MonitorPreferences
import com.example.data.local.VoltTraceDatabase
import com.example.domain.AnalysisWindow
import com.example.domain.CauseSeverity
import com.example.domain.RootCauseAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoltTraceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                VoltTraceWidgetUpdater.updateWidgets(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetIds = appWidgetIds,
                    recordSnapshot = false
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WIDGET) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val manager = AppWidgetManager.getInstance(context)
                    val ids = manager.getAppWidgetIds(
                        ComponentName(context, VoltTraceWidgetProvider::class.java)
                    )
                    VoltTraceWidgetUpdater.updateWidgets(
                        context = context,
                        appWidgetManager = manager,
                        appWidgetIds = ids,
                        recordSnapshot = true
                    )
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.example.widget.ACTION_REFRESH_WIDGET"
    }
}

object VoltTraceWidgetUpdater {

    suspend fun updateAllWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, VoltTraceWidgetProvider::class.java)
        )
        if (ids.isNotEmpty()) {
            updateWidgets(context, manager, ids, recordSnapshot = false)
        }
    }

    suspend fun updateWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        recordSnapshot: Boolean
    ) {
        if (appWidgetIds.isEmpty()) return

        val reader = BatteryHardwareReader(context)
        val db = VoltTraceDatabase.getInstance(context)
        val prefs = MonitorPreferences(context)

        val liveSample = reader.readCurrentSample(if (recordSnapshot) "WIDGET" else "LIVE")
        if (recordSnapshot) {
            runCatching { db.batteryDao().insertSample(liveSample) }
        }

        val settings = runCatching { prefs.settingsFlow.first() }
            .getOrDefault(com.example.data.local.UserMonitorSettings())
        val since24h = liveSample.timestampMs - 24L * 60L * 60L * 1000L
        val samples = runCatching { db.batteryDao().observeSamplesSince(since24h).first() }
            .getOrDefault(emptyList())

        val report = RootCauseAnalyzer.analyze(
            samples = samples,
            latestSample = liveSample,
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.TWENTY_FOUR_HOURS,
            ratedCapacityMah = settings.ratedCapacityMah,
            thermalAlertCelsius = settings.thermalAlertCelsius
        )

        val topFinding = report.findings.firstOrNull()
        val topHw = report.hardwareBreakdown.firstOrNull()
        val causeLine = when {
            topFinding != null -> "${topFinding.title} (${topFinding.headlineMetric})"
            topHw != null -> "Top load: ${topHw.component.displayName} (${topHw.sharePct.toInt()}%)"
            else -> "Balanced hardware load"
        }

        val statusText = when {
            liveSample.isCharging -> "CHARGING"
            settings.highPrecisionMonitorEnabled -> "1-MIN LIVE"
            else -> "DISCHARGING"
        }

        val statusColor = when {
            liveSample.isCharging -> 0xFF38BDF8.toInt()
            topFinding?.severity == CauseSeverity.CRITICAL -> 0xFFFF5A5F.toInt()
            topFinding?.severity == CauseSeverity.ELEVATED -> 0xFFFFB74D.toInt()
            else -> 0xFFA6FF4D.toInt()
        }

        val powerText = if (liveSample.powerMw > 25f) {
            String.format(Locale.US, "%+.2f W · %+.0f mA", liveSample.powerSignedWatts, liveSample.currentSignedMa)
        } else {
            val estW = (RootCauseAnalyzer.estimateLiveSubsystems(liveSample)
                .sumOf { it.liveEstimatedMw.toDouble() } / 1000.0)
            String.format(Locale.US, "~%.2f W est.", estW)
        }

        val tempVoltText = String.format(
            Locale.US,
            "%.1f°C · %.2f V · Score %d",
            liveSample.temperatureCelsius,
            liveSample.voltageVolts,
            report.efficiencyScore
        )

        val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(liveSample.timestampMs))

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_START_ROUTE, "causes")
        }
        val openPending = PendingIntent.getActivity(
            context,
            301,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val refreshIntent = Intent(context, VoltTraceWidgetProvider::class.java).apply {
            action = VoltTraceWidgetProvider.ACTION_REFRESH_WIDGET
        }
        val refreshPending = PendingIntent.getBroadcast(
            context,
            302,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        appWidgetIds.forEach { widgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_volttrace).apply {
                setTextViewText(R.id.widget_level_pct, "${liveSample.levelPct}%")
                setTextViewText(R.id.widget_power_watts, powerText)
                setTextColor(R.id.widget_power_watts, statusColor)
                setTextViewText(R.id.widget_temp_volt, tempVoltText)
                setTextViewText(R.id.widget_status_badge, statusText)
                setTextColor(R.id.widget_status_badge, statusColor)
                setTextViewText(R.id.widget_top_cause_label, causeLine)
                setTextViewText(R.id.widget_updated_time, timeText)
                setOnClickPendingIntent(R.id.widget_root, openPending)
                setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPending)
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
