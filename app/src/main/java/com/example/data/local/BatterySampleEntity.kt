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
     * Standard Android HALs (including modern flagship OEM HALs) report microamperes (µA),
     * while some older OEM HALs report milliamperes directly (1..9,500 mA).
     * Also heals legacy DB rows where sub-25 mA µA trickle readings (e.g. 14,784 µA)
     * were previously over-multiplied by 1000 (>10.5 A is physically impossible on a
     * single-cell smartphone battery).
     */
    val currentAbsMa: Float
        get() {
            val raw = abs(currentMicroA)
            return when {
                raw == 0L -> 0f
                raw > 10_500_000L -> raw / 1_000_000f
                raw in 1L..9_500L -> raw.toFloat()
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
