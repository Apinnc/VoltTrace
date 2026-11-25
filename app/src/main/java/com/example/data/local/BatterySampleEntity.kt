package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.math.abs

@Entity(
    tableName = "battery_samples",
    indices = [Index(value = ["timestampMs"])]
)
data class BatterySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val levelPct: Int,
    val voltageMv: Int,
    val temperatureDeciC: Int,
    val currentMicroA: Long,
    val chargeCounterMicroAh: Long,
    val isCharging: Boolean,
    val pluggedType: String,
    val healthCode: Int,
    val technology: String,
    val isScreenOn: Boolean,
    val isPowerSaveMode: Boolean,
    val thermalStatus: Int,
    val networkTransport: String,
    val screenBrightnessPct: Int,
    val displayRefreshRateHz: Float = 60f,
    val displayAutoBrightness: Boolean = false,
    val gpsEnabled: Boolean = false,
    val wifiEnabled: Boolean = false,
    val wifiConnected: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val cellularActive: Boolean = false,
    val airplaneModeOn: Boolean = false,
    val linkDownKbps: Int = 0,
    val isMeteredNetwork: Boolean = false,
    val elapsedRealtimeMs: Long = 0L,
    val uptimeMs: Long = 0L,
    val foregroundPackage: String?,
    val foregroundLabel: String?,
    val source: String
) {
    val temperatureCelsius: Float
        get() = temperatureDeciC / 10f

    val voltageVolts: Float
        get() = voltageMv / 1000f

    /**
     * Normalized current magnitude in milliamperes (mA).
     *
     * `currentMicroA` is stored in Android's canonical signed microampere (µA) unit so valid
     * low-power standby readings (e.g. 8,000 µA = 8 mA = 0.032 W at 4 V) are preserved accurately.
     * Legacy corrupted DB rows where a trickle µA reading was previously over-multiplied by 1000
     * (>10.5 A is physically impossible on a single-cell smartphone battery) are still healed safely.
     */
    val currentAbsMa: Float
        get() {
            val raw = abs(currentMicroA)
            return when {
                raw == 0L -> 0f
                raw > 10_500_000L -> raw / 1_000_000f
                else -> raw / 1000f
            }
        }

    /**
     * Signed current in mA: positive (+) when charging into battery, negative (-) when discharging.
     */
    val currentSignedMa: Float
        get() = if (isCharging) currentAbsMa else -currentAbsMa

    /**
     * Instantaneous electrical power magnitude in milliwatts (mW).
     */
    val powerMw: Float
        get() = (currentAbsMa * voltageMv) / 1000f

    /**
     * Signed electrical power in milliwatts (mW): + charging, - discharging.
     */
    val powerSignedMw: Float
        get() = if (isCharging) powerMw else -powerMw

    val powerWatts: Float
        get() = powerMw / 1000f

    val powerSignedWatts: Float
        get() = powerSignedMw / 1000f
}
