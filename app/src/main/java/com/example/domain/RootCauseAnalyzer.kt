package com.example.domain

import com.example.data.hardware.ObservedAppUsage
import com.example.data.local.AppDrainEventEntity
import com.example.data.local.BatterySampleEntity
import com.example.data.local.EnergyMeterSnapshotEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure Kotlin hardware-attribution and root-cause diagnostic engine.
 *
 * Uses Android power-profile curves for Display (brightness + refresh rate),
 * Cellular radio (active vs idle standby), Wi-Fi radio (connected vs idle scan),
 * GPS/GNSS engine, and Bluetooth radio, calibrated and bounded against actual
 * electrical discharge measured by BatteryManager (charge counter, instantaneous
 * current, voltage, and SOC delta).
 */
object RootCauseAnalyzer {

    /**
     * Real-time instantaneous subsystem breakdown for the Live Hardware Monitor.
     */
    fun estimateLiveSubsystems(sample: BatterySampleEntity): List<HardwareSubsystemStatus> {
        val raw = estimateInstantHardwareMw(sample)
        val totalMeasured = sample.powerMw
        val rawHardwareSum = raw.values.sum()
        // If device reports real instantaneous power > 0, scale hardware estimates cleanly
        // so individual components stay grounded in actual measured wattage.
        val scale = if (!sample.isCharging && totalMeasured > 40f && rawHardwareSum > totalMeasured * 0.92f) {
            (totalMeasured * 0.92f) / rawHardwareSum
        } else {
            1f
        }

        val displayMw = (raw[HardwareComponentType.DISPLAY] ?: 0f) * scale
        val cellularMw = (raw[HardwareComponentType.CELLULAR] ?: 0f) * scale
        val wifiMw = (raw[HardwareComponentType.WIFI] ?: 0f) * scale
        val gpsMw = (raw[HardwareComponentType.GPS] ?: 0f) * scale
        val btMw = (raw[HardwareComponentType.BLUETOOTH] ?: 0f) * scale

        val displayDetail = if (sample.isScreenOn) {
            val mode = if (sample.displayAutoBrightness) "Auto" else "Manual"
            "${sample.screenBrightnessPct}% ($mode) · ${sample.displayRefreshRateHz.roundToInt()} Hz"
        } else {
            "Screen Off"
        }

        val cellularDetail = when {
            sample.airplaneModeOn -> "Airplane Mode (Off)"
            sample.cellularActive -> "Active Mobile Data"
            else -> "Standby / Voice Idle"
        }

        val wifiDetail = when {
            sample.wifiConnected -> "Connected"
            sample.wifiEnabled -> "Enabled (Idle Scan)"
            else -> "Radio Off"
        }

        val gpsDetail = if (sample.gpsEnabled) "Location Setting ON (Standby Ready)" else "Location Off"
        val btDetail = if (sample.bluetoothEnabled) "Radio Active" else "Radio Off"

        return listOf(
            HardwareSubsystemStatus(
                component = HardwareComponentType.DISPLAY,
                isActive = sample.isScreenOn,
                liveDetail = displayDetail,
                liveEstimatedMw = displayMw,
                settingsAction = SettingsAction.DISPLAY_SETTINGS
            ),
            HardwareSubsystemStatus(
                component = HardwareComponentType.CELLULAR,
                isActive = !sample.airplaneModeOn,
                liveDetail = cellularDetail,
                liveEstimatedMw = cellularMw,
                settingsAction = SettingsAction.NETWORK_SETTINGS
            ),
            HardwareSubsystemStatus(
                component = HardwareComponentType.WIFI,
                isActive = sample.wifiEnabled || sample.wifiConnected,
                liveDetail = wifiDetail,
                liveEstimatedMw = wifiMw,
                settingsAction = SettingsAction.WIFI_SETTINGS
            ),
            HardwareSubsystemStatus(
                component = HardwareComponentType.GPS,
                isActive = sample.gpsEnabled,
                liveDetail = gpsDetail,
                liveEstimatedMw = gpsMw,
                settingsAction = SettingsAction.LOCATION_SETTINGS
            ),
            HardwareSubsystemStatus(
                component = HardwareComponentType.BLUETOOTH,
                isActive = sample.bluetoothEnabled,
                liveDetail = btDetail,
                liveEstimatedMw = btMw,
                settingsAction = SettingsAction.BLUETOOTH_SETTINGS
            )
        )
    }

    /**
     * Baseline hardware power draw model (in mW) derived from Android power_profile.xml
     * physical curves before normalization against actual measured battery discharge.
     */
    internal fun estimateInstantHardwareMw(sample: BatterySampleEntity): Map<HardwareComponentType, Float> {
        val displayMw = if (sample.isScreenOn) {
            val brightnessFactor = sample.screenBrightnessPct.coerceIn(0, 100) / 100f
            val refreshMultiplier = when {
                sample.displayRefreshRateHz >= 115f -> 1.32f
                sample.displayRefreshRateHz >= 85f -> 1.16f
                else -> 1.0f
            }
            (220f + 880f * brightnessFactor) * refreshMultiplier
        } else {
            0f
        }

        val cellularMw = when {
            sample.airplaneModeOn -> 0f
            sample.cellularActive -> if (sample.isScreenOn) 380f else 190f
            else -> 28f // Baseband paging & cell-tower registration standby
        }

        val wifiMw = when {
            sample.wifiConnected -> if (sample.isScreenOn) 135f else 48f
            sample.wifiEnabled -> 42f // Periodic SSID scanning while disconnected
            else -> 0f
        }

        // Android exposes whether the Location/GPS provider setting is ON, not third-party
        // GNSS satellite lock duration. Use a conservative standby-readiness weight so
        // leaving Location toggled ON does not over-attribute active satellite power.
        val gpsMw = if (sample.gpsEnabled) {
            if (sample.isScreenOn) 18f else 6f
        } else {
            0f
        }

        val btMw = if (sample.bluetoothEnabled) {
            if (sample.isScreenOn) 55f else 24f
        } else {
            0f
        }

        return mapOf(
            HardwareComponentType.DISPLAY to displayMw,
            HardwareComponentType.CELLULAR to cellularMw,
            HardwareComponentType.WIFI to wifiMw,
            HardwareComponentType.GPS to gpsMw,
            HardwareComponentType.BLUETOOTH to btMw
        )
    }

