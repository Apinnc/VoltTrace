package com.example.data.hardware

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.VoltTraceApp
import com.example.domain.AnalysisWindow
import com.example.domain.RootCauseAnalyzer
import com.example.widget.VoltTraceWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lightweight, sleep-friendly foreground telemetry monitor.
 *
 * Design principles so the phone sleeps naturally without monitor-induced drain:
 * 1. Zero WakeLocks and zero waking alarms while screen is off.
 * 2. Captures exact boundary samples on SCREEN_OFF, SCREEN_ON, POWER_CONNECTED,
 *    POWER_DISCONNECTED, and passive BATTERY_CHANGED (% or charging transitions).
 * 3. Runs the 1-minute active ticker ONLY while the screen is interactive (CPU already awake);
 *    pauses the ticker immediately on SCREEN_OFF so the SoC enters natural deep sleep.
 * 4. Throttles 24h alert evaluation, widget updates, and PowerMonitor snapshots to boundary
 *    events or >= 5-minute intervals instead of every 60 seconds.
 */
class HighPrecisionMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeTickerJob: Job? = null
    private var receiverRegistered = false

    @Volatile
    private var lastObservedLevelPct: Int = -1
    @Volatile
    private var lastObservedCharging: Boolean? = null
    @Volatile
    private var lastHeavyEvalMs: Long = 0L
    @Volatile
    private var lastMeterCaptureMs: Long = 0L

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    pauseActiveTicker()
                    serviceScope.launch { captureSample(sourceTag = "FGS_SCR_OFF", isBoundaryEvent = true) }
                }

                Intent.ACTION_SCREEN_ON -> {
                    serviceScope.launch { captureSample(sourceTag = "FGS_SCR_ON", isBoundaryEvent = true) }
                    startActiveTickerIfScreenOn()
                }

                Intent.ACTION_POWER_CONNECTED -> {
                    serviceScope.launch { captureSample(sourceTag = "FGS_PLUG", isBoundaryEvent = true) }
                }

                Intent.ACTION_POWER_DISCONNECTED -> {
                    serviceScope.launch { captureSample(sourceTag = "FGS_UNPLUG", isBoundaryEvent = true) }
                }

                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val pct = if (level >= 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else -1
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                    val levelChanged = pct >= 0 && lastObservedLevelPct >= 0 && pct != lastObservedLevelPct
                    val chargeChanged = lastObservedCharging != null && charging != lastObservedCharging
                    if (pct >= 0) lastObservedLevelPct = pct
                    lastObservedCharging = charging

                    // Only persist passive broadcast if battery % or charging state actually changed
                    if (levelChanged || chargeChanged) {
                        val tag = if (chargeChanged) {
                            if (charging) "FGS_CHG_ON" else "FGS_CHG_OFF"
                        } else {
                            "FGS_LVL"
                        }
                        serviceScope.launch { captureSample(sourceTag = tag, isBoundaryEvent = true) }
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                val app = application as VoltTraceApp
                app.preferences.setHighPrecisionMonitor(false)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val app = application as VoltTraceApp
        val initialSample = runCatching { app.hardwareReader.readCurrentSample("FGS_START") }.getOrNull()
        if (initialSample != null) {
            lastObservedLevelPct = initialSample.levelPct
            lastObservedCharging = initialSample.isCharging
        }
        val notification = BatteryAlertManager.buildPrecisionServiceNotification(this, initialSample)

        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        runCatching {
            ServiceCompat.startForeground(
                this,
                BatteryAlertManager.NOTIFICATION_ID_FOREGROUND,
                notification,
                fgType
            )
        }.onFailure {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        registerEventReceiver()
        serviceScope.launch {
            captureSample(sourceTag = "FGS_START", isBoundaryEvent = true)
        }
        startActiveTickerIfScreenOn()
        return START_STICKY
    }

    private fun registerEventReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                stateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    private fun startActiveTickerIfScreenOn() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == false) {
            pauseActiveTicker()
            return
        }
        if (activeTickerJob?.isActive == true) return
        activeTickerJob = serviceScope.launch {
            while (isActive) {
                delay(60_000L)
                val stillInteractive = pm?.isInteractive ?: false
                if (!stillInteractive) {
                    // Screen turned off — stop ticking so CPU enters deep sleep cleanly
                    break
                }
                captureSample(sourceTag = "FGS_1M", isBoundaryEvent = false)
            }
        }
    }

    private fun pauseActiveTicker() {
        activeTickerJob?.cancel()
        activeTickerJob = null
    }

    private suspend fun captureSample(sourceTag: String, isBoundaryEvent: Boolean) {
        runCatching {
            val app = application as VoltTraceApp
            val dao = app.database.batteryDao()
            val sample = app.hardwareReader.readCurrentSample(sourceTag)

            // Guard against sub-2s duplicate rows with identical state
            val latest = dao.getLatestSample()
            val isDuplicate = latest != null &&
                (sample.timestampMs - latest.timestampMs) < 2_000L &&
                sample.isCharging == latest.isCharging &&
                sample.isScreenOn == latest.isScreenOn &&
                sample.levelPct == latest.levelPct
            if (!isDuplicate) {
                dao.insertSample(sample)
            }

            lastObservedLevelPct = sample.levelPct
            lastObservedCharging = sample.isCharging

            // Lightweight notification update (no DB reads)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(
                BatteryAlertManager.NOTIFICATION_ID_FOREGROUND,
                BatteryAlertManager.buildPrecisionServiceNotification(this, sample)
            )

            val now = sample.timestampMs

            // Capture Android 15+ PowerMonitor hardware energy snapshots on boundaries or >= 5 min
            if (isBoundaryEvent || (now - lastMeterCaptureMs) >= 5L * 60_000L) {
                lastMeterCaptureMs = now
                val meterSnapshots = app.powerMonitorDiscovery.captureSnapshots(now)
                if (meterSnapshots.isNotEmpty()) {
                    dao.insertEnergyMeterSnapshots(meterSnapshots)
                }
            }

            // Throttle heavy 24h analysis + widget refresh to boundaries or >= 5 min
            if (isBoundaryEvent || (now - lastHeavyEvalMs) >= 5L * 60_000L) {
                lastHeavyEvalMs = now
                val settings = app.preferences.settingsFlow.first()
                val since24h = now - 24L * 60L * 60L * 1000L
                val recentSamples = dao.observeSamplesSince(since24h).first()
                val report = RootCauseAnalyzer.analyze(
                    samples = recentSamples,
                    latestSample = sample,
                    appEvents = emptyList(),
                    liveAppUsage = emptyList(),
                    window = AnalysisWindow.TWENTY_FOUR_HOURS,
                    ratedCapacityMah = settings.ratedCapacityMah,
                    thermalAlertCelsius = settings.thermalAlertCelsius
                )

                BatteryAlertManager.evaluateAndNotify(
                    context = this,
                    latestSample = sample,
                    sleepSessions = report.sleepSessions,
                    settings = settings,
                    preferences = app.preferences
                )

                VoltTraceWidgetUpdater.updateAllWidgets(this)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        if (receiverRegistered) {
            runCatching { unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
        pauseActiveTicker()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.action.START_PRECISION_MONITOR"
        const val ACTION_STOP = "com.example.action.STOP_PRECISION_MONITOR"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun setRunning(context: Context, enabled: Boolean) {
            val intent = Intent(context, HighPrecisionMonitorService::class.java).apply {
                action = if (enabled) ACTION_START else ACTION_STOP
            }
            if (enabled) {
                if (!isRunning) {
                    runCatching { ContextCompat.startForegroundService(context, intent) }
                }
            } else {
                runCatching { context.stopService(intent) }
            }
        }
    }
}
