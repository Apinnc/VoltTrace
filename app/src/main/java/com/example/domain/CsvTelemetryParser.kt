package com.example.domain

import com.example.data.local.BatterySampleEntity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Deterministic parser for VoltTrace CSV telemetry exports (supports both v2.0
 * and v2.1+ headers including signed current/power and kernel boot clock columns).
 */
object CsvTelemetryParser {

    fun parse(csvContent: String): List<BatterySampleEntity> {
        val lines = csvContent
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.size < 2) return emptyList()

        val headers = splitCsvLine(lines.first()).mapIndexed { idx, col -> col.trim() to idx }.toMap()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val result = mutableListOf<BatterySampleEntity>()
        for (rowIdx in 1 until lines.size) {
            val cols = splitCsvLine(lines[rowIdx])
            fun get(name: String): String? = headers[name]?.let { cols.getOrNull(it)?.trim() }

            val tsStr = get("timestamp_utc") ?: continue
            val tsMs = runCatching { iso.parse(tsStr)?.time }.getOrNull() ?: continue
            val levelPct = get("level_pct")?.toIntOrNull() ?: continue
            val voltageMv = get("voltage_mv")?.toIntOrNull() ?: 0
            val tempC = get("temp_c")?.toFloatOrNull() ?: 0f
            val isCharging = get("is_charging").equals("true", ignoreCase = true)

            val signedMa = get("current_signed_ma")?.toDoubleOrNull()
            val signedPowerMw = get("power_signed_mw")?.toDoubleOrNull()
            val absMa = get("current_ma")?.toDoubleOrNull() ?: 0.0
            val canonicalSignedMa = when {
                signedMa != null -> signedMa
                signedPowerMw != null && voltageMv > 0 -> (signedPowerMw * 1000.0) / voltageMv.toDouble()
                else -> if (isCharging) abs(absMa) else -abs(absMa)
            }
            val currentMicroA = (canonicalSignedMa * 1000.0).roundToLong()

            val chargeCounterUah = get("charge_counter_uah")?.toLongOrNull() ?: 0L
            val elapsedRealtimeMs = get("elapsed_realtime_ms")?.toLongOrNull() ?: 0L
            val uptimeMs = get("uptime_ms")?.toLongOrNull() ?: 0L
            val pluggedType = get("plugged_type").orEmpty().ifBlank { "UNKNOWN" }
            val isScreenOn = get("screen_on").equals("true", ignoreCase = true)
            val brightnessPct = get("brightness_pct")?.toIntOrNull() ?: 0
            val refreshHz = get("refresh_hz")?.toFloatOrNull() ?: 60f
            val autoBrightness = get("auto_brightness").equals("true", ignoreCase = true)
            val transport = get("network_transport").orEmpty().ifBlank { "OFFLINE" }
            val cellularActive = get("cellular_active").equals("true", ignoreCase = true)
            val wifiEnabled = get("wifi_enabled").equals("true", ignoreCase = true)
            val wifiConnected = get("wifi_connected").equals("true", ignoreCase = true)
            val gpsEnabled = get("gps_enabled").equals("true", ignoreCase = true)
            val bluetoothEnabled = get("bluetooth_enabled").equals("true", ignoreCase = true)
            val airplaneModeOn = get("airplane_mode").equals("true", ignoreCase = true)
            val linkDownKbps = get("link_down_kbps")?.toIntOrNull() ?: 0
            val isMetered = get("metered_network").equals("true", ignoreCase = true)
            val thermalStatus = get("thermal_status")?.toIntOrNull() ?: 0
            val isPowerSave = get("power_save").equals("true", ignoreCase = true)
            val fgApp = get("foreground_app")?.takeIf { it.isNotBlank() }
            val source = get("source").orEmpty().ifBlank { "CSV" }
            val currentUnitVerified = get("current_unit_verified")?.let {
                !it.equals("false", ignoreCase = true) && it != "0"
            } ?: true

            result.add(
                BatterySampleEntity(
                    id = rowIdx.toLong(),
                    timestampMs = tsMs,
                    levelPct = levelPct,
                    voltageMv = voltageMv,
                    temperatureDeciC = (tempC * 10f).roundToInt(),
                    currentMicroA = currentMicroA,
                    chargeCounterMicroAh = chargeCounterUah,
                    isCharging = isCharging,
                    pluggedType = pluggedType,
                    healthCode = 2,
                    technology = "Li-ion",
                    isScreenOn = isScreenOn,
                    isPowerSaveMode = isPowerSave,
                    thermalStatus = thermalStatus,
                    networkTransport = transport,
                    screenBrightnessPct = brightnessPct,
                    displayRefreshRateHz = refreshHz,
                    displayAutoBrightness = autoBrightness,
                    gpsEnabled = gpsEnabled,
                    wifiEnabled = wifiEnabled,
                    wifiConnected = wifiConnected,
                    bluetoothEnabled = bluetoothEnabled,
                    cellularActive = cellularActive,
                    airplaneModeOn = airplaneModeOn,
                    linkDownKbps = linkDownKbps,
                    isMeteredNetwork = isMetered,
                    elapsedRealtimeMs = elapsedRealtimeMs,
                    uptimeMs = uptimeMs,
                    foregroundPackage = fgApp,
                    foregroundLabel = fgApp,
                    source = source,
                    currentUnitVerified = currentUnitVerified
                )
            )
        }
        return result
    }

    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }
}
