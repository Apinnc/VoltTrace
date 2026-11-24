package com.example.data.hardware

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.BatterySampleEntity
import com.example.data.local.MonitorPreferences
import com.example.data.local.UserMonitorSettings
import com.example.domain.RootCauseAnalyzer
import com.example.domain.SleepSessionSummary
import java.util.Locale

object BatteryAlertManager {

    const val CHANNEL_PRECISION_MONITOR = "volttrace_precision_monitor"
    const val CHANNEL_CRITICAL_ALERTS = "volttrace_critical_alerts"

    const val NOTIFICATION_ID_FOREGROUND = 1001
    private const val NOTIFICATION_ID_THERMAL = 2001
    private const val NOTIFICATION_ID_STANDBY = 2002
    private const val NOTIFICATION_ID_WATCH_MATCH = 2003

    private const val ALERT_COOLDOWN_MS = 60L * 60L * 1000L // 60 minutes anti-spam cooldown

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val precisionChannel = NotificationChannel(
            CHANNEL_PRECISION_MONITOR,
            "High-Precision 1-Min Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Silent ongoing telemetry status while 1-minute precision monitoring is enabled."
            setShowBadge(false)
        }

        val criticalChannel = NotificationChannel(
            CHANNEL_CRITICAL_ALERTS,
            "Critical Battery Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts only for critical battery overheating or abnormal screen-off standby drain."
        }

        nm.createNotificationChannel(precisionChannel)
        nm.createNotificationChannel(criticalChannel)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun buildPrecisionServiceNotification(
        context: Context,
        sample: BatterySampleEntity?
    ): Notification {
        ensureChannels(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_START_ROUTE, "live")
        }
        val contentPending = PendingIntent.getActivity(
            context,
            101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, HighPrecisionMonitorService::class.java).apply {
            action = HighPrecisionMonitorService.ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            context,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (sample != null) {
            val state = if (sample.isCharging) "Charging" else "Discharging"
            "${sample.levelPct}% · ${String.format(Locale.US, "%+.2f W", sample.powerSignedWatts)} · ${String.format(Locale.US, "%.1f°C", sample.temperatureCelsius)} ($state)"
        } else {
            "VoltTrace 1-Min Precision Active"
        }

        val detail = if (sample != null) {
            val active = buildList {
                if (sample.isScreenOn) add("DSP ${sample.screenBrightnessPct}%") else add("Standby")
                if (sample.cellularActive) add("Cell")
                if (sample.wifiConnected) add("Wi-Fi")
                if (sample.gpsEnabled) add("GPS")
                if (sample.bluetoothEnabled) add("BT")
            }.joinToString(" · ")
            "1-min rail sampling active · $active"
        } else {
            "Recording hardware power rails every 60 seconds"
        }

        return NotificationCompat.Builder(context, CHANNEL_PRECISION_MONITOR)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPending)
            .addAction(0, "Stop", stopPending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Evaluates strictly CRITICAL conditions:
     * 1. Critical battery overheating (>= threshold + 3°C OR Android thermalStatus >= SEVERE)
     * 2. Abnormal screen-off standby drain (>= 2.5%/hr across a >=10 min screen-off session)
     */
    suspend fun evaluateAndNotify(
        context: Context,
        latestSample: BatterySampleEntity,
        sleepSessions: List<SleepSessionSummary>,
        settings: UserMonitorSettings,
        preferences: MonitorPreferences
    ) {
        if (!settings.criticalAlertsEnabled || !hasNotificationPermission(context)) return
        ensureChannels(context)
        val now = System.currentTimeMillis()

        // 1. Critical Overheating Alert
        val criticalTempFloor = settings.thermalAlertCelsius + 3.0f
        val isCriticalHeat = latestSample.temperatureCelsius >= criticalTempFloor ||
            latestSample.thermalStatus >= 3
        if (isCriticalHeat && (now - settings.lastThermalAlertMs) >= ALERT_COOLDOWN_MS) {
            postCriticalNotification(
                context = context,
                notificationId = NOTIFICATION_ID_THERMAL,
                title = "Critical Battery Heat: ${String.format(Locale.US, "%.1f°C", latestSample.temperatureCelsius)}",
                body = "Cell temperature exceeded critical safety margin (${String.format(Locale.US, "%.1f°C", criticalTempFloor)}). Tap to inspect active hardware load."
            )
            preferences.recordThermalAlertTimestamp(now)
        }

        // 2. Abnormal Standby Wakelock Drain Alert (require >= 15 min pure screen-off window)
        val recentBadSleep = sleepSessions.firstOrNull { session ->
            (now - session.endMs) <= 2L * 60L * 60L * 1000L &&
                session.durationMs >= 15L * 60L * 1000L &&
                session.drainRatePctPerHour >= 2.5f
        }
        if (recentBadSleep != null && (now - settings.lastStandbyAlertMs) >= ALERT_COOLDOWN_MS) {
            val radios = if (recentBadSleep.activeRadiosDuringSleep.isEmpty()) {
                "Possible background wakelock"
            } else {
                "Radios active: ${recentBadSleep.activeRadiosDuringSleep.joinToString(", ")}"
            }
            postCriticalNotification(
                context = context,
                notificationId = NOTIFICATION_ID_STANDBY,
                title = "Abnormal Standby Drain: ${String.format(Locale.US, "%.1f%%/hr", recentBadSleep.drainRatePctPerHour)}",
                body = "Lost ${recentBadSleep.startLevelPct - recentBadSleep.endLevelPct}% across ${RootCauseAnalyzer.formatDuration(recentBadSleep.durationMs)} screen-off. $radios."
            )
            preferences.recordStandbyAlertTimestamp(now)
        }
    }

    suspend fun notifyWatchMatchIfNeeded(
        context: Context,
        title: String,
        reason: String,
        matchedAtMs: Long,
        settings: UserMonitorSettings,
        preferences: MonitorPreferences
    ) {
        if (!settings.watchConfig.active) return
        if (matchedAtMs <= settings.watchConfig.lastMatchedAtMs) return
        preferences.recordWatchMatch(matchedAtMs, reason)
        if (!settings.criticalAlertsEnabled || !hasNotificationPermission(context)) return
        ensureChannels(context)
        postCriticalNotification(
            context = context,
            notificationId = NOTIFICATION_ID_WATCH_MATCH,
            title = "Watch Matched: ${title.ifBlank { "Overnight Drain Pattern" }}",
            body = "$reason. Open VoltTrace to replay the window.",
            targetRoute = "live"
        )
    }

    private fun postCriticalNotification(
        context: Context,
        notificationId: Int,
        title: String,
        body: String,
        targetRoute: String = "causes"
    ) {
        if (!hasNotificationPermission(context)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_START_ROUTE, targetRoute)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CRITICAL_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }
}
