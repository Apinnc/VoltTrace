package com.example.domain

enum class AnalysisWindow(val label: String, val durationMs: Long?) {
    ONE_HOUR("1H", 1L * 60L * 60L * 1000L),
    SIX_HOURS("6H", 6L * 60L * 60L * 1000L),
    TWENTY_FOUR_HOURS("24H", 24L * 60L * 60L * 1000L),
    SEVEN_DAYS("7D", 7L * 24L * 60L * 60L * 1000L),
    ALL("All", null)
}

enum class HardwareComponentType(val displayName: String, val shortTag: String) {
    DISPLAY("Display", "DSP"),
    CELLULAR("Cellular Radio", "CELL"),
    WIFI("Wi-Fi Radio", "WIFI"),
    GPS("GPS / Location", "GPS"),
    BLUETOOTH("Bluetooth", "BT"),
    CPU_SYSTEM("CPU & System", "SYS")
}

enum class RootCauseCategory {
    DISPLAY_POWER,
    CELLULAR_RADIO,
    WIFI_RADIO,
    GPS_LOCATION,
    BLUETOOTH_RADIO,
    WAKELOCK_STANDBY,
    THERMAL_STRESS,
    HIGH_POWER_SPIKES,
    APP_CULPRIT,
    HEALTHY_PROFILE
}

enum class CauseSeverity {
    CRITICAL,
    ELEVATED,
    MODERATE,
    HEALTHY
}

enum class SettingsAction {
    USAGE_ACCESS,
    DISPLAY_SETTINGS,
    LOCATION_SETTINGS,
    WIFI_SETTINGS,
    BLUETOOTH_SETTINGS,
    NETWORK_SETTINGS,
    BATTERY_SAVER,
    BATTERY_OPTIMIZATION,
    APP_DETAILS,
    NONE
}

data class HardwareSubsystemStatus(
    val component: HardwareComponentType,
    val isActive: Boolean,
    val liveDetail: String,
    val liveEstimatedMw: Float,
    val settingsAction: SettingsAction
)

data class HardwareComponentAttribution(
    val component: HardwareComponentType,
    val attributedMah: Float,
    val attributedWh: Float,
    val sharePct: Float,
    val activeDurationMs: Long,
    val activeDutyPct: Float,
    val avgPowerMwWhenActive: Float,
    val isCurrentlyActive: Boolean,
    val liveDetail: String,
    val verdict: String,
    val severity: CauseSeverity,
    val settingsAction: SettingsAction,
    val forensicDetails: List<String> = emptyList()
)

data class SleepSessionSummary(
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val startLevelPct: Int,
    val endLevelPct: Int,
    val drainRatePctPerHour: Float,
    val mahLost: Float,
    val verdict: String,
    val severity: CauseSeverity,
    val activeRadiosDuringSleep: List<String>,
    val deepSleepMs: Long? = null,
    val cpuAwakeMs: Long? = null,
    val deepSleepPct: Float? = null
)

data class RootCauseFinding(
    val id: String,
    val category: RootCauseCategory,
    val severity: CauseSeverity,
    val title: String,
    val headlineMetric: String,
    val sharePct: Float,
    val explanation: String,
    val evidencePoints: List<String>,
    val recommendation: String,
    val actionLabel: String?,
    val settingsAction: SettingsAction,
    val targetPackage: String? = null
)

data class AppDrainAttribution(
    val packageName: String,
    val appLabel: String,
    val totalForegroundMs: Long,
    val wakeupCount: Int,
    val estimatedMah: Float,
    val sharePct: Float,
    val shareOfTotalDrainPct: Float = 0f,
    val avgPowerMw: Float,
    val drainRatePctPerHour: Float,
    val isHighIntensity: Boolean,
    val hasEnergyEstimate: Boolean = true,
    val isUniformTimeEstimate: Boolean = false
)

data class ProbeSessionResult(
    val durationSec: Int,
    val sampleCount: Int,
    val avgPowerMw: Float,
    val peakPowerMw: Float,
    val startVoltageMv: Int,
    val endVoltageMv: Int,
    val tempDeltaC: Float,
    val estimatedDrainPctPerHour: Float,
    val primaryHardwareLoad: String,
    val verdict: String
)

data class EnergyMeterSummary(
    val meterName: String,
    val meterType: String, // "MEASUREMENT" (ODPM rail) or "CONSUMER"
    val deltaMicroWattSec: Long,
    val deltaMWh: Float,
    val estimatedMah: Float,
    val startEnergyMicroWattSec: Long,
    val endEnergyMicroWattSec: Long,
    val sampleCount: Int
)

