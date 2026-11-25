package com.example.data.hardware

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import com.example.data.local.BatterySampleEntity
import com.example.domain.WindowUsageEventItem
import kotlin.math.abs
import kotlin.math.roundToInt

data class ObservedAppUsage(
    val packageName: String,
    val appLabel: String,
    val foregroundMs: Long,
    val launchCount: Int,
    val activeIntervals: List<Pair<Long, Long>> = emptyList(),
    val windowStartMs: Long = 0L,
    val windowEndMs: Long = 0L
)

class BatteryHardwareReader(private val context: Context) {

    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    private val halPrefs = context.applicationContext
        .getSharedPreferences("volttrace_hal_calibration", Context.MODE_PRIVATE)
    @Volatile
    private var halUsesMicroAmps: Boolean = halPrefs.getBoolean("hal_uses_micro_amps", false)

    private val labelCache = mutableMapOf<String, String>()

    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun readCurrentSample(source: String = "LIVE"): BatterySampleEntity {
        val now = System.currentTimeMillis()
        val elapsedRealtimeMs = SystemClock.elapsedRealtime()
        val uptimeMs = SystemClock.uptimeMillis()

        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val levelPct = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
        } else {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.coerceIn(0, 100) ?: 0
        }

        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            ?.let { raw -> if (raw in 1..20) raw * 1000 else raw }
            ?.coerceAtLeast(0) ?: 0

        val tempDeciC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val status = intent?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val hasPluggedExtra = intent?.hasExtra(BatteryManager.EXTRA_PLUGGED) == true
        val pluggedCode = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val pluggedType = when {
            !hasPluggedExtra || pluggedCode < 0 -> "UNKNOWN"
            pluggedCode == BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            pluggedCode == BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            pluggedCode == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                pluggedCode == BatteryManager.BATTERY_PLUGGED_DOCK -> "DOCK"
            pluggedCode == 0 -> "BATTERY"
            else -> "AC"
        }

        val healthCode = intent?.getIntExtra(
            BatteryManager.EXTRA_HEALTH,
            BatteryManager.BATTERY_HEALTH_UNKNOWN
        ) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN

        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
            ?.takeIf { it.isNotBlank() } ?: "Li-ion"

        val rawCurrent = batteryManager
            ?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?: Long.MIN_VALUE
        val rawAvgCurrent = batteryManager
            ?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            ?: Long.MIN_VALUE
        val effectiveRawCurrent = if (isCurrentReadingAvailable(rawCurrent)) {
            rawCurrent
        } else {
            rawAvgCurrent
        }
        val normalizedCurrentMicroA = normalizeCurrentToMicroAmps(
            raw = effectiveRawCurrent,
            rawAvg = rawAvgCurrent,
            isCharging = isCharging
        )

        val chargeCounter = batteryManager
            ?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.let { if (it == Long.MIN_VALUE || it < 0L) 0L else it } ?: 0L

