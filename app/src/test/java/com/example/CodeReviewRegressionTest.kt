package com.example

import com.example.data.hardware.BatteryHardwareReader
import com.example.data.hardware.BatteryTelemetryWorker
import com.example.data.hardware.ObservedAppUsage
import com.example.data.local.AppDrainEventEntity
import com.example.data.local.BatterySampleEntity
import com.example.data.local.EnergyMeterSnapshotEntity
import com.example.domain.AnalysisWindow
import com.example.domain.ChargeEvidenceSource
import com.example.domain.DiagnosticReportGenerator
import com.example.domain.OvernightReplayEngine
import com.example.domain.OvernightReportMode
import com.example.domain.RootCauseAnalyzer
import com.example.domain.SpikeInspector
import com.example.domain.WatchInvestigationConfig
import com.example.ui.components.nearestSampleIndexForTouchX
import com.example.ui.components.timelineTimestampToX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure-JVM regression suite directly reproducing every functional and analytical finding
 * from the VoltTrace code review using synthetic telemetry fixtures.
 */
class CodeReviewRegressionTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun utcTime(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long {
        return Calendar.getInstance(utc).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun sample(
        timestampMs: Long,
        levelPct: Int,
        voltageMv: Int = 4000,
        tempDeciC: Int = 290,
        currentMicroA: Long = -100_000L,
        chargeCounterMicroAh: Long = 0L,
        isCharging: Boolean = false,
        isScreenOn: Boolean = false,
        elapsedRealtimeMs: Long = 0L,
        uptimeMs: Long = 0L,
        foregroundPackage: String? = null,
        foregroundLabel: String? = null
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
        thermalStatus = 0,
        networkTransport = "WIFI",
        screenBrightnessPct = if (isScreenOn) 50 else 0,
        displayRefreshRateHz = 60f,
        displayAutoBrightness = true,
        gpsEnabled = false,
        wifiEnabled = true,
        wifiConnected = true,
        bluetoothEnabled = false,
        cellularActive = false,
        airplaneModeOn = false,
        foregroundPackage = foregroundPackage,
        foregroundLabel = foregroundLabel,
        source = "TEST",
        elapsedRealtimeMs = elapsedRealtimeMs,
        uptimeMs = uptimeMs
    )

    // Finding 1a: Morning report must clip daytime drain (12:00 100% -> 21:00 65% -> 07:00 60% = 5%, NOT 40%)
    @Test
    fun morningReportClipsDaytimeDrainToLocalNightBoundaries() {
        val samples = listOf(
            sample(utcTime(2026, 11, 20, 12, 0), levelPct = 100, isScreenOn = true, chargeCounterMicroAh = 4_500_000L),
            sample(utcTime(2026, 11, 20, 15, 0), levelPct = 88, isScreenOn = true, chargeCounterMicroAh = 3_960_000L),
            sample(utcTime(2026, 11, 20, 18, 0), levelPct = 76, isScreenOn = true, chargeCounterMicroAh = 3_420_000L),
            sample(utcTime(2026, 11, 20, 21, 0), levelPct = 65, isScreenOn = false, chargeCounterMicroAh = 2_925_000L),
            sample(utcTime(2026, 11, 21, 0, 30), levelPct = 63, isScreenOn = false, chargeCounterMicroAh = 2_835_000L),
            sample(utcTime(2026, 11, 21, 4, 0), levelPct = 61, isScreenOn = false, chargeCounterMicroAh = 2_745_000L),
            sample(utcTime(2026, 11, 21, 7, 0), levelPct = 60, isScreenOn = false, chargeCounterMicroAh = 2_700_000L)
        )

        val report = OvernightReplayEngine.analyze(
            allSamples = samples,
            ratedCapacityMah = 4500,
            nowMs = utcTime(2026, 11, 21, 7, 15),
            timeZone = utc
        )

        assertEquals(OvernightReportMode.OVERNIGHT_STANDBY, report.mode)
        assertEquals(65, report.startLevelPct)
        assertEquals(60, report.endLevelPct)
        assertEquals(5, report.levelLostPct)
        assertEquals(225f, report.chargeLostMah, 1.0f)
        assertEquals(utcTime(2026, 11, 20, 21, 0), report.windowStartMs)
        assertEquals(utcTime(2026, 11, 21, 7, 0), report.windowEndMs)
    }

    // Finding 1b: Continuous unplugged session spanning two nights splits cleanly into Night 1 (baseline) and Night 2 (current)
    @Test
    fun multiNightUnpluggedSessionSelectsMostRecentLocalNightAndUsesPriorNightForBaseline() {
        val samples = listOf(
            // Night 1: Nov 20 21:00 (95%) -> Nov 21 07:00 (91%) = 4%
            sample(utcTime(2026, 11, 20, 21, 0), levelPct = 95, isScreenOn = false, chargeCounterMicroAh = 4_275_000L),
            sample(utcTime(2026, 11, 21, 0, 30), levelPct = 93, isScreenOn = false, chargeCounterMicroAh = 4_185_000L),
            sample(utcTime(2026, 11, 21, 4, 0), levelPct = 92, isScreenOn = false, chargeCounterMicroAh = 4_140_000L),
            sample(utcTime(2026, 11, 21, 7, 0), levelPct = 91, isScreenOn = false, chargeCounterMicroAh = 4_095_000L),
            // Daytime Nov 21: 10:00 -> 18:00 drops 90% -> 55% (must be excluded from both nights)
            sample(utcTime(2026, 11, 21, 10, 0), levelPct = 90, isScreenOn = true, chargeCounterMicroAh = 4_050_000L),
            sample(utcTime(2026, 11, 21, 13, 30), levelPct = 75, isScreenOn = true, chargeCounterMicroAh = 3_375_000L),
            sample(utcTime(2026, 11, 21, 17, 0), levelPct = 60, isScreenOn = true, chargeCounterMicroAh = 2_700_000L),
            // Night 2: Nov 21 21:00 (55%) -> Nov 22 07:00 (48%) = 7%
            sample(utcTime(2026, 11, 21, 21, 0), levelPct = 55, isScreenOn = false, chargeCounterMicroAh = 2_475_000L),
            sample(utcTime(2026, 11, 22, 0, 30), levelPct = 53, isScreenOn = false, chargeCounterMicroAh = 2_385_000L),
            sample(utcTime(2026, 11, 22, 4, 0), levelPct = 50, isScreenOn = false, chargeCounterMicroAh = 2_250_000L),
            sample(utcTime(2026, 11, 22, 7, 0), levelPct = 48, isScreenOn = false, chargeCounterMicroAh = 2_160_000L)
        )

        val report = OvernightReplayEngine.analyze(
            allSamples = samples,
            ratedCapacityMah = 4500,
            nowMs = utcTime(2026, 11, 22, 7, 15),
            timeZone = utc
        )

        assertEquals(OvernightReportMode.OVERNIGHT_STANDBY, report.mode)
        assertEquals(55, report.startLevelPct)
        assertEquals(48, report.endLevelPct)
        assertEquals(7, report.levelLostPct)
        assertEquals(1, report.baseline.nightsCount)
    }

    // Finding 2: Single sample + 1 app must NOT invent 225 mAh / 100% drain
    @Test
    fun zeroLossWindowDoesNotAssignFabricatedCapacityToApps() {
        val single = sample(utcTime(2026, 11, 22, 9, 0), levelPct = 80, isScreenOn = true)
        val report = RootCauseAnalyzer.analyze(
            samples = listOf(single),
            latestSample = single,
            appEvents = emptyList(),
            liveAppUsage = listOf(
                ObservedAppUsage(
                    packageName = "com.example.reader",
                    appLabel = "Sample Reader",
                    foregroundMs = 15L * 60_000L,
                    launchCount = 2
                )
            ),
            window = AnalysisWindow.ONE_HOUR,
            ratedCapacityMah = 4500,
            thermalAlertCelsius = 39f
        )

        assertEquals(0f, report.totalDischargedMah, 0.01f)
        assertEquals(1, report.appCulprits.size)
        val app = report.appCulprits.first()
        assertFalse("Must not mark usage-only row as energy-estimated", app.hasEnergyEstimate)
        assertEquals(0f, app.estimatedMah, 0.001f)
        assertEquals(0f, app.sharePct, 0.001f)
        assertEquals(0f, app.shareOfTotalDrainPct, 0.001f)
        assertEquals(15L * 60_000L, app.totalForegroundMs)
        assertTrue(report.findings.none { it.category == com.example.domain.RootCauseCategory.APP_CULPRIT })

        val md = DiagnosticReportGenerator.generateMarkdownSummary(report, single)
        assertTrue(md.contains("Usage only"))
        assertFalse(md.contains("225.0"))
    }

    // Finding 3: 8 mA @ 4 V = 0.032 W both before and after HAL calibration (never 32 W or 0 W)
    @Test
    fun lowPowerCurrentPreservesSignedMicroAmpsBeforeAndAfterCalibration() {
        val raw8mAInMicroAmps = -8_000L

        val uncalibrated = BatteryHardwareReader.normalizeCurrentReadingToMicroAmps(
            raw = raw8mAInMicroAmps,
            isCharging = false,
            halUsesMicroAmps = false,
            isLegacyMilliampHal = false
        )
        val calibrated = BatteryHardwareReader.normalizeCurrentReadingToMicroAmps(
            raw = raw8mAInMicroAmps,
            isCharging = false,
            halUsesMicroAmps = true,
            isLegacyMilliampHal = false
        )

        assertEquals(-8_000L, uncalibrated)
        assertEquals(-8_000L, calibrated)

        val uncalSample = sample(utcTime(2026, 11, 22, 2, 0), levelPct = 80, voltageMv = 4000, currentMicroA = uncalibrated)
        val calSample = sample(utcTime(2026, 11, 22, 2, 1), levelPct = 80, voltageMv = 4000, currentMicroA = calibrated)

        assertEquals(8.0f, uncalSample.currentAbsMa, 0.01f)
        assertEquals(0.032f, uncalSample.powerWatts, 0.001f)
        assertEquals(-0.032f, uncalSample.powerSignedWatts, 0.001f)
        assertEquals(0.032f, calSample.powerWatts, 0.001f)
    }

    // Finding 4: Phone without charge counter: 10 min @ 270 mA on 4,500 mAh, 80% -> 79% = 1.0% loss & 6.0%/hr
    @Test
    fun nonCounterBackedDischargeReconcilesGaugeOncePerPeriodWithoutDoubleCounting() {
        val base = utcTime(2026, 11, 22, 3, 0)
        // 10 one-minute intervals at -270 mA @ 4.0 V (45 mAh total = 1.0% of 4500 mAh); level ticks 80 -> 79 on final minute
        val samples = (0..10).map { min ->
            sample(
                timestampMs = base + min * 60_000L,
                levelPct = if (min < 10) 80 else 79,
                voltageMv = 4000,
                currentMicroA = -270_000L,
                chargeCounterMicroAh = 0L,
                isScreenOn = false
            )
        }

        val report = RootCauseAnalyzer.analyze(
            samples = samples,
            latestSample = samples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.ONE_HOUR,
            ratedCapacityMah = 4500,
            thermalAlertCelsius = 39f
        )

        assertEquals(45.0f, report.totalDischargedMah, 0.5f)
        assertEquals(1.0f, report.totalDischargedPct, 0.05f)
        assertEquals(6.0f, report.overallDrainRatePctPerHour, 0.2f)
        assertEquals(6.0f, report.screenOffDrainRatePctPerHour, 0.2f)
    }

    // Finding 5: Watch armed at 7 a.m. must NOT match 3 a.m. 3 W spike on subsequent 0.20 W readings;
    // screen-on peaks must NOT be mislabeled screen-off; stable event ID deduplicates repeated evaluations.
    @Test
    fun watchInvestigationEvaluatesStrictlyPostArmAndChecksScreenStateAndStableEventId() {
        val base = utcTime(2026, 11, 21, 23, 0)
        val spikeAt3Am = utcTime(2026, 11, 22, 3, 0)
        val armedAt7Am = utcTime(2026, 11, 22, 7, 0)

        val watchConfig = WatchInvestigationConfig(
            active = true,
            title = "Watch: Standby Spike",
            suspectLabel = "Sample Sync",
            triggerDrainPctPerHour = 1.8f,
            triggerPeakMw = 2_000f,
            armedAtMs = armedAt7Am
        )

        val samples = listOf(
            sample(base, levelPct = 90, currentMicroA = -50_000L, chargeCounterMicroAh = 4_050_000L, isScreenOn = false),
            sample(spikeAt3Am, levelPct = 85, currentMicroA = -750_000L, chargeCounterMicroAh = 3_825_000L, isScreenOn = false), // 3.0 W pre-arm
            sample(armedAt7Am, levelPct = 84, currentMicroA = -50_000L, chargeCounterMicroAh = 3_780_000L, isScreenOn = false),
            sample(armedAt7Am + 15 * 60_000L, levelPct = 84, currentMicroA = -50_000L, chargeCounterMicroAh = 3_775_000L, isScreenOn = false), // 0.20 W post-arm
            sample(armedAt7Am + 30 * 60_000L, levelPct = 84, currentMicroA = -50_000L, chargeCounterMicroAh = 3_770_000L, isScreenOn = false)  // 0.20 W post-arm
        )

        val eval1 = OvernightReplayEngine.analyze(
            allSamples = samples.dropLast(1),
            watchConfig = watchConfig,
            ratedCapacityMah = 4500,
            nowMs = armedAt7Am + 16 * 60_000L,
            timeZone = utc
        )
        val eval2 = OvernightReplayEngine.analyze(
            allSamples = samples,
            watchConfig = watchConfig,
            ratedCapacityMah = 4500,
            nowMs = armedAt7Am + 31 * 60_000L,
            timeZone = utc
        )

        assertFalse("Old 3 a.m. spike must NOT match after watch armed at 7 a.m.", eval1.watchTriggeredThisWindow)
        assertFalse("Second calm 0.20 W reading must NOT match old 3 a.m. spike", eval2.watchTriggeredThisWindow)

        // Now add a genuine POST-ARM screen-on 3.2 W spike at 07:45 followed by a calm reading at 08:00
        val postSpikeTs = armedAt7Am + 45 * 60_000L
        val withPostArmScreenOnSpike = samples + listOf(
            sample(postSpikeTs, levelPct = 83, currentMicroA = -800_000L, chargeCounterMicroAh = 3_735_000L, isScreenOn = true)
        )
        val match1 = OvernightReplayEngine.analyze(
            allSamples = withPostArmScreenOnSpike,
            watchConfig = watchConfig,
            ratedCapacityMah = 4500,
            nowMs = postSpikeTs + 5 * 60_000L,
            timeZone = utc
        )
        val match2 = OvernightReplayEngine.analyze(
            allSamples = withPostArmScreenOnSpike + sample(postSpikeTs + 15 * 60_000L, levelPct = 83, currentMicroA = -50_000L, chargeCounterMicroAh = 3_730_000L, isScreenOn = false),
            watchConfig = watchConfig,
            ratedCapacityMah = 4500,
            nowMs = postSpikeTs + 20 * 60_000L,
            timeZone = utc
        )

        assertTrue(match1.watchTriggeredThisWindow)
        assertTrue("Screen-on peak must NOT be called Screen-off peak", match1.watchMatchReason!!.contains("Screen-on peak"))
        assertFalse(match1.watchMatchReason!!.contains("Screen-off peak"))
        assertNotNull(match1.watchMatchEventId)
        assertEquals(postSpikeTs, match1.watchMatchEventTimestampMs)
        // Event ID & timestamp stay stable across subsequent calm samples so it deduplicates cleanly
        assertEquals(match1.watchMatchEventId, match2.watchMatchEventId)
        assertEquals(match1.watchMatchEventTimestampMs, match2.watchMatchEventTimestampMs)
    }

    // Finding 6: Sleep verdict must NOT say "CPU Wakelock Confirmed", and supplemental Wi-Fi PowerMonitor
    // must NOT relabel the modeled 6-component table as "Measured"
    @Test
    fun doesNotOverclaimUnmeasuredWakelockOrRelabelModeledBreakdownAsMeasured() {
        val base = utcTime(2026, 11, 22, 1, 0)
        val elapsed0 = 10_000_000L
        val uptime0 = 5_000_000L
        // 1-hour screen-off session, 3% drain (135 mAh on 4500 mAh), 70% deep sleep (42m sleep, 18m awake)
        val samples = listOf(
            sample(base, levelPct = 80, currentMicroA = -135_000L, chargeCounterMicroAh = 3_600_000L, isScreenOn = false, elapsedRealtimeMs = elapsed0, uptimeMs = uptime0),
            sample(base + 60 * 60_000L, levelPct = 77, currentMicroA = -135_000L, chargeCounterMicroAh = 3_465_000L, isScreenOn = false, elapsedRealtimeMs = elapsed0 + 60 * 60_000L, uptimeMs = uptime0 + 18 * 60_000L)
        )
        val wifiMeters = listOf(
            EnergyMeterSnapshotEntity(timestampMs = base, elapsedRealtimeMs = elapsed0, meterName = "WIFI", meterType = "MEASUREMENT", energyMicroWattSec = 100_000_000L, readingTimestampMs = elapsed0),
            EnergyMeterSnapshotEntity(timestampMs = base + 60 * 60_000L, elapsedRealtimeMs = elapsed0 + 60 * 60_000L, meterName = "WIFI", meterType = "MEASUREMENT", energyMicroWattSec = 460_000_000L, readingTimestampMs = elapsed0 + 60 * 60_000L)
        )

        val reportWithoutMeters = RootCauseAnalyzer.analyze(
            samples = samples,
            latestSample = samples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.SIX_HOURS,
            ratedCapacityMah = 4500,
            thermalAlertCelsius = 39f
        )
        val reportWithMeters = RootCauseAnalyzer.analyze(
            samples = samples,
            latestSample = samples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.SIX_HOURS,
            ratedCapacityMah = 4500,
            thermalAlertCelsius = 39f,
            energyMeterSnapshots = wifiMeters
        )

        assertEquals(1, reportWithMeters.sleepSessions.size)
        val verdict = reportWithMeters.sleepSessions.first().verdict
        assertFalse("Must not claim confirmed wakelock: $verdict", verdict.contains("Confirmed", ignoreCase = true))
        assertTrue("Should describe observed CPU awake time: $verdict", verdict.contains("CPU Awake", ignoreCase = true))

        assertTrue(reportWithMeters.hardwareAttributionMode.startsWith("Modeled Component Split"))
        assertFalse(reportWithMeters.hardwareAttributionMode.startsWith("Measured"))
        assertEquals(
            reportWithoutMeters.hardwareBreakdown.map { it.attributedMah },
            reportWithMeters.hardwareBreakdown.map { it.attributedMah }
        )

        val md = DiagnosticReportGenerator.generateMarkdownSummary(reportWithMeters, samples.last())
        assertTrue(md.contains("## 4. Modeled Hardware Component Breakdown"))
    }

    // Finding 7: 2-minute spike window must NOT implicate a 15-minute app interval that ended 14 minutes earlier
    @Test
    fun spikeInspectorExcludesAppActivityThatEndedBeforeSpikeWindow() {
        val t0 = utcTime(2026, 11, 22, 10, 0)
        val samples = listOf(
            sample(t0, levelPct = 80, currentMicroA = -100_000L, chargeCounterMicroAh = 3_600_000L),
            sample(t0 + 1 * 60_000L, levelPct = 80, currentMicroA = -800_000L, chargeCounterMicroAh = 3_585_000L), // 3.2 W spike
            sample(t0 + 2 * 60_000L, levelPct = 79, currentMicroA = -100_000L, chargeCounterMicroAh = 3_580_000L)
        )
        // App ran for 15 minutes and ENDED 14 minutes BEFORE t0 (14 minutes before windowStartMs)
        val closedAppEvent = AppDrainEventEntity(
            timestampMs = t0 - 14 * 60_000L,
            packageName = "com.example.closedgame",
            appLabel = "Closed Game",
            foregroundMs = 15 * 60_000L,
            launchCount = 1,
            estimatedMah = 120f,
            avgPowerMw = 2400f,
            intervalDurationMs = 15 * 60_000L
        )

        val spikeReport = SpikeInspector.analyzeSpike(
            samples = samples,
            targetIndex = 1,
            usageEvents = emptyList(),
            appUsage = emptyList(),
            appDrainEvents = listOf(closedAppEvent),
            energyMeters = emptyList(),
            hasUsageAccess = true,
            ratedCapacityMah = 4500
        )

        assertTrue("Closed app from 14m earlier must NOT be implicated", spikeReport.correlatedAppUsage.isEmpty())
        assertTrue(spikeReport.likelyDrivers.none { it.contains("Closed Game") })
    }

    // Finding 8a & 8b: 3 daytime sessions produce 0 baseline nights; uninterrupted daytime discharge uses RECENT_SESSION
    @Test
    fun daytimeSessionsDoNotBuildBaselineNightsOrClaimOvernightCharging() {
        val samples = listOf(
            // Daytime session 1: Nov 18 12:00 -> 12:45
            sample(utcTime(2026, 11, 18, 12, 0), levelPct = 90, isScreenOn = false, chargeCounterMicroAh = 4_050_000L),
            sample(utcTime(2026, 11, 18, 12, 45), levelPct = 88, isScreenOn = false, chargeCounterMicroAh = 3_960_000L),
            // Daytime session 2: Nov 19 13:00 -> 13:45
            sample(utcTime(2026, 11, 19, 13, 0), levelPct = 85, isScreenOn = false, chargeCounterMicroAh = 3_825_000L),
            sample(utcTime(2026, 11, 19, 13, 45), levelPct = 83, isScreenOn = false, chargeCounterMicroAh = 3_735_000L),
            // Daytime session 3: Nov 20 14:00 -> 14:45
            sample(utcTime(2026, 11, 20, 14, 0), levelPct = 80, isScreenOn = false, chargeCounterMicroAh = 3_600_000L),
            sample(utcTime(2026, 11, 20, 14, 45), levelPct = 78, isScreenOn = false, chargeCounterMicroAh = 3_510_000L),
            // First actual night: Nov 21 22:00 -> Nov 22 06:00
            sample(utcTime(2026, 11, 21, 22, 0), levelPct = 75, isScreenOn = false, chargeCounterMicroAh = 3_375_000L),
            sample(utcTime(2026, 11, 22, 2, 0), levelPct = 73, isScreenOn = false, chargeCounterMicroAh = 3_285_000L),
            sample(utcTime(2026, 11, 22, 6, 0), levelPct = 71, isScreenOn = false, chargeCounterMicroAh = 3_195_000L)
        )

        val firstNightReport = OvernightReplayEngine.analyze(
            allSamples = samples,
            ratedCapacityMah = 4500,
            nowMs = utcTime(2026, 11, 22, 6, 15),
            timeZone = utc
        )
        assertEquals(OvernightReportMode.OVERNIGHT_STANDBY, firstNightReport.mode)
        assertEquals("Daytime sessions must not count as baseline nights", 0, firstNightReport.baseline.nightsCount)

        // Separate case: uninterrupted noon-to-1 p.m. discharge with NO plug-in recorded
        val daytimeOnly = listOf(
            sample(utcTime(2026, 11, 22, 12, 0), levelPct = 85, isScreenOn = true, chargeCounterMicroAh = 3_825_000L),
            sample(utcTime(2026, 11, 22, 12, 30), levelPct = 80, isScreenOn = true, chargeCounterMicroAh = 3_600_000L),
            sample(utcTime(2026, 11, 22, 13, 0), levelPct = 75, isScreenOn = true, chargeCounterMicroAh = 3_375_000L)
        )
        val daytimeReport = OvernightReplayEngine.analyze(
            allSamples = daytimeOnly,
            ratedCapacityMah = 4500,
            nowMs = utcTime(2026, 11, 22, 13, 5),
            timeZone = utc
        )
        assertEquals(
            "Uninterrupted daytime discharge without plug-in must use neutral RECENT_SESSION",
            OvernightReportMode.RECENT_SESSION,
            daytimeReport.mode
        )
    }

    // Finding 9: 5 mAh charge-counter + 250 mAh level-estimated must be labeled MIXED, not counter-measured
    @Test
    fun mixedCounterAndLevelEstimateIsLabeledMixed() {
        val samples = listOf(
            // Interval 1: 5 mAh from charge counter
            sample(utcTime(2026, 11, 21, 22, 0), levelPct = 90, chargeCounterMicroAh = 4_500_000L, isScreenOn = false),
            sample(utcTime(2026, 11, 22, 1, 0), levelPct = 90, chargeCounterMicroAh = 4_495_000L, isScreenOn = false),
            // Interval 2: charge counter unavailable (0), 5% drop on 5000 mAh = 250 mAh estimated
            sample(utcTime(2026, 11, 22, 4, 0), levelPct = 85, chargeCounterMicroAh = 0L, isScreenOn = false)
        )

        val report = OvernightReplayEngine.analyze(
            allSamples = samples,
            ratedCapacityMah = 5000,
            nowMs = utcTime(2026, 11, 22, 4, 15),
            timeZone = utc
        )

        assertEquals(OvernightReportMode.OVERNIGHT_STANDBY, report.mode)
        assertEquals(ChargeEvidenceSource.MIXED, report.chargeEvidenceSource)
        assertFalse("Mixed evidence must not be marked purely counterMeasured", report.counterMeasured)
        assertEquals(5f, report.counterMeasuredMah, 0.5f)
        assertEquals(250f, report.estimatedMah, 0.5f)
        assertEquals(255f, report.chargeLostMah, 0.5f)
        assertTrue(report.whatPhoneRecordedSummary.first().contains("mixed evidence", ignoreCase = true))
    }

    // Finding 10: 100 mAh discharged + 200 mAh charged must report +100.0 mAh net gained, NOT "Net Charge Loss: 100 mAh"
    @Test
    fun exportedMarkdownSeparatesDischargeChargeAndSignedNetChange() {
        val base = utcTime(2026, 11, 22, 10, 0)
        val samples = listOf(
            sample(base, levelPct = 50, chargeCounterMicroAh = 2_500_000L, isCharging = false, isScreenOn = true),
            sample(base + 30 * 60_000L, levelPct = 48, chargeCounterMicroAh = 2_400_000L, isCharging = false, isScreenOn = true), // -100 mAh
            sample(base + 60 * 60_000L, levelPct = 52, chargeCounterMicroAh = 2_600_000L, isCharging = true, currentMicroA = 1_200_000L, isScreenOn = false) // +200 mAh
        )

        val report = RootCauseAnalyzer.analyze(
            samples = samples,
            latestSample = samples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.SIX_HOURS,
            ratedCapacityMah = 5000,
            thermalAlertCelsius = 39f
        )

        assertEquals(100f, report.totalDischargedMah, 0.5f)
        assertEquals(200f, report.totalChargedMah, 0.5f)
        assertEquals(100f, report.netChargeDeltaMah, 0.5f)

        val md = DiagnosticReportGenerator.generateMarkdownSummary(report, samples.last())
        assertFalse("Must not call discharge 'Net Charge Loss' when net charge increased", md.contains("Net Charge Loss"))
        assertTrue(md.contains("Total Discharged"))
        assertTrue(md.contains("Total Charged"))
        assertTrue(md.contains("+100.0 mAh (net gained)"))
    }

    // Finding 14: Worker uses dedicated app-usage cursor and scales app energy by foreground coverage
    @Test
    fun workerPreservesAppUsageCursorAndScalesEnergyByForegroundCoverage() {
        val now = utcTime(2026, 11, 22, 12, 0)
        val lastCursor = now - 15 * 60_000L

        // Even if high-precision/live wrote a sample 1 minute ago, the dedicated cursor stays at now - 15m
        val resolvedStart = BatteryTelemetryWorker.resolveAppUsageWindowStart(
            lastCursorMs = lastCursor,
            fallbackSampleMs = now - 60_000L,
            nowMs = now
        )
        assertEquals(lastCursor, resolvedStart)

        // 1 minute of foreground usage inside a 15-minute 150 mAh interval should receive only 1/15 = 10 mAh
        val startSample = sample(
            timestampMs = lastCursor,
            levelPct = 80,
            currentMicroA = -600_000L,
            chargeCounterMicroAh = 3_600_000L,
            isScreenOn = false
        )
        val endSample = sample(
            timestampMs = now,
            levelPct = 77,
            currentMicroA = -600_000L,
            chargeCounterMicroAh = 3_450_000L,
            isScreenOn = false
        )
        val events = BatteryTelemetryWorker.computeAppDrainEvents(
            observedApps = listOf(
                ObservedAppUsage(
                    packageName = "com.example.chat",
                    appLabel = "Sample Chat",
                    foregroundMs = 60_000L,
                    launchCount = 1
                )
            ),
            windowStartMs = lastCursor,
            windowEndMs = now,
            samplesInWindow = listOf(startSample, endSample),
            fallbackPrevSample = startSample,
            currentSample = endSample,
            ratedCapacityMah = 4500
        )
        assertEquals(1, events.size)
        assertEquals(10f, events.first().estimatedMah, 0.2f)
    }

    // Finding 13b: Chart positions points and selects taps by timestamp rather than sample index
    @Test
    fun chartTimestampMappingGivesTwoHourGapProportionallyMoreWidthThanOneMinuteGap() {
        val t0 = utcTime(2026, 11, 22, 8, 0)
        val s0 = sample(t0, levelPct = 90)
        val s1 = sample(t0 + 1 * 60_000L, levelPct = 89) // 1 min gap
        val s2 = sample(t0 + 121 * 60_000L, levelPct = 80) // 2 hour gap
        val samples = listOf(s0, s1, s2)
        val spanMs = s2.timestampMs - s0.timestampMs
        val width = 1000f

        val x0 = timelineTimestampToX(s0.timestampMs, t0, spanMs, width)
        val x1 = timelineTimestampToX(s1.timestampMs, t0, spanMs, width)
        val x2 = timelineTimestampToX(s2.timestampMs, t0, spanMs, width)

        assertEquals(0f, x0, 0.01f)
        assertEquals(1000f, x2, 0.01f)
        assertTrue("1m gap width ($x1) must be ~1/121 of total width, not 500px", x1 in 7f..10f)

        // Tapping at 80% across the chart (closer in time to s2 than s1) must select s2, NOT s1
        assertEquals(2, nearestSampleIndexForTouchX(800f, width, samples))
        // Tapping near 1% across the chart selects s1
        assertEquals(1, nearestSampleIndexForTouchX(10f, width, samples))
    }
}
