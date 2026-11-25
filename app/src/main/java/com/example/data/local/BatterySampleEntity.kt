package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale
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
    val source: String,
    val currentUnitVerified: Boolean = true
) {
    val temperatureCelsius: Float
        get() = temperatureDeciC / 10f

    val voltageVolts: Float
        get() = voltageMv / 1000f

    /**
     * True when the recorded power connection state confirms an external power source
     * (AC, USB, Wireless, or Dock), regardless of whether charging is active or paused.
     */
    val isConnectedToExternalPower: Boolean
        get() = pluggedType.trim().uppercase(Locale.US) in EXTERNAL_POWER_PLUG_TYPES

    /**
     * True when the phone is explicitly recorded on battery with no cable/charger attached.
     */
    val isConfirmedUnplugged: Boolean
        get() = !isCharging && pluggedType.trim().uppercase(Locale.US) in UNPLUGGED_PLUG_TYPES

    /**
     * True when historical/imported telemetry did not record a cable connection state.
     */
    val hasUnknownConnectionState: Boolean
        get() = pluggedType.isBlank() || pluggedType.trim().equals("UNKNOWN", ignoreCase = true)

    /**
     * True when this row originated from a pre-v8 database (or unverified legacy import)
     * where v7's pre-calibration 1000x multiplier could have stored a low-power standby
     * reading (e.g. -8,000 µA = 8 mA) as -8,000,000 µA (32 W at 4 V). Because magnitude
     * alone cannot distinguish an old falsely-scaled row from a genuine high-current draw,
     * unverified rows in this range are treated as unit-uncertain for peaks, alerts, and
     * conclusions while retaining the original stored data.
     */
    val hasUncertainLegacyCurrentUnit: Boolean
        get() = !currentUnitVerified &&
            currentMicroA != Long.MIN_VALUE &&
            abs(currentMicroA) in 500_000L..10_500_000L

    /**
     * Normalized current magnitude in milliamperes (mA).
     *
     * `currentMicroA` is stored in Android's canonical signed microampere (µA) unit so valid
     * low-power standby readings (e.g. 8,000 µA = 8 mA = 0.032 W at 4 V) are preserved accurately.
     * Pre-v8 legacy rows where a trickle µA reading was over-multiplied by 1000 above 10.5 A are
     * still healed safely when `currentUnitVerified` is false or above physical single-cell limits.
     */
    val currentAbsMa: Float
        get() {
            if (currentMicroA == 0L || currentMicroA == Long.MIN_VALUE) return 0f
            val raw = abs(currentMicroA)
            return when {
                raw > 10_500_000L -> raw / 1_000_000f
                else -> raw / 1000f
            }
        }

    /**
     * Signed current in mA preserving the actual measured sign of `currentMicroA`
     * independently of OS charging status or plug connection.
     */
    val currentSignedMa: Float
        get() = when {
            currentMicroA == 0L || currentMicroA == Long.MIN_VALUE -> 0f
            currentMicroA < 0L -> -currentAbsMa
            else -> currentAbsMa
        }

    /**
     * Instantaneous electrical power magnitude in milliwatts (mW).
     */
    val powerMw: Float
        get() = (currentAbsMa * voltageMv) / 1000f

    /**
     * Signed electrical power in milliwatts (mW) preserving the actual sign of `currentMicroA`.
     */
    val powerSignedMw: Float
        get() = when {
            currentMicroA == 0L || currentMicroA == Long.MIN_VALUE -> 0f
            currentMicroA < 0L -> -powerMw
            else -> powerMw
        }

    val powerWatts: Float
        get() = powerMw / 1000f

    val powerSignedWatts: Float
        get() = powerSignedMw / 1000f

    companion object {
        private val EXTERNAL_POWER_PLUG_TYPES = setOf("AC", "USB", "WIRELESS", "DOCK")
        private val UNPLUGGED_PLUG_TYPES = setOf("BATTERY", "NONE", "UNPLUGGED")
    }
}