    fun analyze(
        samples: List<BatterySampleEntity>,
        latestSample: BatterySampleEntity?,
        appEvents: List<AppDrainEventEntity>,
        liveAppUsage: List<ObservedAppUsage>,
        window: AnalysisWindow,
        ratedCapacityMah: Int,
        thermalAlertCelsius: Float,
        energyMeterSnapshots: List<EnergyMeterSnapshotEntity> = emptyList()
    ): BatteryAnalysisReport {
        val rawSorted = samples.sortedBy { it.timestampMs }
        val missingClockSampleCount = rawSorted.count { it.elapsedRealtimeMs <= 0L || it.uptimeMs <= 0L }
        // Deduplicate near-simultaneous (<2s) identical-state rows (e.g., LIVE + WORK race)
        val sorted = buildList<BatterySampleEntity> {
            for (s in rawSorted) {
                val prev = lastOrNull()
                if (prev != null &&
                    (s.timestampMs - prev.timestampMs) < 2_000L &&
                    s.isCharging == prev.isCharging &&
                    s.isScreenOn == prev.isScreenOn
                ) {
                    continue
                }
                add(s)
            }
        }
        val anchorSample = latestSample ?: sorted.lastOrNull()

        if (sorted.size < 2) {
            return buildInitialSnapshotReport(
                sample = anchorSample,
                liveAppUsage = liveAppUsage,
                window = window,
                ratedCapacityMah = ratedCapacityMah,
                thermalAlertCelsius = thermalAlertCelsius
            )
        }

        val spanMs = (sorted.last().timestampMs - sorted.first().timestampMs).coerceAtLeast(1L)

        var totalDischargeMs = 0L
        var totalDischargedMah = 0f
        var totalDischargedPct = 0f
        var counterBackedDischargeIntervals = 0
        var totalDischargeIntervals = 0

        var screenOnDischargeMs = 0L
        var screenOnDischargedPct = 0f
        var screenOffDischargeMs = 0L
        var screenOffDischargedPct = 0f
        var mixedTransitionDurationMs = 0L
        var chargingDurationMs = 0L
        var gapDurationMs = 0L
        var totalChargedMah = 0f

        var totalDeepSleepMs = 0L
        var totalStandbyAwakeMs = 0L
        var totalUnpluggedElapsedMs = 0L
        var totalUnpluggedAwakeMs = 0L
        var totalUnpluggedDeepSleepMs = 0L

        var wifiDischargeMs = 0L
        var wifiDischargedPct = 0f
        var cellularDischargeMs = 0L
        var cellularDischargedPct = 0f

        var highBrightnessMs = 0L
        var highRefreshMs = 0L
        var hotDurationMs = 0L
        var wifiIdleScanMs = 0L
        var gpsStandbyMs = 0L
        var btStandbyMs = 0L
        var meteredMs = 0L
        var cellularActiveDataMs = 0L
        val linkSpeedSamplesKbps = mutableListOf<Int>()
        var spikeIntervalCount = 0
        var powerWeightedSum = 0f

        var peakDischargePowerMw = 0f
        var peakDischargePowerTimestampMs: Long? = null
        var peakChargePowerMw = 0f
        var peakChargePowerTimestampMs: Long? = null

        val componentMah = mutableMapOf<HardwareComponentType, Float>().apply {
            HardwareComponentType.entries.forEach { put(it, 0f) }
        }
        val componentWh = mutableMapOf<HardwareComponentType, Float>().apply {
            HardwareComponentType.entries.forEach { put(it, 0f) }
        }
        val componentActiveMs = mutableMapOf<HardwareComponentType, Long>().apply {
            HardwareComponentType.entries.forEach { put(it, 0L) }
        }
        val componentPowerTimeSum = mutableMapOf<HardwareComponentType, Float>().apply {
            HardwareComponentType.entries.forEach { put(it, 0f) }
        }

        // Seed peak charge / discharge power directly from individual samples with exact timestamps
        for (s in sorted) {
            if (s.isCharging) {
                if (s.powerMw > peakChargePowerMw) {
                    peakChargePowerMw = s.powerMw
                    peakChargePowerTimestampMs = s.timestampMs
                }
            } else {
                if (s.powerMw > peakDischargePowerMw) {
                    peakDischargePowerMw = s.powerMw
                    peakDischargePowerTimestampMs = s.timestampMs
                }
            }
        }

        data class PendingDischargeInterval(
            val a: BatterySampleEntity,
            val b: BatterySampleEntity,
            val dtMs: Long,
            val dtHours: Float,
            val avgV: Float,
            val counterBacked: Boolean,
            val rawMah: Float,
            val rawWh: Float,
            val rawPct: Float,
            val measuredPowerMw: Float,
            val effectivePowerMw: Float,
            val hwEstimate: Map<HardwareComponentType, Float>,
            val hwSumMw: Float,
            val hasValidBootClocks: Boolean,
            val intervalDeepSleepMs: Long,
            val intervalCpuAwakeMs: Long
        )

        val pendingPeriod = mutableListOf<PendingDischargeInterval>()

        fun flushDischargePeriod() {
            if (pendingPeriod.isEmpty()) return

            val isNonCounterPeriod = !pendingPeriod.first().counterBacked
            val periodScale = if (isNonCounterPeriod) {
                val periodStartPct = pendingPeriod.first().a.levelPct
                val periodEndPct = pendingPeriod.last().b.levelPct
                val periodGaugeDropPct = (periodStartPct - periodEndPct).coerceAtLeast(0).toFloat()
                val periodRawPct = pendingPeriod.sumOf { it.rawPct.toDouble() }.toFloat()
                if (periodGaugeDropPct > periodRawPct && periodRawPct > 0.0001f) {
                    (periodGaugeDropPct / periodRawPct).coerceIn(1f, 25f)
                } else {
                    1f
                }
            } else {
                1f
            }

            val periodTotalDtMs = pendingPeriod.sumOf { it.dtMs }.coerceAtLeast(1L)
            val fallbackPeriodGaugeDropPct = if (isNonCounterPeriod) {
                val periodRawPct = pendingPeriod.sumOf { it.rawPct.toDouble() }.toFloat()
                val gaugeDrop = (pendingPeriod.first().a.levelPct - pendingPeriod.last().b.levelPct)
                    .coerceAtLeast(0)
                    .toFloat()
                if (gaugeDrop > 0f && periodRawPct <= 0.0001f) gaugeDrop else 0f
            } else {
                0f
            }

            for (iv in pendingPeriod) {
                val intervalMah = if (fallbackPeriodGaugeDropPct > 0f) {
                    val shareDt = iv.dtMs.toFloat() / periodTotalDtMs.toFloat()
                    ((fallbackPeriodGaugeDropPct * shareDt) / 100f) * ratedCapacityMah.coerceAtLeast(1000)
                } else {
                    iv.rawMah * periodScale
                }
                val intervalWh = (intervalMah * iv.avgV) / 1000f
                val intervalPct = if (fallbackPeriodGaugeDropPct > 0f) {
                    fallbackPeriodGaugeDropPct * (iv.dtMs.toFloat() / periodTotalDtMs.toFloat())
                } else {
                    iv.rawPct * periodScale
                }
                val effectivePowerMw = if (iv.measuredPowerMw > 25f) {
                    iv.measuredPowerMw
                } else {
                    (intervalWh * 1000f / iv.dtHours).coerceIn(30f, 12_000f)
                }

                totalDischargedMah += intervalMah
                totalDischargedPct += intervalPct
                powerWeightedSum += effectivePowerMw * iv.dtHours
                if (effectivePowerMw >= 3500f) spikeIntervalCount++

                when {
                    iv.a.isScreenOn && iv.b.isScreenOn -> {
                        screenOnDischargeMs += iv.dtMs
                        screenOnDischargedPct += intervalPct
                    }
                    !iv.a.isScreenOn && !iv.b.isScreenOn -> {
                        screenOffDischargeMs += iv.dtMs
                        screenOffDischargedPct += intervalPct
                        if (iv.hasValidBootClocks) {
                            totalDeepSleepMs += iv.intervalDeepSleepMs
                            totalStandbyAwakeMs += iv.intervalCpuAwakeMs
                        }
                    }
                    else -> {
                        mixedTransitionDurationMs += iv.dtMs
                    }
                }

                if (iv.a.wifiConnected && iv.b.wifiConnected) {
                    wifiDischargeMs += iv.dtMs
                    wifiDischargedPct += intervalPct
                } else if (iv.a.cellularActive && iv.b.cellularActive) {
                    cellularDischargeMs += iv.dtMs
                    cellularDischargedPct += intervalPct
                }

                // Decompose interval energy across the 5 hardware subsystems + CPU/System residual
                val maxHardwareBudgetMw = effectivePowerMw * 0.85f
                val hwScale = if (iv.hwSumMw > maxHardwareBudgetMw && iv.hwSumMw > 0f) {
                    maxHardwareBudgetMw / iv.hwSumMw
                } else {
                    1f
                }

                var assignedHwMah = 0f
                var assignedHwWh = 0f
                for ((comp, baseMw) in iv.hwEstimate) {
                    if (baseMw <= 0f) continue
                    val attributedMw = baseMw * hwScale
                    val share = (attributedMw / effectivePowerMw.coerceAtLeast(1f)).coerceIn(0f, 0.85f)
                    val compMah = intervalMah * share
                    val compWh = intervalWh * share
                    componentMah[comp] = (componentMah[comp] ?: 0f) + compMah
                    componentWh[comp] = (componentWh[comp] ?: 0f) + compWh
                    componentPowerTimeSum[comp] =
                        (componentPowerTimeSum[comp] ?: 0f) + (attributedMw * iv.dtHours)
                    assignedHwMah += compMah
                    assignedHwWh += compWh
                }

                val cpuResidualMah = (intervalMah - assignedHwMah).coerceAtLeast(0f)
                val cpuResidualWh = (intervalWh - assignedHwWh).coerceAtLeast(0f)
                val cpuResidualMw = (effectivePowerMw - (iv.hwSumMw * hwScale)).coerceAtLeast(25f)
                componentMah[HardwareComponentType.CPU_SYSTEM] =
                    (componentMah[HardwareComponentType.CPU_SYSTEM] ?: 0f) + cpuResidualMah
                componentWh[HardwareComponentType.CPU_SYSTEM] =
                    (componentWh[HardwareComponentType.CPU_SYSTEM] ?: 0f) + cpuResidualWh
                componentPowerTimeSum[HardwareComponentType.CPU_SYSTEM] =
                    (componentPowerTimeSum[HardwareComponentType.CPU_SYSTEM] ?: 0f) + (cpuResidualMw * iv.dtHours)
            }

            pendingPeriod.clear()
        }

        for (i in 0 until sorted.lastIndex) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val dtMs = b.timestampMs - a.timestampMs
            if (dtMs <= 0L) continue
            // Account for multi-hour device power-off / collector-stopped gaps (> 6h) explicitly
            if (dtMs > 6L * 60L * 60L * 1000L) {
                flushDischargePeriod()
                gapDurationMs += dtMs
                continue
            }
            val dtHours = dtMs / 3_600_000f

            // Track total active duration across all valid intervals for duty metrics
            val avgV = ((a.voltageVolts + b.voltageVolts) / 2f).coerceIn(3.2f, 4.5f)
            if (a.isScreenOn && b.isScreenOn) {
                componentActiveMs[HardwareComponentType.DISPLAY] =
                    (componentActiveMs[HardwareComponentType.DISPLAY] ?: 0L) + dtMs
                if (a.screenBrightnessPct >= 70) highBrightnessMs += dtMs
                if (a.displayRefreshRateHz >= 90f) highRefreshMs += dtMs
            } else if (a.isScreenOn != b.isScreenOn) {
                // Mixed transition interval: split screen-on active duty evenly
                componentActiveMs[HardwareComponentType.DISPLAY] =
                    (componentActiveMs[HardwareComponentType.DISPLAY] ?: 0L) + (dtMs / 2L)
            }
            if (!a.airplaneModeOn) {
                componentActiveMs[HardwareComponentType.CELLULAR] =
                    (componentActiveMs[HardwareComponentType.CELLULAR] ?: 0L) + dtMs
                if (a.cellularActive) cellularActiveDataMs += dtMs
            }
            if (a.wifiEnabled || a.wifiConnected) {
                componentActiveMs[HardwareComponentType.WIFI] =
                    (componentActiveMs[HardwareComponentType.WIFI] ?: 0L) + dtMs
                if (a.wifiEnabled && !a.wifiConnected) wifiIdleScanMs += dtMs
            }
            if (a.gpsEnabled) {
                componentActiveMs[HardwareComponentType.GPS] =
                    (componentActiveMs[HardwareComponentType.GPS] ?: 0L) + dtMs
                if (!a.isScreenOn && !b.isScreenOn) gpsStandbyMs += dtMs
            }
            if (a.bluetoothEnabled) {
                componentActiveMs[HardwareComponentType.BLUETOOTH] =
                    (componentActiveMs[HardwareComponentType.BLUETOOTH] ?: 0L) + dtMs
                if (!a.isScreenOn && !b.isScreenOn) btStandbyMs += dtMs
            }
            if (a.isMeteredNetwork) meteredMs += dtMs
            if (a.linkDownKbps > 0) linkSpeedSamplesKbps.add(a.linkDownKbps)
            componentActiveMs[HardwareComponentType.CPU_SYSTEM] =
                (componentActiveMs[HardwareComponentType.CPU_SYSTEM] ?: 0L) + dtMs

            if (a.temperatureCelsius >= thermalAlertCelsius || b.temperatureCelsius >= thermalAlertCelsius || a.thermalStatus >= 2) {
                hotDurationMs += dtMs
            }

            // Explicitly account for charging intervals and exclude them from discharge attribution
            if (a.isCharging || b.isCharging) {
                flushDischargePeriod()
                chargingDurationMs += dtMs
                val chargedMah = when {
                    a.chargeCounterMicroAh > 0L && b.chargeCounterMicroAh > a.chargeCounterMicroAh -> {
                        ((b.chargeCounterMicroAh - a.chargeCounterMicroAh) / 1000f).coerceIn(0f, 6000f)
                    }
                    b.levelPct > a.levelPct -> ((b.levelPct - a.levelPct) / 100f) * ratedCapacityMah
                    else -> 0f
                }
                totalChargedMah += chargedMah
                continue
            }

            totalDischargeMs += dtMs
            totalDischargeIntervals++
            val measuredPowerMw = averagePositive(a.powerMw, b.powerMw)
            val hwEstimate = if (a.isScreenOn == b.isScreenOn) {
                estimateInstantHardwareMw(a)
            } else {
                // Blend start & end profiles during mixed screen-state transitions
                val hwA = estimateInstantHardwareMw(a)
                val hwB = estimateInstantHardwareMw(b)
                HardwareComponentType.entries.associateWith { type ->
                    ((hwA[type] ?: 0f) + (hwB[type] ?: 0f)) / 2f
                }
            }
            val hwSumMw = hwEstimate.values.sum()

            // Determine electrical energy consumed in this discharging interval;
            // non-counter-backed intervals are reconciled against whole-number gauge drop ONCE per continuous period.
            val counterBacked = a.chargeCounterMicroAh > 0L && b.chargeCounterMicroAh > 0L
            if (counterBacked) counterBackedDischargeIntervals++
            if (pendingPeriod.isNotEmpty() && pendingPeriod.first().counterBacked != counterBacked) {
                flushDischargePeriod()
            }

            val intervalMah = computeIntervalMah(
                a = a,
                b = b,
                dtHours = dtHours,
                avgVolts = avgV,
                measuredPowerMw = measuredPowerMw,
                fallbackHwMw = hwSumMw,
                ratedCapacityMah = ratedCapacityMah
            )
            val intervalWh = (intervalMah * avgV) / 1000f
            val intervalPct = (intervalMah / ratedCapacityMah.coerceAtLeast(1000)) * 100f
            val effectivePowerMw = if (measuredPowerMw > 25f) {
                measuredPowerMw
            } else {
                (intervalWh * 1000f / dtHours).coerceIn(30f, 12_000f)
            }

            // Track kernel deep sleep vs CPU awake across EVERY same-boot discharging interval
            var intervalDeepSleepMs = 0L
            var intervalCpuAwakeMs = 0L
            var hasValidBootClocks = false
            if (a.elapsedRealtimeMs > 0L && b.elapsedRealtimeMs > a.elapsedRealtimeMs &&
                a.uptimeMs > 0L && b.uptimeMs >= a.uptimeMs
            ) {
                val dtElapsed = b.elapsedRealtimeMs - a.elapsedRealtimeMs
                // Sanity check against reboot: elapsed delta should closely match wall-clock dt
                if (abs(dtElapsed - dtMs) <= max(60_000L, dtMs / 2L)) {
                    val dtAwake = (b.uptimeMs - a.uptimeMs).coerceIn(0L, dtElapsed)
                    val dtSleep = (dtElapsed - dtAwake).coerceAtLeast(0L)
                    totalUnpluggedElapsedMs += dtElapsed
                    totalUnpluggedAwakeMs += dtAwake
                    totalUnpluggedDeepSleepMs += dtSleep
                    intervalDeepSleepMs = dtSleep
                    intervalCpuAwakeMs = dtAwake
                    hasValidBootClocks = true
                }
            }

            pendingPeriod.add(
                PendingDischargeInterval(
                    a = a,
                    b = b,
                    dtMs = dtMs,
                    dtHours = dtHours,
                    avgV = avgV,
                    counterBacked = counterBacked,
                    rawMah = intervalMah,
                    rawWh = intervalWh,
                    rawPct = intervalPct,
                    measuredPowerMw = measuredPowerMw,
                    effectivePowerMw = effectivePowerMw,
                    hwEstimate = hwEstimate,
                    hwSumMw = hwSumMw,
                    hasValidBootClocks = hasValidBootClocks,
                    intervalDeepSleepMs = intervalDeepSleepMs,
                    intervalCpuAwakeMs = intervalCpuAwakeMs
                )
            )
        }
        flushDischargePeriod()