data class DrainEpisode(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val chargeLostMah: Float,
    val counterMeasured: Boolean,
    val startCounterMicroAh: Long,
    val endCounterMicroAh: Long,
    val startLevelPct: Int,
    val endLevelPct: Int,
    val levelDropPct: Int,
    val drainRateMahPerHour: Float,
    val cpuAwakeMs: Long?,
    val deepSleepMs: Long?,
    val awakePct: Float?,
    val peakPowerMw: Float,
    val peakPowerTimestampMs: Long?,
    val minTempC: Float,
    val maxTempC: Float,
    val screenSummary: String,
    val observedActivity: List<String>,
    val whatRemainsUnknown: List<String>,
    val sourceReadings: List<String>,
    val severity: CauseSeverity,
    val counterMeasuredMah: Float = if (counterMeasured) chargeLostMah else 0f,
    val estimatedMah: Float = if (counterMeasured) 0f else chargeLostMah,
    val chargeEvidenceSource: ChargeEvidenceSource =
        if (counterMeasured) ChargeEvidenceSource.COUNTER_MEASURED else ChargeEvidenceSource.ESTIMATED
)

data class BatteryAnalysisReport(
    val window: AnalysisWindow,
    val sampleCount: Int,
    val spanDurationMs: Long,
    val totalDischargedPct: Float,
    val totalDischargedMah: Float,
    val overallDrainRatePctPerHour: Float,
    val screenOnDrainRatePctPerHour: Float,
    val screenOffDrainRatePctPerHour: Float,
    val wifiDrainRatePctPerHour: Float,
    val cellularDrainRatePctPerHour: Float,
    val avgPowerMw: Float,
    val peakPowerMw: Float,
    val avgTempC: Float,
    val peakTempC: Float,
    val minVoltageMv: Int,
    val maxVoltageMv: Int,
    val screenOnDurationMs: Long,
    val screenOffDurationMs: Long,
    val chargingDurationMs: Long = 0L,
    val mixedTransitionDurationMs: Long = 0L,
    val plugTransitionDurationMs: Long = 0L,
    val plugTransitionDischargedMah: Float = 0f,
    val plugTransitionChargedMah: Float = 0f,
    val gapDurationMs: Long = 0L,
    val totalChargedMah: Float = 0f,
    val netChargeDeltaMah: Float = totalChargedMah - totalDischargedMah,
    val peakDischargePowerMw: Float = 0f,
    val peakDischargePowerTimestampMs: Long? = null,
    val peakChargePowerMw: Float = 0f,
    val peakChargePowerTimestampMs: Long? = null,
    val peakTempTimestampMs: Long? = null,
    val peakTempWasCharging: Boolean = false,
    val totalDeepSleepMs: Long = 0L,
    val totalStandbyAwakeMs: Long = 0L,
    val totalUnpluggedElapsedMs: Long = 0L,
    val totalUnpluggedAwakeMs: Long = 0L,
    val totalUnpluggedDeepSleepMs: Long = 0L,
    val chargeCounterMeasured: Boolean = false,
    val counterMeasuredDischargedMah: Float = if (chargeCounterMeasured) totalDischargedMah else 0f,
    val estimatedDischargedMah: Float = if (chargeCounterMeasured) 0f else totalDischargedMah,
    val chargeEvidenceSource: ChargeEvidenceSource =
        if (chargeCounterMeasured) ChargeEvidenceSource.COUNTER_MEASURED else ChargeEvidenceSource.ESTIMATED,
    val missingClockSampleCount: Int = 0,
    val uncertainLegacyUnitSampleCount: Int = 0,
    val hardwareAttributionMode: String = "Modeled Component Split (0 Hardware Energy Meters Recorded in Window)",
    val discoveredEnergyMeters: List<EnergyMeterSummary> = emptyList(),
    val drainEpisodes: List<DrainEpisode> = emptyList(),
    val estimatedRemainingHours: Float?,
    val efficiencyScore: Int,
    val hardwareBreakdown: List<HardwareComponentAttribution>,
    val sleepSessions: List<SleepSessionSummary> = emptyList(),
    val findings: List<RootCauseFinding>,
    val appCulprits: List<AppDrainAttribution>,
    val hasLongitudinalData: Boolean
)
