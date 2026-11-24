package com.example

import com.example.data.local.BatterySampleEntity
import com.example.domain.AnalysisWindow
import com.example.domain.HardwareComponentType
import com.example.domain.RootCauseAnalyzer
import com.example.domain.RootCauseCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCauseAnalyzerTest {

    private fun sample(
        timestampMs: Long,
        levelPct: Int,
        voltageMv: Int = 3900,
        tempDeciC: Int = 330,
        currentMicroA: Long = -650_000L,
        chargeCounterMicroAh: Long = 0L,
        isCharging: Boolean = false,
        isScreenOn: Boolean = true,
        brightnessPct: Int = 85,
        refreshHz: Float = 120f,
        gpsEnabled: Boolean = true,
        wifiEnabled: Boolean = true,
        wifiConnected: Boolean = false,
        bluetoothEnabled: Boolean = true,
        cellularActive: Boolean = true
    ) = BatterySampleEntity(
        id = timestampMs,
        timestampMs = timestampMs,
        levelPct = levelPct,
        voltageMv = voltageMv,
        temperatureDeciC = tempDeciC,
        currentMicroA = currentMicroA,
        chargeCounterMicroAh = chargeCounterMicroAh,
        isCharging = isCharging,
        pluggedType = if (isCharging) "AC" else "BATTERY",
        healthCode = 2,
        technology = "Li-ion",
        isScreenOn = isScreenOn,
        isPowerSaveMode = false,
        thermalStatus = if (tempDeciC >= 400) 2 else 0,
        networkTransport = if (cellularActive) "CELLULAR" else if (wifiConnected) "WIFI" else "OFFLINE",
        screenBrightnessPct = brightnessPct,
        displayRefreshRateHz = refreshHz,
        displayAutoBrightness = false,
        gpsEnabled = gpsEnabled,
        wifiEnabled = wifiEnabled,
        wifiConnected = wifiConnected,
        bluetoothEnabled = bluetoothEnabled,
        cellularActive = cellularActive,
        airplaneModeOn = false,
        foregroundPackage = null,
        foregroundLabel = null,
        source = "TEST"
    )

    @Test
    fun attributesAllHardwareSubsystemsAndDetectsDisplayAndCellularDrivers() {
        val base = 1_700_000_000_000L
        val samples = listOf(
            sample(base, levelPct = 80, chargeCounterMicroAh = 3_600_000L, tempDeciC = 410),
            sample(base + 15 * 60_000L, levelPct = 75, chargeCounterMicroAh = 3_375_000L, tempDeciC = 415),
            sample(base + 30 * 60_000L, levelPct = 70, chargeCounterMicroAh = 3_150_000L, tempDeciC = 412),
            sample(base + 45 * 60_000L, levelPct = 65, chargeCounterMicroAh = 2_925_000L, tempDeciC = 408)
        )

        val report = RootCauseAnalyzer.analyze(
            samples = samples,
            latestSample = samples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.TWENTY_FOUR_HOURS,
            ratedCapacityMah = 4500,
            thermalAlertCelsius = 39.0f
        )

        assertTrue(report.hasLongitudinalData)
        assertEquals(6, report.hardwareBreakdown.size)

        val totalShare = report.hardwareBreakdown.sumOf { it.sharePct.toDouble() }
        assertTrue("Hardware attribution shares should sum to ~100%, got $totalShare", totalShare in 98.0..102.0)

        val byType = report.hardwareBreakdown.associateBy { it.component }
        assertTrue((byType[HardwareComponentType.DISPLAY]?.attributedMah ?: 0f) > 0f)
        assertTrue((byType[HardwareComponentType.CELLULAR]?.attributedMah ?: 0f) > 0f)
        assertTrue((byType[HardwareComponentType.GPS]?.attributedMah ?: 0f) > 0f)
        assertTrue((byType[HardwareComponentType.BLUETOOTH]?.attributedMah ?: 0f) > 0f)
        assertTrue((byType[HardwareComponentType.WIFI]?.attributedMah ?: 0f) > 0f)

        val categories = report.findings.map { it.category }
        assertTrue(categories.contains(RootCauseCategory.DISPLAY_POWER))
        assertTrue(categories.contains(RootCauseCategory.THERMAL_STRESS))
    }

    @Test
    fun detectsScreenOffStandbyWakelockDrain() {
        val base = 1_700_000_000_000L
        val samples = listOf(
            sample(base, levelPct = 90, isScreenOn = false, currentMicroA = -220_000L, chargeCounterMicroAh = 4_050_000L, gpsEnabled = false, bluetoothEnabled = false, cellularActive = false, wifiConnected = true),
            sample(base + 30 * 60_000L, levelPct = 88, isScreenOn = false, currentMicroA = -220_000L, chargeCounterMicroAh = 3_960_000L, gpsEnabled = false, bluetoothEnabled = false, cellularActive = false, wifiConnected = true),
            sample(base + 60 * 60_000L, levelPct = 86, isScreenOn = false, currentMicroA = -220_000L, chargeCounterMicroAh = 3_870_000L, gpsEnabled = false, bluetoothEnabled = false, cellularActive = false, wifiConnected = true),
            sample(base + 90 * 60_000L, levelPct = 84, isScreenOn = false, currentMicroA = -220_000L, chargeCounterMicroAh = 3_780_000L, gpsEnabled = false, bluetoothEnabled = false, cellularActive = false, wifiConnected = true)
        )

        val report = RootCauseAnalyzer.analyze(
            samples = samples,
            latestSample = samples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.SIX_HOURS,
            ratedCapacityMah = 4500,
            thermalAlertCelsius = 39.0f
        )

        assertTrue(report.screenOffDrainRatePctPerHour >= 3.5f)
        assertTrue(report.findings.any { it.category == RootCauseCategory.WAKELOCK_STANDBY })
        assertEquals(1, report.sleepSessions.size)
        assertEquals("Severe Standby Drain", report.sleepSessions.first().verdict)

        val markdown = com.example.domain.DiagnosticReportGenerator.generateMarkdownSummary(
            report = report,
            latestSample = samples.last()
        )
        assertTrue(markdown.contains("VoltTrace Battery Forensic Report"))
        assertTrue(markdown.contains("Sleep & Standby Forensic Audit"))

        val csv = com.example.domain.DiagnosticReportGenerator.generateCsvTelemetry(samples)
        val lines = csv.trim().lines()
        assertEquals(samples.size + 1, lines.size)
        assertTrue(csv.trim().lines().first().contains("link_down_kbps"))
        assertTrue(csv.trim().lines().first().contains("current_signed_ma"))
        assertTrue(csv.trim().lines().first().contains("elapsed_realtime_ms"))
    }

    @Test
    fun normalizesSamsungMilliampUnitsAndSignedPowerDirection() {
        val base = 1_700_000_000_000L
        // Legacy OEM HAL value: 4300 mA fast charging stored as 4300L
        val fastChargeSample = sample(
            timestampMs = base,
            levelPct = 80,
            voltageMv = 4375,
            currentMicroA = 4_300L,
            isCharging = true
        )
        assertEquals(4300f, fastChargeSample.currentAbsMa, 0.1f)
        assertEquals(4300f, fastChargeSample.currentSignedMa, 0.1f)
        assertTrue("Expected ~18.8 W charging power, got ${fastChargeSample.powerSignedWatts}", fastChargeSample.powerSignedWatts in 18.5f..19.1f)

        // Samsung discharging value: -2900 mA stored as -2900L
        val heavyDischargeSample = sample(
            timestampMs = base + 60_000L,
            levelPct = 79,
            voltageMv = 4030,
            currentMicroA = -2_900L,
            isCharging = false
        )
        assertEquals(2900f, heavyDischargeSample.currentAbsMa, 0.1f)
        assertEquals(-2900f, heavyDischargeSample.currentSignedMa, 0.1f)
        assertTrue("Expected ~-11.7 W discharge power, got ${heavyDischargeSample.powerSignedWatts}", heavyDischargeSample.powerSignedWatts in -12.0f..-11.4f)
    }

    @Test
    fun excludesMixedScreenTransitionsFromStandbyRateAndTracksChargingAndDeepSleep() {
        val base = 1_700_000_000_000L
        val elapsedBase = 10_000_000L
        val uptimeBase = 5_000_000L

        val samples = listOf(
            // Pure standby block: 60 min screen-off, drops 90 mAh (~1.8%/hr on 5000 mAh), 80% deep sleep
            sample(
                timestampMs = base,
                levelPct = 80,
                tempDeciC = 295,
                currentMicroA = -150_000L,
                chargeCounterMicroAh = 4_000_000L,
                isCharging = false,
                isScreenOn = false,
                gpsEnabled = true,
                bluetoothEnabled = false,
                cellularActive = false,
                wifiConnected = true
            ).copy(elapsedRealtimeMs = elapsedBase, uptimeMs = uptimeBase),
            sample(
                timestampMs = base + 60 * 60_000L,
                levelPct = 78,
                tempDeciC = 290,
                currentMicroA = -150_000L,
                chargeCounterMicroAh = 3_910_000L,
                isCharging = false,
                isScreenOn = false,
                gpsEnabled = true,
                bluetoothEnabled = false,
                cellularActive = false,
                wifiConnected = true
            ).copy(
                elapsedRealtimeMs = elapsedBase + 60 * 60_000L,
                uptimeMs = uptimeBase + 12 * 60_000L // 12m CPU awake, 48m deep sleep (80%)
            ),
            // Coarse mixed transition: screen_off -> screen_on across 15 min, drops 150 mAh
            // MUST NOT inflate pure screenOffDrainRatePctPerHour!
            sample(
                timestampMs = base + 75 * 60_000L,
                levelPct = 75,
                tempDeciC = 348,
                currentMicroA = -1_500_000L,
                chargeCounterMicroAh = 3_760_000L,
                isCharging = false,
                isScreenOn = true,
                gpsEnabled = true,
                bluetoothEnabled = false,
                cellularActive = false,
                wifiConnected = true
            ).copy(
                elapsedRealtimeMs = elapsedBase + 75 * 60_000L,
                uptimeMs = uptimeBase + 27 * 60_000L
            ),
            // Charging interval: 30 min on AC fast charge (+1500 mAh, 18.8 W peak)
            sample(
                timestampMs = base + 105 * 60_000L,
                levelPct = 95,
                voltageMv = 4375,
                tempDeciC = 340,
                currentMicroA = 4_300_000L,
                chargeCounterMicroAh = 4_750_000L,
                isCharging = true,
                isScreenOn = false,
                gpsEnabled = true,
                bluetoothEnabled = false,
                cellularActive = false,
                wifiConnected = true
            ).copy(
                elapsedRealtimeMs = elapsedBase + 105 * 60_000L,
                uptimeMs = uptimeBase + 57 * 60_000L
            )
        )

        val report = RootCauseAnalyzer.analyze(
            samples = samples,
            latestSample = samples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.SIX_HOURS,
            ratedCapacityMah = 5000,
            thermalAlertCelsius = 39.0f
        )

        // Pure standby rate stays near ~2.0%/hr, NOT polluted by the 15-min 150 mAh mixed transition
        assertTrue(
            "Pure standby rate should remain <= 2.3%/hr, got ${report.screenOffDrainRatePctPerHour}",
            report.screenOffDrainRatePctPerHour in 1.7f..2.3f
        )
        assertEquals(60 * 60_000L, report.screenOffDurationMs)
        assertEquals(15 * 60_000L, report.mixedTransitionDurationMs)
        assertEquals(30 * 60_000L, report.chargingDurationMs)
        assertTrue("Expected charged mAh > 900, got ${report.totalChargedMah}", report.totalChargedMah >= 900f)

        // Peak discharge vs peak charge properly separated with timestamps
        assertTrue("Peak charge should be ~18.8 W", report.peakChargePowerMw > 18_000f)
        assertTrue("Peak discharge must exclude 18.8 W charge", report.peakDischargePowerMw < 10_000f)
        assertEquals(base + 75 * 60_000L, report.peakTempTimestampMs)
        assertEquals(false, report.peakTempWasCharging)

        // Kernel deep sleep verified on both report and sleep session
        assertEquals(48 * 60_000L, report.totalDeepSleepMs)
        assertEquals(12 * 60_000L, report.totalStandbyAwakeMs)
        assertEquals(1, report.sleepSessions.size)
        assertEquals(80f, report.sleepSessions.first().deepSleepPct ?: 0f, 0.5f)

        val md = com.example.domain.DiagnosticReportGenerator.generateMarkdownSummary(report, samples.last())
        assertTrue(md.contains("Window Time Budget"))
        assertTrue(md.contains("Mixed Screen Transitions"))
        assertTrue(md.contains("Kernel Standby Deep Sleep"))
        assertTrue(md.contains("Peak Charge +"))
    }

    @Test
    fun mergesOverlappingAppUsageIntervalsStrictlyWithinWindow() {
        val merged = com.example.data.hardware.BatteryHardwareReader.mergeIntervalsDuration(
            listOf(
                1000L to 5000L,
                3000L to 8000L,
                10000L to 12000L,
                11000L to 11500L
            )
        )
        // [1000..8000] = 7000 ms + [10000..12000] = 2000 ms => 9000 ms
        assertEquals(9000L, merged)
    }

    @Test
    fun healsLegacyPhantomTrickleChargeScalingAndPreservesNormalMicroAmps() {
        val base = 1_700_000_000_000L
        // Legacy corrupted Room row where 14,784 µA was mis-multiplied by 1000 -> 14,784,000 µA (64.68 W!)
        val corruptedTrickleRow = sample(
            timestampMs = base,
            levelPct = 98,
            voltageMv = 4375,
            currentMicroA = 14_784_000L,
            isCharging = true
        )
        assertEquals(14.784f, corruptedTrickleRow.currentAbsMa, 0.05f)
        assertTrue(
            "Healed trickle wattage should be ~0.06 W, not 64.68 W; got ${corruptedTrickleRow.powerWatts}",
            corruptedTrickleRow.powerWatts < 0.2f
        )

        // Fresh µA reading during trickle charge: 14,784 µA = 14.784 mA (~0.065 W)
        val cleanMicroAmpRow = sample(
            timestampMs = base + 60_000L,
            levelPct = 98,
            voltageMv = 4375,
            currentMicroA = 14_784L,
            isCharging = true
        )
        assertEquals(14.784f, cleanMicroAmpRow.currentAbsMa, 0.05f)
        assertTrue("Clean trickle wattage should be ~0.065 W, got ${cleanMicroAmpRow.powerWatts}", cleanMicroAmpRow.powerWatts in 0.04f..0.1f)
    }

    @Test
    fun suppressesNoisyStandbyRateForVeryShortScreenOffSnippets() {
        val base = 1_700_000_000_000L
        // Only 2 minutes of screen-off time with a 1% SOC step (would extrapolate to 30%/hr if unsuppressed)
        val samples = listOf(
            sample(base, levelPct = 98, isScreenOn = false, isCharging = false, currentMicroA = -120_000L),
            sample(base + 2 * 60_000L, levelPct = 97, isScreenOn = false, isCharging = false, currentMicroA = -120_000L)
        )
        val report = RootCauseAnalyzer.analyze(
            samples = samples,
            latestSample = samples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.SIX_HOURS,
            ratedCapacityMah = 5000,
            thermalAlertCelsius = 39.0f
        )
        assertEquals(2 * 60_000L, report.screenOffDurationMs)
        assertEquals(0f, report.screenOffDrainRatePctPerHour, 0.01f)
    }

    @Test
    fun spikeInspectorDetectsStandbyDrainSpikeAndCorrelatesBackgroundService() {
        val base = 1_700_000_000_000L
        val elapsedBase = 10_000_000L
        val uptimeBase = 4_000_000L

        val samples = listOf(
            // 0: Charging at 1:13 AM — must NOT be flagged as a discharging drain spike
            sample(
                timestampMs = base,
                levelPct = 98,
                voltageMv = 4375,
                currentMicroA = 1_500_000L,
                chargeCounterMicroAh = 4_900_000L,
                isCharging = true,
                isScreenOn = false
            ).copy(elapsedRealtimeMs = elapsedBase, uptimeMs = uptimeBase),
            // 1: Unplugged calm standby at 1:23 AM
            sample(
                timestampMs = base + 10 * 60_000L,
                levelPct = 98,
                voltageMv = 4280,
                currentMicroA = -110_000L,
                chargeCounterMicroAh = 4_890_000L,
                isCharging = false,
                isScreenOn = false,
                cellularActive = false,
                wifiConnected = true
            ).copy(
                elapsedRealtimeMs = elapsedBase + 10 * 60_000L,
                uptimeMs = uptimeBase + 1 * 60_000L
            ),
            // 2: Screen-off standby spike at 1:33 AM (-3.4 W, CPU awake 90%, counter drops 55 mAh)
            sample(
                timestampMs = base + 20 * 60_000L,
                levelPct = 97,
                voltageMv = 4200,
                tempDeciC = 345,
                currentMicroA = -810_000L,
                chargeCounterMicroAh = 4_835_000L,
                isCharging = false,
                isScreenOn = false,
                cellularActive = false,
                wifiConnected = true
            ).copy(
                elapsedRealtimeMs = elapsedBase + 20 * 60_000L,
                uptimeMs = uptimeBase + 10 * 60_000L // 9 min awake out of 10 min => 90% CPU awake
            ),
            // 3: Returns to idle at 1:43 AM
            sample(
                timestampMs = base + 30 * 60_000L,
                levelPct = 97,
                voltageMv = 4260,
                tempDeciC = 310,
                currentMicroA = -100_000L,
                chargeCounterMicroAh = 4_825_000L,
                isCharging = false,
                isScreenOn = false,
                cellularActive = false,
                wifiConnected = true
            ).copy(
                elapsedRealtimeMs = elapsedBase + 30 * 60_000L,
                uptimeMs = uptimeBase + 11 * 60_000L
            )
        )

        val markers = com.example.domain.SpikeInspector.detectNotableSpikes(samples)
        assertEquals(1, markers.size)
        assertEquals(2, markers.first().sampleIndex)
        assertEquals("Screen-Off Spike", markers.first().reasonTag)

        val bgEvent = com.example.domain.WindowUsageEventItem(
            timestampMs = base + 19 * 60_000L,
            packageName = "com.example.syncservice",
            appLabel = "Sample Sync App",
            eventLabel = "Foreground Service Started",
            isBackgroundService = true
        )

        val drillDown = com.example.domain.SpikeInspector.analyzeSpike(
            samples = samples,
            targetIndex = 2,
            usageEvents = listOf(bgEvent),
            appUsage = emptyList(),
            appDrainEvents = emptyList(),
            energyMeters = emptyList(),
            hasUsageAccess = true,
            ratedCapacityMah = 5000
        )

        assertEquals("Screen-Off Standby Power Spike", drillDown.classification)
        assertEquals(55f, drillDown.chargeLostBeforeMah ?: 0f, 0.5f)
        assertTrue(drillDown.counterMeasured)
        assertEquals(90f, drillDown.awakePctBefore ?: 0f, 1.0f)
        assertTrue(drillDown.likelyDrivers.any { it.contains("Sample Sync App") })
        assertTrue(drillDown.likelyDrivers.any { it.contains("90%") })
        assertTrue(drillDown.stateChanges.any { it.contains("-80 mV") })
        assertTrue(drillDown.whatRemainsUnknown.isNotEmpty())
        assertEquals(3, drillDown.sourceReadings.size)

        // Charging sample must clearly classify as Charging Current (Not Battery Drain)
        val chargingDrillDown = com.example.domain.SpikeInspector.analyzeSpike(
            samples = samples,
            targetIndex = 0,
            usageEvents = emptyList(),
            appUsage = emptyList(),
            appDrainEvents = emptyList(),
            energyMeters = emptyList(),
            hasUsageAccess = true,
            ratedCapacityMah = 5000
        )
        assertEquals("Charging Current (Not Battery Drain)", chargingDrillDown.classification)
    }
}