        val totalDischargeHours = (totalDischargeMs / 3_600_000f).coerceAtLeast(0.0001f)
        val hasRealDischarge = totalDischargeMs >= 10_000L && totalDischargedMah > 0.001f

        val overallDrainPctHr = if (hasRealDischarge) {
            (totalDischargedPct / totalDischargeHours).coerceIn(0f, 95f)
        } else {
            anchorSample?.let { estimateRateFromLiveSample(it, ratedCapacityMah) } ?: 0f
        }

        val screenOnRatePctHr = if (screenOnDischargeMs >= 10_000L) {
            (screenOnDischargedPct / (screenOnDischargeMs / 3_600_000f)).coerceIn(0f, 95f)
        } else 0f

        val screenOffRatePctHr = if (screenOffDischargeMs >= 5L * 60_000L) {
            (screenOffDischargedPct / (screenOffDischargeMs / 3_600_000f)).coerceIn(0f, 40f)
        } else 0f

        val wifiRatePctHr = if (wifiDischargeMs >= 30_000L) {
            (wifiDischargedPct / (wifiDischargeMs / 3_600_000f)).coerceIn(0f, 95f)
        } else 0f

        val cellularRatePctHr = if (cellularDischargeMs >= 30_000L) {
            (cellularDischargedPct / (cellularDischargeMs / 3_600_000f)).coerceIn(0f, 95f)
        } else 0f

        val avgPowerMw = if (hasRealDischarge) {
            powerWeightedSum / totalDischargeHours
        } else {
            anchorSample?.powerMw ?: 0f
        }

        val validTempSamples = sorted.filter { it.temperatureCelsius > 0f }
        val avgTempC = if (validTempSamples.isNotEmpty()) {
            validTempSamples.map { it.temperatureCelsius }.average().toFloat()
        } else {
            anchorSample?.temperatureCelsius ?: 0f
        }
        val peakTempSample = validTempSamples.maxByOrNull { it.temperatureCelsius }
        val peakTempC = peakTempSample?.temperatureCelsius ?: avgTempC
        val peakTempTimestampMs = peakTempSample?.timestampMs
        val peakTempWasCharging = peakTempSample?.isCharging ?: false

        val voltages = sorted.map { it.voltageMv }.filter { it > 0 }
        val minVoltageMv = voltages.minOrNull() ?: (anchorSample?.voltageMv ?: 0)
        val maxVoltageMv = voltages.maxOrNull() ?: (anchorSample?.voltageMv ?: 0)

        val avgLinkMbps = if (linkSpeedSamplesKbps.isNotEmpty()) {
            linkSpeedSamplesKbps.average().toFloat() / 1000f
        } else 0f

        val standbyClockTotalMs = totalDeepSleepMs + totalStandbyAwakeMs
        val forensicExtras = mapOf(
            HardwareComponentType.DISPLAY to listOf(
                "High brightness (>=70%): ${formatDuration(highBrightnessMs)}",
                "High refresh (>=90 Hz): ${formatDuration(highRefreshMs)}",
                "Pure screen-on drain rate: ${String.format(Locale.US, "%.1f%%/hr", screenOnRatePctHr)}"
            ),
            HardwareComponentType.CELLULAR to buildList {
                add("Active mobile data: ${formatDuration(cellularActiveDataMs)}")
                add("Metered network time: ${formatDuration(meteredMs)}")
                if (avgLinkMbps > 0f) add(String.format(Locale.US, "Avg link bandwidth: %.1f Mbps", avgLinkMbps))
                if (cellularRatePctHr > 0f) add(String.format(Locale.US, "Measured cellular rate: %.1f%%/hr", cellularRatePctHr))
            },
            HardwareComponentType.WIFI to buildList {
                add("Idle disconnected scanning: ${formatDuration(wifiIdleScanMs)}")
                if (wifiRatePctHr > 0f) add(String.format(Locale.US, "Measured Wi-Fi rate: %.1f%%/hr", wifiRatePctHr))
            },
            HardwareComponentType.GPS to listOf(
                "Location setting ON during standby: ${formatDuration(gpsStandbyMs)}",
                "Location toggle ON window: ${formatDuration(componentActiveMs[HardwareComponentType.GPS] ?: 0L)}",
                "Note: Android exposes Location toggle state, not 3rd-party GNSS satellite lock duration"
            ),
            HardwareComponentType.BLUETOOTH to listOf(
                "Enabled during pure standby: ${formatDuration(btStandbyMs)}",
                "Total Bluetooth radio window: ${formatDuration(componentActiveMs[HardwareComponentType.BLUETOOTH] ?: 0L)}"
            ),
            HardwareComponentType.CPU_SYSTEM to buildList {
                add("Pure screen-off standby rate: ${String.format(Locale.US, "%.1f%%/hr", screenOffRatePctHr)}")
                if (standbyClockTotalMs > 0L) {
                    val sleepPct = (totalDeepSleepMs.toFloat() / standbyClockTotalMs.toFloat()) * 100f
                    add(
                        String.format(
                            Locale.US,
                            "Kernel deep sleep: %.0f%% (%s asleep / %s awake)",
                            sleepPct,
                            formatDuration(totalDeepSleepMs),
                            formatDuration(totalStandbyAwakeMs)
                        )
                    )
                }
                add("High-wattage discharge spikes (>=3.5W): $spikeIntervalCount intervals")
                add("Thermal stress duration: ${formatDuration(hotDurationMs)}")
            }
        )

        // Build Hardware Breakdown list
        val hardwareBreakdown = buildHardwareBreakdown(
            anchorSample = anchorSample,
            totalDischargedMah = totalDischargedMah,
            spanMs = spanMs,
            componentMah = componentMah,
            componentWh = componentWh,
            componentActiveMs = componentActiveMs,
            componentPowerTimeSum = componentPowerTimeSum,
            hasRealDischarge = hasRealDischarge,
            forensicExtras = forensicExtras
        )

        // Detect continuous pure Screen-Off Sleep Sessions (>= 5 min)
        val sleepSessions = detectSleepSessions(sorted, ratedCapacityMah)

        // Build App Attribution list (includes both % of total drain and % of tracked app pool)
        val appCulprits = buildAppAttribution(
            appEvents = appEvents,
            liveAppUsage = liveAppUsage,
            totalDischargedMah = totalDischargedMah,
            avgPowerMw = avgPowerMw.coerceAtLeast(350f),
            ratedCapacityMah = ratedCapacityMah
        )

        // Diagnose Ranked Root Causes
        val findings = diagnoseRootCauses(
            anchorSample = anchorSample,
            hardwareBreakdown = hardwareBreakdown,
            screenOnRatePctHr = screenOnRatePctHr,
            screenOffRatePctHr = screenOffRatePctHr,
            wifiRatePctHr = wifiRatePctHr,
            cellularRatePctHr = cellularRatePctHr,
            highBrightnessMs = highBrightnessMs,
            highRefreshMs = highRefreshMs,
            hotDurationMs = hotDurationMs,
            peakTempC = peakTempC,
            peakTempTimestampMs = peakTempTimestampMs,
            peakTempWasCharging = peakTempWasCharging,
            thermalAlertCelsius = thermalAlertCelsius,
            peakDischargePowerMw = peakDischargePowerMw,
            peakDischargePowerTimestampMs = peakDischargePowerTimestampMs,
            spikeIntervalCount = spikeIntervalCount,
            spanMs = spanMs,
            screenOffDurationMs = screenOffDischargeMs,
            totalDeepSleepMs = totalDeepSleepMs,
            totalStandbyAwakeMs = totalStandbyAwakeMs,
            appCulprits = appCulprits
        )

        val avgVoltageVolts = if (voltages.isNotEmpty()) {
            (voltages.average().toFloat() / 1000f).coerceIn(3.4f, 4.4f)
        } else {
            3.9f
        }
        val discoveredEnergyMeters = summarizeEnergyMeters(
            snapshots = energyMeterSnapshots,
            avgVoltageVolts = avgVoltageVolts
        )
        val hasMeasuredMeters = discoveredEnergyMeters.any { it.deltaMWh > 0.05f }
        // Keep the 6-component hardwareBreakdown honestly labeled as modeled even when supplemental
        // PowerMonitor readings exist (which are reported separately in discoveredEnergyMeters).
        val hardwareAttributionMode = if (hasMeasuredMeters) {
            val railCount = discoveredEnergyMeters.count { it.meterType == "MEASUREMENT" }
            val consumerCount = discoveredEnergyMeters.count { it.meterType == "CONSUMER" }
            "Modeled Component Split (Supplemental PowerMonitor Readings: $railCount ODPM rails, $consumerCount consumers)"
        } else {
            "Modeled Component Split (0 Hardware Energy Meters Recorded in Window)"
        }

        val chargeCounterMeasured = totalDischargeIntervals > 0 &&
            counterBackedDischargeIntervals * 2 >= totalDischargeIntervals

        val drainEpisodes = detectDrainEpisodes(
            sorted = sorted,
            ratedCapacityMah = ratedCapacityMah,
            hasMeasuredEnergyMeters = hasMeasuredMeters,
            hasAppAttribution = appCulprits.isNotEmpty()
        )

        val currentLevel = anchorSample?.levelPct ?: 0
        val remainingHours = if (overallDrainPctHr > 0.3f && anchorSample?.isCharging == false) {
            (currentLevel / overallDrainPctHr).coerceIn(0.1f, 96f)
        } else {
            null
        }

        val efficiencyScore = computeEfficiencyScore(
            overallDrainPctHr = overallDrainPctHr,
            screenOffRatePctHr = screenOffRatePctHr,
            peakTempC = peakTempC,
            thermalAlertCelsius = thermalAlertCelsius,
            hardwareBreakdown = hardwareBreakdown
        )

        val combinedPeakPowerMw = max(peakDischargePowerMw, peakChargePowerMw)