        val isScreenOn = powerManager?.isInteractive ?: true
        val isPowerSave = powerManager?.isPowerSaveMode ?: false
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager?.currentThermalStatus ?: 0 }.getOrDefault(0)
        } else {
            0
        }

        // Hardware subsystem telemetry: Display, GPS, Wi-Fi, Bluetooth, Cellular
        val brightnessPct = readScreenBrightnessPct()
        val autoBrightness = readAutoBrightnessEnabled()
        val refreshRateHz = readDisplayRefreshRateHz()
        val gpsEnabled = readGpsEnabled()
        val wifiEnabled = readWifiEnabled()
        val networkInfo = readNetworkHardwareState()
        val bluetoothEnabled = readBluetoothEnabled()
        val airplaneModeOn = readAirplaneModeEnabled()

        val foregroundApp = if (hasUsageStatsPermission()) {
            queryMostRecentForegroundApp(now - 5 * 60_000L, now)
        } else {
            null
        }

        return BatterySampleEntity(
            timestampMs = now,
            levelPct = levelPct,
            voltageMv = voltageMv,
            temperatureDeciC = tempDeciC,
            currentMicroA = normalizedCurrentMicroA,
            chargeCounterMicroAh = chargeCounter,
            isCharging = isCharging,
            pluggedType = pluggedType,
            healthCode = healthCode,
            technology = technology,
            isScreenOn = isScreenOn,
            isPowerSaveMode = isPowerSave,
            thermalStatus = thermalStatus,
            networkTransport = networkInfo.transportLabel,
            screenBrightnessPct = brightnessPct,
            displayRefreshRateHz = refreshRateHz,
            displayAutoBrightness = autoBrightness,
            gpsEnabled = gpsEnabled,
            wifiEnabled = wifiEnabled || networkInfo.wifiConnected,
            wifiConnected = networkInfo.wifiConnected,
            bluetoothEnabled = bluetoothEnabled,
            cellularActive = networkInfo.cellularActive,
            airplaneModeOn = airplaneModeOn,
            linkDownKbps = networkInfo.linkDownKbps,
            isMeteredNetwork = networkInfo.isMetered,
            elapsedRealtimeMs = elapsedRealtimeMs,
            uptimeMs = uptimeMs,
            foregroundPackage = foregroundApp?.first,
            foregroundLabel = foregroundApp?.second,
            source = source,
            currentUnitVerified = true
        )
    }

    /**
     * Normalizes OEM current reporting to canonical signed microamperes (µA),
     * preserving the actual sign reported by the battery gauge independently of `isCharging`.
     *
     * Android documents BATTERY_PROPERTY_CURRENT_NOW and BATTERY_PROPERTY_CURRENT_AVERAGE
     * in signed microamperes (µA). We preserve both sign and µA magnitude directly.
     * Any legacy milliamp-HAL scaling is restricted to cases where explicit calibration
     * evidence (`verified_legacy_ma_hal`) has been recorded.
     */
    private fun normalizeCurrentToMicroAmps(raw: Long, rawAvg: Long, isCharging: Boolean): Long {
        if (!isCurrentReadingAvailable(raw)) return 0L
        val absRaw = if (raw == Long.MIN_VALUE) 0L else abs(raw)
        val absAvg = if (rawAvg == Long.MIN_VALUE) 0L else abs(rawAvg)
        if (!halUsesMicroAmps && (absRaw >= 10_000L || absAvg >= 10_000L)) {
            halUsesMicroAmps = true
            halPrefs.edit().putBoolean("hal_uses_micro_amps", true).apply()
        }
        val hasVerifiedLegacyMaEvidence = !halUsesMicroAmps &&
            halPrefs.getBoolean("verified_legacy_ma_hal", false)
        return normalizeCurrentReadingToMicroAmps(
            raw = raw,
            isCharging = isCharging,
            halUsesMicroAmps = halUsesMicroAmps,
            isLegacyMilliampHal = hasVerifiedLegacyMaEvidence
        )
    }

    private data class NetworkHardwareState(
        val transportLabel: String,
        val wifiConnected: Boolean,
        val cellularActive: Boolean,
        val linkDownKbps: Int = 0,
        val isMetered: Boolean = false
    )

    private fun readNetworkHardwareState(): NetworkHardwareState {
        return try {
            val cm = connectivityManager
                ?: return NetworkHardwareState("OFFLINE", wifiConnected = false, cellularActive = false)
            val network = cm.activeNetwork
                ?: return NetworkHardwareState("OFFLINE", wifiConnected = false, cellularActive = false)
            val caps = cm.getNetworkCapabilities(network)
                ?: return NetworkHardwareState("OFFLINE", wifiConnected = false, cellularActive = false)

            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val downKbps = caps.linkDownstreamBandwidthKbps.coerceAtLeast(0)
            val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

            val label = when {
                wifi -> "WIFI"
                cellular -> "CELLULAR"
                ethernet -> "ETHERNET"
                else -> "OFFLINE"
            }
            NetworkHardwareState(
                transportLabel = label,
                wifiConnected = wifi,
                cellularActive = cellular,
                linkDownKbps = downKbps,
                isMetered = metered
            )
        } catch (_: Exception) {
            NetworkHardwareState("OFFLINE", wifiConnected = false, cellularActive = false)
        }
    }

    private fun readWifiEnabled(): Boolean {
        return runCatching { wifiManager?.isWifiEnabled ?: false }.getOrDefault(false)
    }

    private fun readGpsEnabled(): Boolean {
        return runCatching {
            val lm = locationManager ?: return false
            val gpsOn = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }
                .getOrDefault(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled && gpsOn
            } else {
                gpsOn
            }
        }.getOrDefault(false)
    }

    private fun readBluetoothEnabled(): Boolean {
        return runCatching {
            bluetoothManager?.adapter?.isEnabled ?: false
        }.getOrDefault(false)
    }

    private fun readAirplaneModeEnabled(): Boolean {
        return runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        }.getOrDefault(false)
    }

    private fun readDisplayRefreshRateHz(): Float {
        return runCatching {
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            display?.mode?.refreshRate ?: display?.refreshRate ?: 60f
        }.getOrDefault(60f).coerceIn(30f, 240f)
    }

    private fun readAutoBrightnessEnabled(): Boolean {
        return runCatching {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }.getOrDefault(false)
    }

    private fun readScreenBrightnessPct(): Int {
        return try {
            val raw = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )
            val maxScale = when {
                raw > 1023 -> 4095f
                raw > 255 -> 1023f
                else -> 255f
            }
            ((raw / maxScale) * 100f).roundToInt().coerceIn(0, 100)
        } catch (_: Exception) {
            50
        }
    }

    private fun queryMostRecentForegroundApp(startMs: Long, endMs: Long): Pair<String, String>? {
        val usm = usageStatsManager ?: return null
        return try {
            val events = usm.queryEvents(startMs, endMs)
            val event = UsageEvents.Event()
            var latestPkg: String? = null
            var latestTs = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isForegroundEvent = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    @Suppress("DEPRECATION") (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND)
                if (isForegroundEvent && event.timeStamp >= latestTs) {
                    latestTs = event.timeStamp
                    latestPkg = event.packageName
                }
            }
            latestPkg?.let { pkg -> pkg to resolveAppLabel(pkg) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reconstructs exact foreground sessions from timestamped UsageEvents strictly clipped
     * to [startMs, endMs] and merges overlapping intervals per package so an app's active
     * time can never exceed the report window duration.
     */
    fun queryAppUsageWindow(startMs: Long, endMs: Long): List<ObservedAppUsage> {
        if (!hasUsageStatsPermission() || endMs <= startMs) return emptyList()
        val usm = usageStatsManager ?: return emptyList()
        val windowDurationMs = (endMs - startMs).coerceAtLeast(1L)

        return try {
            // Query slightly before startMs (15m lookback) to catch apps already open at startMs
            val lookbackStart = (startMs - 15L * 60_000L).coerceAtLeast(0L)
            val events = usm.queryEvents(lookbackStart, endMs)
            val event = UsageEvents.Event()

            val activeStartByPkg = mutableMapOf<String, Long>()
            val intervalsByPkg = mutableMapOf<String, MutableList<Pair<Long, Long>>>()
            val launchCounts = mutableMapOf<String, Int>()

            fun closeSession(pkg: String, stopTs: Long) {
                val started = activeStartByPkg.remove(pkg) ?: return
                val clippedStart = started.coerceAtLeast(startMs)
                val clippedEnd = stopTs.coerceAtMost(endMs)
                if (clippedEnd > clippedStart) {
                    intervalsByPkg.getOrPut(pkg) { mutableListOf() }.add(clippedStart to clippedEnd)
                }
            }

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                val ts = event.timeStamp
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        // Do not overwrite an already-open session start for multi-activity apps
                        if (pkg !in activeStartByPkg) {
                            activeStartByPkg[pkg] = ts
                        }
                        if (ts >= startMs) {
                            launchCounts[pkg] = (launchCounts[pkg] ?: 0) + 1
                        }
                    }

                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                    @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        closeSession(pkg, ts)
                    }

                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        // Screen turned off: close any open foreground sessions immediately
                        activeStartByPkg.keys.toList().forEach { openPkg ->
                            closeSession(openPkg, ts)
                        }
                    }
                }
            }

            // Close sessions still open at endMs only if screen is currently interactive
            val isInteractiveNow = powerManager?.isInteractive ?: false
            if (isInteractiveNow) {
                activeStartByPkg.keys.toList().forEach { openPkg ->
                    closeSession(openPkg, endMs)
                }
            }

            intervalsByPkg.entries
                .mapNotNull { (pkg, rawIntervals) ->
                    val mergedIntervals = mergeIntervals(rawIntervals)
                    val mergedDuration = mergedIntervals.sumOf { (s, e) -> (e - s).coerceAtLeast(0L) }
                        .coerceAtMost(windowDurationMs)
                    if (mergedDuration < 1_000L) return@mapNotNull null
                    ObservedAppUsage(
                        packageName = pkg,
                        appLabel = resolveAppLabel(pkg),
                        foregroundMs = mergedDuration,
                        launchCount = (launchCounts[pkg] ?: 1).coerceAtLeast(1),
                        activeIntervals = mergedIntervals,
                        windowStartMs = startMs,
                        windowEndMs = endMs
                    )
                }
                .sortedByDescending { it.foregroundMs }
                .take(18)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Queries timestamped system UsageEvents in [startMs, endMs] around a specific timeline
     * spike, including foreground service starts/stops (API 29+), app launches, and screen
     * wake/sleep transitions using only ordinary Usage Access permission.
     */
    fun queryDetailedEventsAround(startMs: Long, endMs: Long): List<WindowUsageEventItem> {
        if (!hasUsageStatsPermission() || endMs <= startMs) return emptyList()
        val usm = usageStatsManager ?: return emptyList()
        return try {
            val events = usm.queryEvents(startMs, endMs)
            val event = UsageEvents.Event()
            val results = mutableListOf<WindowUsageEventItem>()
            val lastSeenByKey = mutableMapOf<String, Long>()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val ts = event.timeStamp
                val pkg = event.packageName.orEmpty()
                val mapped: Pair<String, Boolean>? = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND ->
                        "App Opened (Foreground)" to false

                    UsageEvents.Event.SCREEN_INTERACTIVE ->
                        "Screen Turned On" to false

                    UsageEvents.Event.SCREEN_NON_INTERACTIVE ->
                        "Screen Turned Off" to false

                    UsageEvents.Event.USER_INTERACTION ->
                        "User Interaction" to false

                    else -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            when (event.eventType) {
                                UsageEvents.Event.FOREGROUND_SERVICE_START ->
                                    "Background Service Started" to true
                                UsageEvents.Event.FOREGROUND_SERVICE_STOP ->
                                    "Background Service Stopped" to true
                                else -> null
                            }
                        } else {
                            null
                        }
                    }
                }
                if (mapped == null) continue
                val (label, isBgService) = mapped
                val dedupKey = "$pkg|$label"
                val prevTs = lastSeenByKey[dedupKey]
                if (prevTs != null && (ts - prevTs) < 10_000L) continue
                lastSeenByKey[dedupKey] = ts

                val appLabel = if (pkg.isBlank() || pkg == "android") {
                    "Android System"
                } else {
                    resolveAppLabel(pkg)
                }
                results.add(
                    WindowUsageEventItem(
                        timestampMs = ts,
                        packageName = pkg.ifBlank { "android" },
                        appLabel = appLabel,
                        eventLabel = label,
                        isBackgroundService = isBgService
                    )
                )
            }
            results.sortedBy { it.timestampMs }.takeLast(40)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun resolveAppLabel(packageName: String): String {
        labelCache[packageName]?.let { return it }
        val resolved = try {
            val pm = context.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString().ifBlank { cleanPackageLabel(packageName) }
        } catch (_: Exception) {
            cleanPackageLabel(packageName)
        }
        labelCache[packageName] = resolved
        return resolved
    }

    private fun cleanPackageLabel(packageName: String): String {
        val tail = packageName.substringAfterLast('.')
        return tail.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    companion object {
        /**
         * Returns true when an Android BatteryManager current property reading is present
         * (`Long.MIN_VALUE` is Android's sentinel for an unsupported/unavailable property).
         */
        fun isCurrentReadingAvailable(raw: Long): Boolean = raw != Long.MIN_VALUE

        /**
         * Pure conversion from raw Android BatteryManager current property to canonical signed µA.
         *
         * Preserves the actual sign reported by the battery gauge independently of `isCharging`
         * (e.g., `-250,000 µA` remains `-250,000 µA` / `-250 mA` / `-1.0 W` at 4 V even when
         * OS charging status is `FULL` or `CHARGING`). Returns `0L` for `0L` or unavailable
         * (`Long.MIN_VALUE`) readings. Only verified legacy mA-reporting HALs with supporting
         * evidence scale by 1000x.
         */
        fun normalizeCurrentReadingToMicroAmps(
            raw: Long,
            @Suppress("UNUSED_PARAMETER") isCharging: Boolean = false,
            halUsesMicroAmps: Boolean = true,
            isLegacyMilliampHal: Boolean = false
        ): Long {
            if (raw == 0L || raw == Long.MIN_VALUE) return 0L
            val absRaw = abs(raw)
            val magnitudeMicroA = if (!halUsesMicroAmps && isLegacyMilliampHal && absRaw <= 9_500L) {
                absRaw * 1000L
            } else {
                absRaw
            }
            return if (raw < 0L) -magnitudeMicroA else magnitudeMicroA
        }

        fun mergeIntervals(intervals: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
            if (intervals.isEmpty()) return emptyList()
            val sorted = intervals.sortedBy { it.first }
            val merged = mutableListOf<Pair<Long, Long>>()
            var curStart = sorted[0].first
            var curEnd = sorted[0].second
            for (i in 1 until sorted.size) {
                val (s, e) = sorted[i]
                if (s <= curEnd) {
                    if (e > curEnd) curEnd = e
                } else {
                    if (curEnd > curStart) merged.add(curStart to curEnd)
                    curStart = s
                    curEnd = e
                }
            }
            if (curEnd > curStart) merged.add(curStart to curEnd)
            return merged
        }

        fun mergeIntervalsDuration(intervals: List<Pair<Long, Long>>): Long {
            return mergeIntervals(intervals).sumOf { (s, e) -> (e - s).coerceAtLeast(0L) }
        }

        fun healthLabel(healthCode: Int): String = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Degraded"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Normal"
        }

        fun thermalLabel(thermalStatus: Int): String = when (thermalStatus) {
            0 -> "Nominal"
            1 -> "Light"
            2 -> "Moderate"
            3 -> "Severe"
            4 -> "Critical"
            5, 6 -> "Emergency"
            else -> "Nominal"
        }
    }
}