        return BatteryAnalysisReport(
            window = window,
            sampleCount = sorted.size,
            spanDurationMs = spanMs,
            totalDischargedPct = totalDischargedPct,
            totalDischargedMah = totalDischargedMah,
            overallDrainRatePctPerHour = overallDrainPctHr,
            screenOnDrainRatePctPerHour = screenOnRatePctHr,
            screenOffDrainRatePctPerHour = screenOffRatePctHr,
            wifiDrainRatePctPerHour = wifiRatePctHr,
            cellularDrainRatePctPerHour = cellularRatePctHr,
            avgPowerMw = avgPowerMw,
            peakPowerMw = combinedPeakPowerMw,
            avgTempC = avgTempC,
            peakTempC = peakTempC,
            minVoltageMv = minVoltageMv,
            maxVoltageMv = maxVoltageMv,
            screenOnDurationMs = screenOnDischargeMs,
            screenOffDurationMs = screenOffDischargeMs,
            chargingDurationMs = chargingDurationMs,
            mixedTransitionDurationMs = mixedTransitionDurationMs,
            gapDurationMs = gapDurationMs,
            totalChargedMah = totalChargedMah,
            netChargeDeltaMah = totalChargedMah - totalDischargedMah,
            peakDischargePowerMw = peakDischargePowerMw,
            peakDischargePowerTimestampMs = peakDischargePowerTimestampMs,
            peakChargePowerMw = peakChargePowerMw,
            peakChargePowerTimestampMs = peakChargePowerTimestampMs,
            peakTempTimestampMs = peakTempTimestampMs,
            peakTempWasCharging = peakTempWasCharging,
            totalDeepSleepMs = totalDeepSleepMs,
            totalStandbyAwakeMs = totalStandbyAwakeMs,
            totalUnpluggedElapsedMs = totalUnpluggedElapsedMs,
            totalUnpluggedAwakeMs = totalUnpluggedAwakeMs,
            totalUnpluggedDeepSleepMs = totalUnpluggedDeepSleepMs,
            chargeCounterMeasured = chargeCounterMeasured,
            missingClockSampleCount = missingClockSampleCount,
            hardwareAttributionMode = hardwareAttributionMode,
            discoveredEnergyMeters = discoveredEnergyMeters,
            drainEpisodes = drainEpisodes,
            estimatedRemainingHours = remainingHours,
            efficiencyScore = efficiencyScore,
            hardwareBreakdown = hardwareBreakdown,
            sleepSessions = sleepSessions,
            findings = findings,
            appCulprits = appCulprits,
            hasLongitudinalData = spanMs >= 5 * 60_000L && sorted.size >= 4
        )
    }

    /**
     * Detects contiguous screen-off discharging sleep sessions (>= 5 minutes)
     * to audit kernel Doze deep-sleep efficiency and isolate standby wakelocks or active radios.
     */
    internal fun detectSleepSessions(
        sorted: List<BatterySampleEntity>,
        ratedCapacityMah: Int
    ): List<SleepSessionSummary> {
        if (sorted.size < 2) return emptyList()
        val sessions = mutableListOf<SleepSessionSummary>()
        var blockStartIdx: Int? = null

        fun finalizeBlock(startIdx: Int, endIdx: Int) {
            if (endIdx <= startIdx) return
            val first = sorted[startIdx]
            val last = sorted[endIdx]
            val durationMs = last.timestampMs - first.timestampMs
            if (durationMs < 5L * 60L * 1000L) return

            val hours = durationMs / 3_600_000f
            val levelDrop = (first.levelPct - last.levelPct).coerceAtLeast(0)
            val counterBacked = first.chargeCounterMicroAh > 0L && last.chargeCounterMicroAh > 0L
            val mahLost = when {
                counterBacked -> {
                    ((first.chargeCounterMicroAh - last.chargeCounterMicroAh) / 1000f).coerceAtLeast(0f)
                }

                levelDrop > 0 -> (levelDrop / 100f) * ratedCapacityMah
                else -> {
                    val slice = sorted.subList(startIdx, endIdx + 1)
                    val avgMw = slice.map { it.powerMw }.filter { it > 10f }.average()
                        .toFloat().let { if (it.isNaN()) 65f else it }
                    val avgV = slice.map { it.voltageVolts }.filter { it > 2f }.average()
                        .toFloat().let { if (it.isNaN()) 3.85f else it }
                    (avgMw * hours) / avgV
                }
            }.coerceAtLeast(0f)

            val effectivePctDrop = max(levelDrop.toFloat(), (mahLost / ratedCapacityMah.coerceAtLeast(1000)) * 100f)
            val ratePctHr = (effectivePctDrop / hours).coerceIn(0f, 35f)

            // Compute kernel deep sleep from elapsedRealtime vs uptime when available across same boot
            var deepSleepMs: Long? = null
            var cpuAwakeMs: Long? = null
            var deepSleepPct: Float? = null
            if (first.elapsedRealtimeMs > 0L && last.elapsedRealtimeMs > first.elapsedRealtimeMs &&
                first.uptimeMs > 0L && last.uptimeMs >= first.uptimeMs
            ) {
                val dtElapsed = last.elapsedRealtimeMs - first.elapsedRealtimeMs
                if (abs(dtElapsed - durationMs) <= max(60_000L, durationMs / 2L)) {
                    val dtAwake = (last.uptimeMs - first.uptimeMs).coerceIn(0L, dtElapsed)
                    val dtSleep = (dtElapsed - dtAwake).coerceAtLeast(0L)
                    deepSleepMs = dtSleep
                    cpuAwakeMs = dtAwake
                    deepSleepPct = ((dtSleep.toFloat() / dtElapsed.toFloat()) * 100f).coerceIn(0f, 100f)
                }
            }

            val slice = sorted.subList(startIdx, endIdx + 1)
            val activeRadios = buildList {
                if (slice.any { it.cellularActive }) add("CELL")
                if (slice.any { it.gpsEnabled }) add("LOC-ON")
                if (slice.any { it.bluetoothEnabled }) add("BT")
                if (slice.any { it.wifiEnabled && !it.wifiConnected }) add("WIFI-IDLE")
            }

            val (verdict, severity) = when {
                deepSleepPct != null && ratePctHr >= 2.5f && deepSleepPct < 75f ->
                    "High CPU Awake Time (Possible Wakelock)" to CauseSeverity.CRITICAL
                ratePctHr >= 2.5f ->
                    "Severe Standby Drain" to CauseSeverity.CRITICAL
                deepSleepPct != null && ratePctHr >= 1.3f && deepSleepPct < 75f ->
                    "Elevated CPU Awake Time (Possible Wakelock)" to CauseSeverity.ELEVATED
                deepSleepPct != null && ratePctHr >= 1.3f && deepSleepPct >= 85f ->
                    "Standby Drain While Deep-Sleeping (Possible Radio/Baseband)" to CauseSeverity.ELEVATED
                ratePctHr >= 1.3f ->
                    "Elevated Standby Drain (Cause Unconfirmed)" to CauseSeverity.ELEVATED
                else ->
                    "Deep Sleep Healthy" to CauseSeverity.HEALTHY
            }

            sessions.add(
                SleepSessionSummary(
                    startMs = first.timestampMs,
                    endMs = last.timestampMs,
                    durationMs = durationMs,
                    startLevelPct = first.levelPct,
                    endLevelPct = last.levelPct,
                    drainRatePctPerHour = ratePctHr,
                    mahLost = mahLost,
                    verdict = verdict,
                    severity = severity,
                    activeRadiosDuringSleep = activeRadios,
                    deepSleepMs = deepSleepMs,
                    cpuAwakeMs = cpuAwakeMs,
                    deepSleepPct = deepSleepPct
                )
            )
        }

        for (i in sorted.indices) {
            val s = sorted[i]
            val isStandby = !s.isScreenOn && !s.isCharging
            val hasGapBreak = i > 0 && (s.timestampMs - sorted[i - 1].timestampMs) > 6L * 60L * 60L * 1000L

            if (isStandby && !hasGapBreak) {
                if (blockStartIdx == null) blockStartIdx = i
            } else {
                blockStartIdx?.let { start ->
                    finalizeBlock(start, i - 1)
                    blockStartIdx = null
                }
                if (isStandby) blockStartIdx = i
            }
        }
        blockStartIdx?.let { start -> finalizeBlock(start, sorted.lastIndex) }

        return sessions.sortedByDescending { it.endMs }.take(8)
    }

    fun analyzeProbeSession(
        probeSamples: List<BatterySampleEntity>,
        ratedCapacityMah: Int
    ): ProbeSessionResult {
        if (probeSamples.isEmpty()) {
            return ProbeSessionResult(
                durationSec = 0,
                sampleCount = 0,
                avgPowerMw = 0f,
                peakPowerMw = 0f,
                startVoltageMv = 0,
                endVoltageMv = 0,
                tempDeltaC = 0f,
                estimatedDrainPctPerHour = 0f,
                primaryHardwareLoad = "None",
                verdict = "No samples captured."
            )
        }
        val first = probeSamples.first()
        val last = probeSamples.last()
        val durationSec = ((last.timestampMs - first.timestampMs) / 1000L).toInt().coerceAtLeast(1)
        val powers = probeSamples.map { s ->
            if (s.powerMw > 25f) s.powerMw else estimateInstantHardwareMw(s).values.sum()
        }
        val avgPower = powers.average().toFloat()
        val peakPower = powers.maxOrNull() ?: avgPower
        val avgVolts = probeSamples.map { it.voltageVolts }.filter { it > 0f }.average()
            .toFloat().let { if (it.isNaN() || it <= 0f) 3.85f else it }
        val avgCurrentMa = avgPower / avgVolts
        val drainPctHr = ((avgCurrentMa / ratedCapacityMah.coerceAtLeast(1000)) * 100f).coerceIn(0f, 95f)
        val tempDelta = last.temperatureCelsius - first.temperatureCelsius

        val hwMap = estimateInstantHardwareMw(last)
        val topHw = hwMap.maxByOrNull { it.value }?.key ?: HardwareComponentType.DISPLAY

        val verdict = when {
            last.isCharging -> "Device is charging — unplug for an unobstructed discharge load test."
            avgPower >= 3200f -> "Heavy active draw (${String.format(Locale.US, "%.2f", avgPower / 1000f)}W). Driven primarily by ${topHw.displayName} & compute."
            avgPower >= 1600f -> "Moderate active draw (${String.format(Locale.US, "%.2f", avgPower / 1000f)}W). Normal for active display + radios."
            else -> "Efficient power profile (${String.format(Locale.US, "%.2f", avgPower / 1000f)}W). Low hardware load observed."
        }

        return ProbeSessionResult(
            durationSec = durationSec,
            sampleCount = probeSamples.size,
            avgPowerMw = avgPower,
            peakPowerMw = peakPower,
            startVoltageMv = first.voltageMv,
            endVoltageMv = last.voltageMv,
            tempDeltaC = tempDelta,
            estimatedDrainPctPerHour = drainPctHr,
            primaryHardwareLoad = topHw.displayName,
            verdict = verdict
        )
    }

    private fun computeIntervalMah(
        a: BatterySampleEntity,
        b: BatterySampleEntity,
        dtHours: Float,
        avgVolts: Float,
        measuredPowerMw: Float,
        fallbackHwMw: Float,
        ratedCapacityMah: Int
    ): Float {
        // Priority 1: Hardware coulomb counter (exact battery charge counter delta).
        // When both endpoints report a valid charge counter (> 0), trust the counter directly
        // including 0.0 mAh on flat 100% fuel-gauge plateaus so we never double-count synthetic
        // current integration on top of the subsequent counter drop.
        if (a.chargeCounterMicroAh > 0L && b.chargeCounterMicroAh > 0L) {
            return ((a.chargeCounterMicroAh - b.chargeCounterMicroAh) / 1000f).coerceIn(0f, 3000f)
        }

        // Priority 2: Measured instantaneous current & voltage integration
        if (measuredPowerMw > 25f) {
            val integrated = (measuredPowerMw * dtHours) / avgVolts
            if (integrated > 0.0005f) return integrated
        }

        // Priority 3: Hardware power-profile baseline integration over dt.
        // Whole-number battery % gauge drops are reconciled once per continuous non-counter period
        // in analyze() so coarse 1% steps are never double-counted with integrated current.
        return ((fallbackHwMw.coerceAtLeast(45f)) * dtHours) / avgVolts
    }

    /**
     * Computes cumulative energy deltas per discovered Android 15+ PowerMonitor meter
     * across same-boot snapshots within the analysis window.
     */
    internal fun summarizeEnergyMeters(
        snapshots: List<EnergyMeterSnapshotEntity>,
        avgVoltageVolts: Float
    ): List<EnergyMeterSummary> {
        if (snapshots.isEmpty()) return emptyList()
        return snapshots
            .groupBy { it.meterName }
            .mapNotNull { (meterName, rows) ->
                val ordered = rows.sortedBy { it.timestampMs }
                if (ordered.size < 2) {
                    val only = ordered.first()
                    return@mapNotNull EnergyMeterSummary(
                        meterName = meterName,
                        meterType = only.meterType,
                        deltaMicroWattSec = 0L,
                        deltaMWh = 0f,
                        estimatedMah = 0f,
                        startEnergyMicroWattSec = only.energyMicroWattSec,
                        endEnergyMicroWattSec = only.energyMicroWattSec,
                        sampleCount = 1
                    )
                }
                var totalDeltaUws = 0L
                for (i in 0 until ordered.lastIndex) {
                    val prev = ordered[i]
                    val next = ordered[i + 1]
                    // Same-boot check: cumulative elapsedRealtime and energy should be monotonic
                    if (next.elapsedRealtimeMs >= prev.elapsedRealtimeMs &&
                        next.energyMicroWattSec >= prev.energyMicroWattSec
                    ) {
                        totalDeltaUws += (next.energyMicroWattSec - prev.energyMicroWattSec)
                    }
                }
                val deltaMWh = totalDeltaUws / 3_600_000f
                val estMah = if (avgVoltageVolts > 0f) deltaMWh / avgVoltageVolts else 0f
                EnergyMeterSummary(
                    meterName = meterName,
                    meterType = ordered.last().meterType,
                    deltaMicroWattSec = totalDeltaUws,
                    deltaMWh = deltaMWh,
                    estimatedMah = estMah,
                    startEnergyMicroWattSec = ordered.first().energyMicroWattSec,
                    endEnergyMicroWattSec = ordered.last().energyMicroWattSec,
                    sampleCount = ordered.size
                )
            }
            .sortedWith(
                compareByDescending<EnergyMeterSummary> { it.deltaMicroWattSec }
                    .thenBy { it.meterType != "MEASUREMENT" }
                    .thenBy { it.meterName }
            )
    }

    /**
     * Segments discharging telemetry into significant, traceable drain episodes.
     *
     * Each episode explicitly reports:
     * - When it happened (start/end timestamps & duration)
     * - How much charge was lost (battery counter µAh delta or fallback estimate)
     * - How long the CPU was awake vs in kernel deep sleep
     * - What activity was directly observed (screen, foreground app, radio transport, power/temp)
     * - What remains unknown (sampling gaps, unattributed background tasks, OS permission limits)
     * - Source readings (exact timestamped start/peak/end raw records)
     */
    internal fun detectDrainEpisodes(
        sorted: List<BatterySampleEntity>,
        ratedCapacityMah: Int,
        hasMeasuredEnergyMeters: Boolean,
        hasAppAttribution: Boolean
    ): List<DrainEpisode> {
        if (sorted.size < 2) return emptyList()

        data class RawInterval(
            val a: BatterySampleEntity,
            val b: BatterySampleEntity,
            val dtMs: Long,
            val mahLost: Float,
            val counterBacked: Boolean,
            val awakeMs: Long?,
            val deepSleepMs: Long?
        )

        val intervals = mutableListOf<RawInterval>()
        for (i in 0 until sorted.lastIndex) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val dtMs = b.timestampMs - a.timestampMs
            if (dtMs <= 0L || dtMs > 6L * 60L * 60L * 1000L) continue
            if (a.isCharging || b.isCharging) continue

            val dtHours = dtMs / 3_600_000f
            val avgV = ((a.voltageVolts + b.voltageVolts) / 2f).coerceIn(3.2f, 4.5f)
            val measuredPowerMw = averagePositive(a.powerMw, b.powerMw)
            val hwSum = estimateInstantHardwareMw(a).values.sum()
            val counterBacked = a.chargeCounterMicroAh > 0L && b.chargeCounterMicroAh > 0L
            val mahLost = computeIntervalMah(a, b, dtHours, avgV, measuredPowerMw, hwSum, ratedCapacityMah)

            var awakeMs: Long? = null
            var sleepMs: Long? = null
            if (a.elapsedRealtimeMs > 0L && b.elapsedRealtimeMs > a.elapsedRealtimeMs &&
                a.uptimeMs > 0L && b.uptimeMs >= a.uptimeMs
            ) {
                val dtElapsed = b.elapsedRealtimeMs - a.elapsedRealtimeMs
                if (abs(dtElapsed - dtMs) <= max(60_000L, dtMs / 2L)) {
                    val dtAwake = (b.uptimeMs - a.uptimeMs).coerceIn(0L, dtElapsed)
                    awakeMs = dtAwake
                    sleepMs = (dtElapsed - dtAwake).coerceAtLeast(0L)
                }
            }
            intervals.add(RawInterval(a, b, dtMs, mahLost, counterBacked, awakeMs, sleepMs))
        }

        if (intervals.isEmpty()) return emptyList()

        // Group contiguous short intervals (< 10 min) with same screen & network regime so
        // 1-minute high-precision runs aggregate cleanly into episodes, while keeping
        // coarse (>= 12 min) intervals individually traceable to their exact boundary rows.
        val groups = mutableListOf<MutableList<RawInterval>>()
        for (iv in intervals) {
            val current = groups.lastOrNull()
            val prev = current?.lastOrNull()
            val canMerge = prev != null &&
                prev.b.timestampMs == iv.a.timestampMs &&
                prev.dtMs < 10L * 60_000L &&
                iv.dtMs < 10L * 60_000L &&
                prev.a.isScreenOn == iv.a.isScreenOn &&
                prev.b.isScreenOn == iv.b.isScreenOn &&
                prev.a.networkTransport == iv.a.networkTransport &&
                (iv.b.timestampMs - current.first().a.timestampMs) <= 45L * 60_000L

            if (canMerge) {
                current.add(iv)
            } else {
                groups.add(mutableListOf(iv))
            }
        }

        val episodes = mutableListOf<DrainEpisode>()
        groups.forEachIndexed { idx, grp ->
            val first = grp.first().a
            val last = grp.last().b
            val durationMs = (last.timestampMs - first.timestampMs).coerceAtLeast(1L)
            val totalMah = grp.sumOf { it.mahLost.toDouble() }.toFloat()
            val levelDrop = (first.levelPct - last.levelPct).coerceAtLeast(0)
            val allSamples = buildList {
                add(first)
                grp.forEach { add(it.b) }
            }
            val peakSample = allSamples.maxByOrNull { it.powerMw }
            val peakPowerMw = peakSample?.powerMw ?: 0f

            // Keep significant episodes: >= 40 mAh charge loss (or >= 2% SOC drop with real loss)
            if (totalMah < 40f && !(levelDrop >= 2 && totalMah >= 15f)) return@forEachIndexed

            val counterMeasured = grp.all { it.counterBacked }
            val rateMahHr = totalMah / (durationMs / 3_600_000f).coerceAtLeast(0.01f)
            val hasClocks = grp.all { it.awakeMs != null && it.deepSleepMs != null }
            val cpuAwakeMs = if (hasClocks) grp.sumOf { it.awakeMs ?: 0L } else null
            val deepSleepMs = if (hasClocks) grp.sumOf { it.deepSleepMs ?: 0L } else null
            val awakePct = if (cpuAwakeMs != null && deepSleepMs != null && (cpuAwakeMs + deepSleepMs) > 0L) {
                (cpuAwakeMs.toFloat() / (cpuAwakeMs + deepSleepMs).toFloat()) * 100f
            } else {
                null
            }

            val temps = allSamples.map { it.temperatureCelsius }.filter { it > 0f }
            val minTemp = temps.minOrNull() ?: 0f
            val maxTemp = temps.maxOrNull() ?: 0f

            val allScreenOn = allSamples.all { it.isScreenOn }
            val allScreenOff = allSamples.all { !it.isScreenOn }
            val screenSummary = when {
                allScreenOn -> "Screen ON at endpoints"
                allScreenOff -> "Screen OFF at endpoints"
                first.isScreenOn && !last.isScreenOn -> "Screen ON → OFF transition"
                !first.isScreenOn && last.isScreenOn -> "Screen OFF → ON transition"
                else -> "Mixed screen states"
            }

            val observed = buildList {
                val brightnessRange = allSamples.map { it.screenBrightnessPct }
                val refreshSet = allSamples.map { it.displayRefreshRateHz.roundToInt() }.distinct().sorted()
                add("$screenSummary (brightness ${brightnessRange.minOrNull()}–${brightnessRange.maxOrNull()}%, ${refreshSet.joinToString("/")} Hz)")

                val transports = allSamples.map { it.networkTransport }.distinct()
                val metered = allSamples.any { it.isMeteredNetwork }
                val transportLabel = transports.joinToString(" → ")
                add("Network transport: $transportLabel${if (metered) " (metered)" else ""}")

                val fgApps = allSamples.mapNotNull { it.foregroundLabel ?: it.foregroundPackage }
                    .filter { it.isNotBlank() }
                    .distinct()
                if (fgApps.isNotEmpty()) {
                    add("Observed foreground app at sample boundary: ${fgApps.joinToString(", ")}")
                } else {
                    add("No foreground app recorded at sample boundaries")
                }

                if (peakSample != null && peakPowerMw > 25f) {
                    add(
                        String.format(
                            Locale.US,
                            "Peak instantaneous discharge: -%.2f W (-%.0f mA @ %d mV) at %s",
                            peakPowerMw / 1000f,
                            peakSample.currentAbsMa,
                            peakSample.voltageMv,
                            formatIsoUtc(peakSample.timestampMs)
                        )
                    )
                }

                if (temps.isNotEmpty()) {
                    val tempDelta = last.temperatureCelsius - first.temperatureCelsius
                    add(
                        String.format(
                            Locale.US,
                            "Battery temperature: %.1f°C → %.1f°C (%+.1f°C; max %.1f°C)",
                            first.temperatureCelsius,
                            last.temperatureCelsius,
                            tempDelta,
                            maxTemp
                        )
                    )
                }

                val radiosOn = buildList {
                    if (allSamples.any { it.gpsEnabled }) add("Location toggle ON")
                    if (allSamples.any { it.bluetoothEnabled }) add("Bluetooth ON")
                    if (allSamples.any { it.wifiEnabled && !it.wifiConnected }) add("Wi-Fi idle scan")
                }
                if (radiosOn.isNotEmpty()) {
                    add("Active radio settings: ${radiosOn.joinToString(", ")}")
                }
            }

            val maxGapMs = grp.maxOf { it.dtMs }
            val unknowns = buildList {
                if (maxGapMs >= 12L * 60_000L) {
                    add("Coarse ${formatDuration(maxGapMs)} sampling interval — intermediate screen on/off transitions and foreground app switches inside this window were not logged.")
                }
                if (!hasAppAttribution) {
                    add("Per-app UsageStats foreground durations were not present in this dataset; only point-in-time boundary foreground labels are known.")
                }
                if (allScreenOff && (awakePct == null || awakePct >= 30f)) {
                    add("Android ordinary app permissions do not expose kernel partial-wakelock holder names or UID wakeup counts (requires dumpsys batterystats / ADB).")
                }
                if (allSamples.any { it.gpsEnabled }) {
                    add("Location setting was ON, but Android does not expose actual third-party GNSS satellite lock duration to ordinary apps.")
                }
                if (!hasMeasuredEnergyMeters) {
                    add("No on-device PowerMonitor hardware rail deltas were recorded in this window, so per-subsystem mAh split is modeled rather than rail-metered.")
                }
            }

            val sourceReadings = buildList {
                add("START: ${formatSourceReading(first)}")
                if (peakSample != null && peakSample.timestampMs != first.timestampMs && peakSample.timestampMs != last.timestampMs) {
                    add("PEAK:  ${formatSourceReading(peakSample)}")
                }
                add("END:   ${formatSourceReading(last)}")
            }

            val severity = when {
                totalMah >= 120f || peakPowerMw >= 7_500f || (allScreenOff && rateMahHr >= 120f) -> CauseSeverity.CRITICAL
                totalMah >= 70f || peakPowerMw >= 5_000f || rateMahHr >= 180f -> CauseSeverity.ELEVATED
                else -> CauseSeverity.MODERATE
            }

            episodes.add(
                DrainEpisode(
                    id = "ep_${idx + 1}_${first.timestampMs}",
                    startMs = first.timestampMs,
                    endMs = last.timestampMs,
                    durationMs = durationMs,
                    chargeLostMah = totalMah,
                    counterMeasured = counterMeasured,
                    startCounterMicroAh = first.chargeCounterMicroAh,
                    endCounterMicroAh = last.chargeCounterMicroAh,
                    startLevelPct = first.levelPct,
                    endLevelPct = last.levelPct,
                    levelDropPct = levelDrop,
                    drainRateMahPerHour = rateMahHr,
                    cpuAwakeMs = cpuAwakeMs,
                    deepSleepMs = deepSleepMs,
                    awakePct = awakePct,
                    peakPowerMw = peakPowerMw,
                    peakPowerTimestampMs = peakSample?.timestampMs,
                    minTempC = minTemp,
                    maxTempC = maxTemp,
                    screenSummary = screenSummary,
                    observedActivity = observed,
                    whatRemainsUnknown = unknowns,
                    sourceReadings = sourceReadings,
                    severity = severity
                )
            )
        }

        return episodes.sortedByDescending { it.chargeLostMah }
    }

    private fun formatSourceReading(s: BatterySampleEntity): String {
        val fg = (s.foregroundLabel ?: s.foregroundPackage)?.takeIf { it.isNotBlank() } ?: "none"
        return String.format(
            Locale.US,
            "%s [%s] %d%% | %d µAh | %+.2f W (%+.0f mA @ %d mV) | %.1f°C | scr=%s | %s | fg=%s | elapsed=%d uptime=%d",
            formatIsoUtc(s.timestampMs),
            s.source,
            s.levelPct,
            s.chargeCounterMicroAh,
            s.powerSignedWatts,
            s.currentSignedMa,
            s.voltageMv,
            s.temperatureCelsius,
            if (s.isScreenOn) "ON" else "OFF",
            s.networkTransport,
            fg,
            s.elapsedRealtimeMs,
            s.uptimeMs
        )
    }

    private fun buildHardwareBreakdown(
        anchorSample: BatterySampleEntity?,
        totalDischargedMah: Float,
        spanMs: Long,
        componentMah: Map<HardwareComponentType, Float>,
        componentWh: Map<HardwareComponentType, Float>,
        componentActiveMs: Map<HardwareComponentType, Long>,
        componentPowerTimeSum: Map<HardwareComponentType, Float>,
        hasRealDischarge: Boolean,
        forensicExtras: Map<HardwareComponentType, List<String>> = emptyMap()
    ): List<HardwareComponentAttribution> {
        val liveSubsystems = anchorSample?.let { estimateLiveSubsystems(it) }
            ?.associateBy { it.component } ?: emptyMap()

        if (!hasRealDischarge && anchorSample != null) {
            // Instantaneous live hardware power share when charging or fresh baseline
            val instantMap = estimateInstantHardwareMw(anchorSample)
            val cpuBase = (anchorSample.powerMw - instantMap.values.sum()).coerceAtLeast(110f)
            val allMw = instantMap + (HardwareComponentType.CPU_SYSTEM to cpuBase)
            val totalMw = allMw.values.sum().coerceAtLeast(1f)
            return HardwareComponentType.entries.map { type ->
                val mw = allMw[type] ?: 0f
                val share = (mw / totalMw) * 100f
                val live = liveSubsystems[type]
                val isActive = live?.isActive ?: (type == HardwareComponentType.CPU_SYSTEM)
                HardwareComponentAttribution(
                    component = type,
                    attributedMah = 0f,
                    attributedWh = 0f,
                    sharePct = share,
                    activeDurationMs = 0L,
                    activeDutyPct = if (isActive) 100f else 0f,
                    avgPowerMwWhenActive = mw,
                    isCurrentlyActive = isActive,
                    liveDetail = live?.liveDetail ?: "OS, CPU & Wakelocks",
                    verdict = when {
                        !isActive -> "Off / Idle"
                        share >= 35f -> "Primary Live Load"
                        share >= 15f -> "Active Contributor"
                        else -> "Low Draw"
                    },
                    severity = when {
                        !isActive -> CauseSeverity.HEALTHY
                        share >= 40f -> CauseSeverity.ELEVATED
                        share >= 20f -> CauseSeverity.MODERATE
                        else -> CauseSeverity.HEALTHY
                    },
                    settingsAction = live?.settingsAction ?: SettingsAction.BATTERY_SAVER,
                    forensicDetails = forensicExtras[type].orEmpty()
                )
            }.sortedByDescending { it.sharePct }
        }

        val denomMah = totalDischargedMah.coerceAtLeast(0.001f)
        return HardwareComponentType.entries.map { type ->
            val mah = componentMah[type] ?: 0f
            val wh = componentWh[type] ?: 0f
            val sharePct = ((mah / denomMah) * 100f).coerceIn(0f, 100f)
            val activeMs = componentActiveMs[type] ?: 0L
            val dutyPct = ((activeMs.toFloat() / spanMs.toFloat()) * 100f).coerceIn(0f, 100f)
            val activeHours = (activeMs / 3_600_000f)
            val avgMw = if (activeHours > 0.0001f) {
                (componentPowerTimeSum[type] ?: 0f) / activeHours
            } else {
                liveSubsystems[type]?.liveEstimatedMw ?: 0f
            }
            val live = liveSubsystems[type]
            val isActive = live?.isActive ?: (type == HardwareComponentType.CPU_SYSTEM)

            val (verdict, severity) = classifyHardwareSeverity(type, sharePct, dutyPct, isActive)

            HardwareComponentAttribution(
                component = type,
                attributedMah = mah,
                attributedWh = wh,
                sharePct = sharePct,
                activeDurationMs = activeMs,
                activeDutyPct = dutyPct,
                avgPowerMwWhenActive = avgMw,
                isCurrentlyActive = isActive,
                liveDetail = live?.liveDetail ?: "Compute, GPU & Wakelocks",
                verdict = verdict,
                severity = severity,
                settingsAction = live?.settingsAction ?: SettingsAction.BATTERY_SAVER,
                forensicDetails = forensicExtras[type].orEmpty()
            )
        }.sortedByDescending { it.sharePct }
    }

    private fun classifyHardwareSeverity(
        type: HardwareComponentType,
        sharePct: Float,
        dutyPct: Float,
        isActive: Boolean
    ): Pair<String, CauseSeverity> {
        return when (type) {
            HardwareComponentType.DISPLAY -> when {
                sharePct >= 45f -> "Major Display Load" to CauseSeverity.CRITICAL
                sharePct >= 30f -> "Elevated Display Draw" to CauseSeverity.ELEVATED
                isActive -> "Normal Active Load" to CauseSeverity.MODERATE
                else -> "Screen Off" to CauseSeverity.HEALTHY
            }

            HardwareComponentType.CELLULAR -> when {
                sharePct >= 28f -> "High Modem Drain" to CauseSeverity.CRITICAL
                sharePct >= 16f -> "Elevated Cellular Draw" to CauseSeverity.ELEVATED
                isActive -> "Standby / Normal" to CauseSeverity.HEALTHY
                else -> "Airplane Mode" to CauseSeverity.HEALTHY
            }

            HardwareComponentType.GPS -> when {
                sharePct >= 14f -> "Elevated Location Overhead" to CauseSeverity.MODERATE
                isActive -> "Location Setting ON" to CauseSeverity.HEALTHY
                else -> "Disabled" to CauseSeverity.HEALTHY
            }

            HardwareComponentType.WIFI -> when {
                sharePct >= 18f -> "Elevated Wi-Fi Activity" to CauseSeverity.MODERATE
                isActive -> "Efficient Link" to CauseSeverity.HEALTHY
                else -> "Disabled" to CauseSeverity.HEALTHY
            }

            HardwareComponentType.BLUETOOTH -> when {
                sharePct >= 10f -> "Continuous BT Radio" to CauseSeverity.MODERATE
                isActive -> "Radio Active" to CauseSeverity.HEALTHY
                else -> "Disabled" to CauseSeverity.HEALTHY
            }

            HardwareComponentType.CPU_SYSTEM -> when {
                sharePct >= 45f -> "Heavy Compute / Wakelocks" to CauseSeverity.CRITICAL
                sharePct >= 30f -> "Elevated System Load" to CauseSeverity.ELEVATED
                else -> "Normal System Baseline" to CauseSeverity.HEALTHY
            }
        }
    }

    private fun buildAppAttribution(
        appEvents: List<AppDrainEventEntity>,
        liveAppUsage: List<ObservedAppUsage>,
        totalDischargedMah: Float,
        avgPowerMw: Float,
        ratedCapacityMah: Int
    ): List<AppDrainAttribution> {
        val denomTotalMah = totalDischargedMah.coerceAtLeast(1f)
        if (appEvents.isNotEmpty()) {
            val grouped = appEvents.groupBy { it.packageName }
            val sumMah = appEvents.sumOf { it.estimatedMah.toDouble() }.toFloat().coerceAtLeast(0.1f)
            return grouped.map { (pkg, list) ->
                val label = list.first().appLabel
                val fgMs = list.sumOf { it.foregroundMs }
                val launches = list.sumOf { it.launchCount }
                val mah = list.sumOf { it.estimatedMah.toDouble() }.toFloat()
                val avgMw = list.map { it.avgPowerMw }.average().toFloat()
                val hours = (fgMs / 3_600_000f).coerceAtLeast(0.01f)
                val drainPctHr = ((mah / ratedCapacityMah.coerceAtLeast(1000)) * 100f) / hours
                val totalShare = if (totalDischargedMah > 0.5f) {
                    ((mah / denomTotalMah) * 100f).coerceIn(0f, 100f)
                } else {
                    ((mah / sumMah) * 100f).coerceIn(0f, 100f)
                }
                AppDrainAttribution(
                    packageName = pkg,
                    appLabel = label,
                    totalForegroundMs = fgMs,
                    wakeupCount = launches,
                    estimatedMah = mah,
                    sharePct = ((mah / sumMah) * 100f).coerceIn(0f, 100f),
                    shareOfTotalDrainPct = totalShare,
                    avgPowerMw = avgMw,
                    drainRatePctPerHour = drainPctHr.coerceIn(0f, 80f),
                    isHighIntensity = drainPctHr >= 14f || launches >= 12
                )
            }.sortedByDescending { it.estimatedMah }.take(10)
        }

        if (liveAppUsage.isNotEmpty()) {
            // Do NOT invent energy attribution when no discharge has been recorded yet.
            if (totalDischargedMah <= 0.5f) {
                return liveAppUsage
                    .sortedByDescending { it.foregroundMs }
                    .take(10)
                    .map { app ->
                        AppDrainAttribution(
                            packageName = app.packageName,
                            appLabel = app.appLabel,
                            totalForegroundMs = app.foregroundMs,
                            wakeupCount = app.launchCount,
                            estimatedMah = 0f,
                            sharePct = 0f,
                            shareOfTotalDrainPct = 0f,
                            avgPowerMw = 0f,
                            drainRatePctPerHour = 0f,
                            isHighIntensity = false,
                            hasEnergyEstimate = false
                        )
                    }
            }

            val totalFgMs = liveAppUsage.sumOf { it.foregroundMs }.coerceAtLeast(1L)
            return liveAppUsage.map { app ->
                val share = app.foregroundMs.toFloat() / totalFgMs.toFloat()
                val mah = totalDischargedMah * share
                val hours = (app.foregroundMs / 3_600_000f).coerceAtLeast(0.01f)
                val drainPctHr = ((mah / ratedCapacityMah.coerceAtLeast(1000)) * 100f) / hours
                val totalShare = ((mah / totalDischargedMah.coerceAtLeast(1f)) * 100f).coerceIn(0f, 100f)
                AppDrainAttribution(
                    packageName = app.packageName,
                    appLabel = app.appLabel,
                    totalForegroundMs = app.foregroundMs,
                    wakeupCount = app.launchCount,
                    estimatedMah = mah,
                    sharePct = (share * 100f).coerceIn(0f, 100f),
                    shareOfTotalDrainPct = totalShare,
                    avgPowerMw = avgPowerMw,
                    drainRatePctPerHour = drainPctHr.coerceIn(0f, 80f),
                    isHighIntensity = app.launchCount >= 10 || totalShare >= 25f,
                    hasEnergyEstimate = true
                )
            }.sortedByDescending { it.estimatedMah }.take(10)
        }

        return emptyList()
    }

    private fun diagnoseRootCauses(
        anchorSample: BatterySampleEntity?,
        hardwareBreakdown: List<HardwareComponentAttribution>,
        screenOnRatePctHr: Float,
        screenOffRatePctHr: Float,
        wifiRatePctHr: Float,
        cellularRatePctHr: Float,
        highBrightnessMs: Long,
        highRefreshMs: Long,
        hotDurationMs: Long,
        peakTempC: Float,
        peakTempTimestampMs: Long?,
        peakTempWasCharging: Boolean,
        thermalAlertCelsius: Float,
        peakDischargePowerMw: Float,
        peakDischargePowerTimestampMs: Long?,
        spikeIntervalCount: Int,
        spanMs: Long,
        screenOffDurationMs: Long,
        totalDeepSleepMs: Long,
        totalStandbyAwakeMs: Long,
        appCulprits: List<AppDrainAttribution>
    ): List<RootCauseFinding> {
        val findings = mutableListOf<RootCauseFinding>()
        val byComp = hardwareBreakdown.associateBy { it.component }

        // 1. Display Subsystem Driver (Brightness + High Refresh Rate)
        val display = byComp[HardwareComponentType.DISPLAY]
        if (display != null && (display.sharePct >= 26f || (anchorSample?.isScreenOn == true && anchorSample.screenBrightnessPct >= 70))) {
            val highBrightPct = ((highBrightnessMs.toFloat() / spanMs.toFloat()) * 100f).roundToInt()
            val highHzPct = ((highRefreshMs.toFloat() / spanMs.toFloat()) * 100f).roundToInt()
            val isCritical = display.sharePct >= 42f || highBrightPct >= 50
            findings += RootCauseFinding(
                id = "cause_display",
                category = RootCauseCategory.DISPLAY_POWER,
                severity = if (isCritical) CauseSeverity.CRITICAL else CauseSeverity.ELEVATED,
                title = "Display Luminance & Refresh Load",
                headlineMetric = String.format(Locale.US, "%.0f%% of drain", display.sharePct),
                sharePct = display.sharePct,
                explanation = "The display panel is a primary hardware power consumer, amplified by high brightness or elevated refresh rate.",
                evidencePoints = buildList {
                    add(String.format(Locale.US, "Attributed %.1f mAh (avg %.0f mW active)", display.attributedMah, display.avgPowerMwWhenActive))
                    if (anchorSample != null) {
                        add("Current: ${anchorSample.screenBrightnessPct}% brightness @ ${anchorSample.displayRefreshRateHz.roundToInt()} Hz")
                    }
                    if (highBrightPct > 0) add("Spent $highBrightPct% of window at >=70% brightness")
                    if (highHzPct > 0) add("High refresh (>=90 Hz) active $highHzPct% of window")
                },
                recommendation = "Enable Adaptive Brightness, lower peak luminance below 65%, and reduce screen timeout or refresh rate.",
                actionLabel = "Display Settings",
                settingsAction = SettingsAction.DISPLAY_SETTINGS
            )
        }

        // 2. Elevated Screen-Off Standby Drain / Observed CPU Awake Time
        if (screenOffDurationMs >= 5 * 60_000L && screenOffRatePctHr >= 1.5f) {
            val severity = if (screenOffRatePctHr >= 3.0f) CauseSeverity.CRITICAL else CauseSeverity.ELEVATED
            val clockTotal = totalDeepSleepMs + totalStandbyAwakeMs
            findings += RootCauseFinding(
                id = "cause_wakelock",
                category = RootCauseCategory.WAKELOCK_STANDBY,
                severity = severity,
                title = "Elevated Screen-Off Standby Drain",
                headlineMetric = String.format(Locale.US, "%.1f%%/hr off", screenOffRatePctHr),
                sharePct = (byComp[HardwareComponentType.CPU_SYSTEM]?.sharePct ?: 25f),
                explanation = "Battery continued draining during pure screen-off intervals. Observed CPU awake time and radio activity can point to background jobs, sync loops, or partial wakelocks limiting Android Doze deep sleep.",
                evidencePoints = buildList {
                    add(String.format(Locale.US, "Pure standby rate: %.1f%%/hr (healthy baseline < 1.0%%/hr)", screenOffRatePctHr))
                    add("Observed across ${formatDuration(screenOffDurationMs)} of pure screen-off time (mixed transitions excluded)")
                    if (clockTotal > 0L) {
                        val sleepPct = (totalDeepSleepMs.toFloat() / clockTotal.toFloat()) * 100f
                        add(
                            String.format(
                                Locale.US,
                                "Kernel deep sleep: %.0f%% (%s asleep / %s CPU awake)",
                                sleepPct,
                                formatDuration(totalDeepSleepMs),
                                formatDuration(totalStandbyAwakeMs)
                            )
                        )
                    }
                    add(String.format(Locale.US, "Pure screen-on rate for comparison: %.1f%%/hr", screenOnRatePctHr))
                },
                recommendation = "Restrict background battery usage for high-wakeup apps and enable Battery Saver overnight.",
                actionLabel = "Battery Optimization",
                settingsAction = SettingsAction.BATTERY_OPTIMIZATION
            )
        }

        // 3. Cellular Radio Overhead vs Wi-Fi
        val cellular = byComp[HardwareComponentType.CELLULAR]
        val cellularPenalty = cellularRatePctHr > 0f && wifiRatePctHr > 0f && cellularRatePctHr > wifiRatePctHr * 1.25f
        if ((cellular != null && cellular.sharePct >= 16f) || cellularPenalty || anchorSample?.cellularActive == true) {
            val share = cellular?.sharePct ?: 0f
            val severity = if (share >= 26f || cellularPenalty) CauseSeverity.ELEVATED else CauseSeverity.MODERATE
            findings += RootCauseFinding(
                id = "cause_cellular",
                category = RootCauseCategory.CELLULAR_RADIO,
                severity = severity,
                title = "Cellular Baseband & Modem Overhead",
                headlineMetric = String.format(Locale.US, "%.0f%% of drain", share),
                sharePct = share,
                explanation = "Cellular data transmission and weak-signal tower handoffs draw substantially more power than Wi-Fi.",
                evidencePoints = buildList {
                    if (cellular != null) {
                        add(String.format(Locale.US, "Cellular attributed %.1f mAh (avg %.0f mW)", cellular.attributedMah, cellular.avgPowerMwWhenActive))
                    }
                    if (cellularRatePctHr > 0f && wifiRatePctHr > 0f) {
                        add(String.format(Locale.US, "Cellular %.1f%%/hr vs Wi-Fi %.1f%%/hr", cellularRatePctHr, wifiRatePctHr))
                    } else if (anchorSample?.cellularActive == true) {
                        add("Device is currently routing active traffic over cellular radio")
                    }
                },
                recommendation = "Prefer Wi-Fi whenever available and disable 5G standalone scanning in weak-signal indoor areas.",
                actionLabel = "Network Settings",
                settingsAction = SettingsAction.NETWORK_SETTINGS
            )
        }

        // 4. Location / GPS Setting Notice (honest distinction between toggle vs GNSS lock)
        val gps = byComp[HardwareComponentType.GPS]
        if (gps != null && gps.isCurrentlyActive && gps.activeDutyPct >= 50f) {
            findings += RootCauseFinding(
                id = "cause_gps",
                category = RootCauseCategory.GPS_LOCATION,
                severity = CauseSeverity.HEALTHY,
                title = "Location Services Enabled",
                headlineMetric = String.format(Locale.US, "%.0f%% toggle ON", gps.activeDutyPct),
                sharePct = gps.sharePct,
                explanation = "The system Location setting remained ON for most of the window. Android exposes only whether the Location provider is enabled — not actual third-party GNSS satellite lock duration.",
                evidencePoints = listOf(
                    String.format(Locale.US, "Location provider enabled %.0f%% of analysis window", gps.activeDutyPct),
                    String.format(Locale.US, "Estimated passive readiness overhead: %.1f mAh (%.0f mW)", gps.attributedMah, gps.avgPowerMwWhenActive)
                ),
                recommendation = "Audit apps with 'Allow all the time' location access in Location Settings to prevent silent background GPS locks.",
                actionLabel = "Location Settings",
                settingsAction = SettingsAction.LOCATION_SETTINGS
            )
        }

        // 5. Bluetooth Radio Overhead
        val bt = byComp[HardwareComponentType.BLUETOOTH]
        if (bt != null && (bt.isCurrentlyActive || bt.sharePct >= 6f)) {
            findings += RootCauseFinding(
                id = "cause_bluetooth",
                category = RootCauseCategory.BLUETOOTH_RADIO,
                severity = if (bt.sharePct >= 10f) CauseSeverity.MODERATE else CauseSeverity.HEALTHY,
                title = "Bluetooth Radio Continuous Duty",
                headlineMetric = String.format(Locale.US, "%.0f%% · %.0f mW", bt.sharePct, bt.avgPowerMwWhenActive),
                sharePct = bt.sharePct,
                explanation = "Active Bluetooth controllers and BLE scanning maintain periodic radio wake intervals even during standby.",
                evidencePoints = listOf(
                    String.format(Locale.US, "Bluetooth enabled %.0f%% of window", bt.activeDutyPct),
                    String.format(Locale.US, "Attributed %.1f mAh to Bluetooth radio", bt.attributedMah)
                ),
                recommendation = "Turn off Bluetooth when not connected to audio devices or wearables, and disable Bluetooth background scanning.",
                actionLabel = "Bluetooth Settings",
                settingsAction = SettingsAction.BLUETOOTH_SETTINGS
            )
        }

        // 6. Wi-Fi Idle Scanning Overhead (enabled but not connected)
        val wifi = byComp[HardwareComponentType.WIFI]
        if (anchorSample != null && anchorSample.wifiEnabled && !anchorSample.wifiConnected) {
            findings += RootCauseFinding(
                id = "cause_wifi_scan",
                category = RootCauseCategory.WIFI_RADIO,
                severity = CauseSeverity.MODERATE,
                title = "Disconnected Wi-Fi Scanning",
                headlineMetric = String.format(Locale.US, "%.0f mW idle", wifi?.avgPowerMwWhenActive ?: 42f),
                sharePct = wifi?.sharePct ?: 4f,
                explanation = "Wi-Fi radio is powered on without an associated access point, triggering periodic probe requests.",
                evidencePoints = listOf(
                    "Wi-Fi adapter is ON while disconnected",
                    String.format(Locale.US, "Wi-Fi subsystem attributed %.1f mAh in window", wifi?.attributedMah ?: 0f)
                ),
                recommendation = "Connect to a nearby Wi-Fi network or toggle Wi-Fi off when traveling outside coverage.",
                actionLabel = "Wi-Fi Settings",
                settingsAction = SettingsAction.WIFI_SETTINGS
            )
        }

        // 7. Thermal Stress & Heat Runaway
        if (peakTempC >= thermalAlertCelsius || (anchorSample?.thermalStatus ?: 0) >= 2) {
            val peakTempTime = peakTempTimestampMs?.let { " at ${formatClockTime(it)}" }.orEmpty()
            val peakState = if (peakTempWasCharging) "while charging" else "while discharging"
            findings += RootCauseFinding(
                id = "cause_thermal",
                category = RootCauseCategory.THERMAL_STRESS,
                severity = if (peakTempC >= thermalAlertCelsius + 3f) CauseSeverity.CRITICAL else CauseSeverity.ELEVATED,
                title = "Thermal Stress & Heat Dissipation",
                headlineMetric = String.format(Locale.US, "%.1f°C peak", peakTempC),
                sharePct = 20f,
                explanation = "Elevated battery temperatures increase internal resistance, reduce usable capacity, and accelerate long-term cell degradation.",
                evidencePoints = buildList {
                    add(
                        String.format(
                            Locale.US,
                            "Peak cell temperature: %.1f°C%s (%s; alert threshold %.1f°C)",
                            peakTempC,
                            peakTempTime,
                            peakState,
                            thermalAlertCelsius
                        )
                    )
                    if (hotDurationMs > 0L) add("Spent ${formatDuration(hotDurationMs)} above thermal threshold")
                },
                recommendation = "Pause intensive rendering/charging together, remove thick thermal cases during fast charging, and cool the device.",
                actionLabel = "Battery Saver",
                settingsAction = SettingsAction.BATTERY_SAVER
            )
        }

        // 8. High-Wattage Discharge Spikes (strictly discharging; excludes charging power)
        if (spikeIntervalCount >= 1 || peakDischargePowerMw >= 3800f) {
            val peakTime = peakDischargePowerTimestampMs?.let { " at ${formatClockTime(it)}" }.orEmpty()
            findings += RootCauseFinding(
                id = "cause_spikes",
                category = RootCauseCategory.HIGH_POWER_SPIKES,
                severity = CauseSeverity.ELEVATED,
                title = "High-Wattage Discharge Spikes",
                headlineMetric = String.format(Locale.US, "-%.2f W peak", peakDischargePowerMw / 1000f),
                sharePct = 18f,
                explanation = "Short discharge bursts above 3.5 W induce voltage sag and rapidly draw down cell charge.",
                evidencePoints = listOf(
                    String.format(Locale.US, "Peak measured discharge: -%.2f W%s (charging excluded)", peakDischargePowerMw / 1000f, peakTime),
                    "Detected $spikeIntervalCount high-current discharging intervals in window"
                ),
                recommendation = "Inspect foreground apps active during spike windows on the Timeline tab.",
                actionLabel = "Battery Saver",
                settingsAction = SettingsAction.BATTERY_SAVER
            )
        }

        // 9. Top App Culprit (when Usage Access provides real attribution backed by measured loss)
        val topApp = appCulprits.firstOrNull()
        if (topApp != null &&
            topApp.hasEnergyEstimate &&
            topApp.estimatedMah > 0.5f &&
            (topApp.shareOfTotalDrainPct >= 10f || (topApp.sharePct >= 30f && topApp.estimatedMah >= 25f))
        ) {
            findings += RootCauseFinding(
                id = "cause_app_${topApp.packageName}",
                category = RootCauseCategory.APP_CULPRIT,
                severity = if (topApp.isHighIntensity && topApp.shareOfTotalDrainPct >= 15f) CauseSeverity.CRITICAL else CauseSeverity.ELEVATED,
                title = "Top Foreground App: ${topApp.appLabel}",
                headlineMetric = String.format(Locale.US, "%.1f%% of total", topApp.shareOfTotalDrainPct),
                sharePct = topApp.shareOfTotalDrainPct,
                explanation = "${topApp.appLabel} accounted for ${String.format(Locale.US, "%.1f%%", topApp.shareOfTotalDrainPct)} of total window discharge (${String.format(Locale.US, "%.1f%%", topApp.sharePct)} of tracked foreground app energy).",
                evidencePoints = listOf(
                    "Foreground time: ${formatDuration(topApp.totalForegroundMs)} (${topApp.wakeupCount} sessions)",
                    String.format(Locale.US, "Estimated %.1f mAh (%.1f%% of total drain · %.1f%% of app pool)", topApp.estimatedMah, topApp.shareOfTotalDrainPct, topApp.sharePct),
                    String.format(Locale.US, "Active intensity: %.1f%%/hr while in foreground", topApp.drainRatePctPerHour)
                ),
                recommendation = "Open App Info for ${topApp.appLabel} to set Battery Usage to Restricted or Optimized.",
                actionLabel = "Inspect App",
                settingsAction = SettingsAction.APP_DETAILS,
                targetPackage = topApp.packageName
            )
        }

        // Healthy fallback if no critical or elevated issues found
        if (findings.isEmpty()) {
            findings += RootCauseFinding(
                id = "cause_healthy",
                category = RootCauseCategory.HEALTHY_PROFILE,
                severity = CauseSeverity.HEALTHY,
                title = "Balanced Hardware Power Profile",
                headlineMetric = "Nominal",
                sharePct = 0f,
                explanation = "No abnormal hardware drain, wakelock loops, or thermal excursions were detected in the selected window.",
                evidencePoints = listOf(
                    String.format(Locale.US, "Peak temperature remained healthy at %.1f°C", peakTempC),
                    "Hardware radios and display are operating within normal power budgets"
                ),
                recommendation = "Keep 15-minute background telemetry enabled to catch overnight standby or app regressions.",
                actionLabel = null,
                settingsAction = SettingsAction.NONE
            )
        }

        return findings.sortedWith(
            compareBy<RootCauseFinding> {
                when (it.severity) {
                    CauseSeverity.CRITICAL -> 0
                    CauseSeverity.ELEVATED -> 1
                    CauseSeverity.MODERATE -> 2
                    CauseSeverity.HEALTHY -> 3
                }
            }.thenByDescending { it.sharePct }
        )
    }

    private fun buildInitialSnapshotReport(
        sample: BatterySampleEntity?,
        liveAppUsage: List<ObservedAppUsage>,
        window: AnalysisWindow,
        ratedCapacityMah: Int,
        thermalAlertCelsius: Float
    ): BatteryAnalysisReport {
        val hw = buildHardwareBreakdown(
            anchorSample = sample,
            totalDischargedMah = 0f,
            spanMs = 1L,
            componentMah = emptyMap(),
            componentWh = emptyMap(),
            componentActiveMs = emptyMap(),
            componentPowerTimeSum = emptyMap(),
            hasRealDischarge = false
        )
        val liveRate = sample?.let { estimateRateFromLiveSample(it, ratedCapacityMah) } ?: 0f
        val appCulprits = buildAppAttribution(
            appEvents = emptyList(),
            liveAppUsage = liveAppUsage,
            totalDischargedMah = 0f,
            avgPowerMw = sample?.powerMw?.coerceAtLeast(350f) ?: 450f,
            ratedCapacityMah = ratedCapacityMah
        )
        val findings = diagnoseRootCauses(
            anchorSample = sample,
            hardwareBreakdown = hw,
            screenOnRatePctHr = liveRate,
            screenOffRatePctHr = 0f,
            wifiRatePctHr = 0f,
            cellularRatePctHr = 0f,
            highBrightnessMs = 0L,
            highRefreshMs = 0L,
            hotDurationMs = 0L,
            peakTempC = sample?.temperatureCelsius ?: 0f,
            peakTempTimestampMs = sample?.timestampMs,
            peakTempWasCharging = sample?.isCharging ?: false,
            thermalAlertCelsius = thermalAlertCelsius,
            peakDischargePowerMw = if (sample?.isCharging == false) sample.powerMw else 0f,
            peakDischargePowerTimestampMs = if (sample?.isCharging == false) sample.timestampMs else null,
            spikeIntervalCount = 0,
            spanMs = 1L,
            screenOffDurationMs = 0L,
            totalDeepSleepMs = 0L,
            totalStandbyAwakeMs = 0L,
            appCulprits = appCulprits
        )
        val remaining = if (sample != null && !sample.isCharging && liveRate > 0.3f) {
            (sample.levelPct / liveRate).coerceIn(0.1f, 96f)
        } else null

        return BatteryAnalysisReport(
            window = window,
            sampleCount = if (sample != null) 1 else 0,
            spanDurationMs = 0L,
            totalDischargedPct = 0f,
            totalDischargedMah = 0f,
            overallDrainRatePctPerHour = liveRate,
            screenOnDrainRatePctPerHour = if (sample?.isScreenOn == true) liveRate else 0f,
            screenOffDrainRatePctPerHour = 0f,
            wifiDrainRatePctPerHour = if (sample?.wifiConnected == true) liveRate else 0f,
            cellularDrainRatePctPerHour = if (sample?.cellularActive == true) liveRate else 0f,
            avgPowerMw = sample?.powerMw ?: 0f,
            peakPowerMw = sample?.powerMw ?: 0f,
            avgTempC = sample?.temperatureCelsius ?: 0f,
            peakTempC = sample?.temperatureCelsius ?: 0f,
            minVoltageMv = sample?.voltageMv ?: 0,
            maxVoltageMv = sample?.voltageMv ?: 0,
            screenOnDurationMs = 0L,
            screenOffDurationMs = 0L,
            peakDischargePowerMw = if (sample?.isCharging == false) sample.powerMw else 0f,
            peakDischargePowerTimestampMs = if (sample?.isCharging == false) sample.timestampMs else null,
            peakChargePowerMw = if (sample?.isCharging == true) sample.powerMw else 0f,
            peakChargePowerTimestampMs = if (sample?.isCharging == true) sample.timestampMs else null,
            peakTempTimestampMs = sample?.timestampMs,
            peakTempWasCharging = sample?.isCharging ?: false,
            estimatedRemainingHours = remaining,
            efficiencyScore = computeEfficiencyScore(
                overallDrainPctHr = liveRate,
                screenOffRatePctHr = 0f,
                peakTempC = sample?.temperatureCelsius ?: 25f,
                thermalAlertCelsius = thermalAlertCelsius,
                hardwareBreakdown = hw
            ),
            hardwareBreakdown = hw,
            findings = findings,
            appCulprits = appCulprits,
            hasLongitudinalData = false
        )
    }

    private fun estimateRateFromLiveSample(sample: BatterySampleEntity, ratedCapacityMah: Int): Float {
        if (sample.isCharging) return 0f
        val currentMa = if (sample.currentAbsMa > 15f) {
            sample.currentAbsMa
        } else {
            val estMw = estimateInstantHardwareMw(sample).values.sum() + 120f
            estMw / sample.voltageVolts.coerceIn(3.5f, 4.4f)
        }
        return ((currentMa / ratedCapacityMah.coerceAtLeast(1000)) * 100f).coerceIn(0f, 95f)
    }

    private fun computeEfficiencyScore(
        overallDrainPctHr: Float,
        screenOffRatePctHr: Float,
        peakTempC: Float,
        thermalAlertCelsius: Float,
        hardwareBreakdown: List<HardwareComponentAttribution>
    ): Int {
        var score = 100
        if (overallDrainPctHr > 18f) score -= 24
        else if (overallDrainPctHr > 12f) score -= 14
        else if (overallDrainPctHr > 8f) score -= 6

        if (screenOffRatePctHr >= 3.0f) score -= 25
        else if (screenOffRatePctHr >= 1.5f) score -= 14

        if (peakTempC >= thermalAlertCelsius + 3f) score -= 20
        else if (peakTempC >= thermalAlertCelsius) score -= 10

        val gpsShare = hardwareBreakdown.firstOrNull { it.component == HardwareComponentType.GPS }?.sharePct ?: 0f
        if (gpsShare >= 14f) score -= 8

        return score.coerceIn(15, 100)
    }

    private fun averagePositive(a: Float, b: Float): Float = when {
        a > 0f && b > 0f -> (a + b) / 2f
        a > 0f -> a
        b > 0f -> b
        else -> 0f
    }

    fun formatDuration(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return when {
            hours > 0L -> "${hours}h ${minutes}m"
            minutes > 0L -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun formatClockTime(timestampMs: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
    }

    fun formatIsoUtc(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestampMs))
    }
}
