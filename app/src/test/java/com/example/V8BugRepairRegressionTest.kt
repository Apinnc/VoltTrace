package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.hardware.BatteryAlertManager
import com.example.data.hardware.BatteryHardwareReader
import com.example.data.hardware.BatteryTelemetryWorker
import com.example.data.hardware.ObservedAppUsage
import com.example.data.local.BatterySampleEntity
import com.example.data.local.EnergyMeterSnapshotEntity
import com.example.data.local.MonitorPreferences
import com.example.domain.AnalysisWindow
import com.example.domain.ChargeEvidenceSource
import com.example.domain.CsvTelemetryParser
import com.example.domain.DiagnosticReportGenerator
import com.example.domain.OvernightReplayEngine
import com.example.domain.OvernightReportMode
import com.example.domain.RootCauseAnalyzer
import com.example.domain.SpikeInspector
import com.example.domain.WatchInvestigationConfig
import com.example.domain.WindowUsageEventItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/**
 * Regression test suite for the remaining VoltTrace v8 battery-analysis bug repairs:
 * 1. Signed battery change through plug/unplug transitions, signed current preservation, and legacy unit uncertainty.
 * 2. Intended local night selection after overnight charging, USB connected-idle vs unplugged, boundary interpolation isolation, and watch match/notification deduplication across execution orders.
 * 3. Time-coverage-scaled app attribution fallback, rolling 1H window app filter, and sub-interval worker discharge attribution.
 * 4. Period-reconciled drain episodes matching summary totals and mixed/estimated provenance labels.
 * 5. Per-side spike charge provenance (before vs after) and consistent spike evidence window + independent energy meter queries.
 */
class V8BugRepairRegressionTest {

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
        pluggedType: String = if (isCharging) "AC" else "BATTERY",
        isScreenOn: Boolean = false,
        elapsedRealtimeMs: Long = 0L,
        uptimeMs: Long = 0L,
        foregroundPackage: String? = null,
        foregroundLabel: String? = null,
        currentUnitVerified: Boolean = true
    ) = BatterySampleEntity(
        id = timestampMs,
        timestampMs = timestampMs,
        levelPct = levelPct,
        voltageMv = voltageMv,
        temperatureDeciC = tempDeciC,
        currentMicroA = currentMicroA,
        chargeCounterMicroAh = chargeCounterMicroAh,
        isCharging = isCharging,
        pluggedType = pluggedType,
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
        uptimeMs = uptimeMs,
        currentUnitVerified = currentUnitVerified
    )

    // =========================================================================
    // SECTION 1: Signed battery change through plug/unplug transitions,
    //            signed current preservation, and legacy unit uncertainty
    // =========================================================================

    @Test
    fun section1a_plugAndUnplugTransitionsRecordSignedCounterChangeAndProtectAgainstCounterReset() {
        val t0 = utcTime(2026, 11, 25, 10, 0)
        // Three consecutive counter samples 5 minutes apart:
        // 1) 4,000 mAh unplugged
        // 2) 3,900 mAh at first charging sample (-100 mAh across unplug->plug transition)
        // 3) 3,950 mAh still charging (+50 mAh gained while charging)
        // Overall net change = -50 mAh (50 mAh lost overall).
        val plugTransitionSamples = listOf(
            sample(t0, levelPct = 80, chargeCounterMicroAh = 4_000_000L, isCharging = false, currentMicroA = -300_000L),
            sample(t0 + 5 * 60_000L, levelPct = 78, chargeCounterMicroAh = 3_900_000L, isCharging = true, currentMicroA = 600_000L),
            sample(t0 + 10 * 60_000L, levelPct = 79, chargeCounterMicroAh = 3_950_000L, isCharging = true, currentMicroA = 600_000L)
        )

        val report = RootCauseAnalyzer.analyze(
            samples = plugTransitionSamples,
            latestSample = plugTransitionSamples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.ONE_HOUR,
            ratedCapacityMah = 5000,
            thermalAlertCelsius = 39f
        )

        assertEquals(100.0f, report.totalDischargedMah, 0.5f)
        assertEquals(50.0f, report.totalChargedMah, 0.5f)
        assertEquals(-50.0f, report.netChargeDeltaMah, 0.5f)
        assertEquals(100.0f, report.plugTransitionDischargedMah, 0.5f)
        assertEquals(0.0f, report.plugTransitionChargedMah, 0.5f)
        assertEquals(5L * 60_000L, report.plugTransitionDurationMs)

        val md = DiagnosticReportGenerator.generateMarkdownSummary(report, plugTransitionSamples.last())
        assertTrue("Markdown must report -50.0 mAh net lost overall: $md", md.contains("-50.0 mAh (net lost)"))
        assertTrue("Markdown must report 100.0 mAh decrease across transition: $md", md.contains("-100.0 mAh") && md.contains("Plug/Unplug Transition Change"))
        assertTrue("Markdown must note unknown plug-in time inside transition: $md", md.contains("precise plug-in/unplug time"))

        // Reverse transition (charging -> unplugged) with net gain across transition (+40 mAh) then -90 mAh discharge
        val unplugTransitionSamples = listOf(
            sample(t0, levelPct = 80, chargeCounterMicroAh = 4_000_000L, isCharging = true, currentMicroA = 500_000L),
            sample(t0 + 5 * 60_000L, levelPct = 81, chargeCounterMicroAh = 4_040_000L, isCharging = false, currentMicroA = -300_000L),
            sample(t0 + 10 * 60_000L, levelPct = 79, chargeCounterMicroAh = 3_950_000L, isCharging = false, currentMicroA = -300_000L)
        )
        val unplugReport = RootCauseAnalyzer.analyze(
            samples = unplugTransitionSamples,
            latestSample = unplugTransitionSamples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.ONE_HOUR,
            ratedCapacityMah = 5000,
            thermalAlertCelsius = 39f
        )
        assertEquals(40.0f, unplugReport.totalChargedMah, 0.5f)
        assertEquals(90.0f, unplugReport.totalDischargedMah, 0.5f)
        assertEquals(-50.0f, unplugReport.netChargeDeltaMah, 0.5f)
        assertEquals(40.0f, unplugReport.plugTransitionChargedMah, 0.5f)

        // Counter reset / reboot across 5 minutes (4,000 mAh -> 1,000 mAh while SOC stays 80%) must be ignored
        val resetSamples = listOf(
            sample(t0, levelPct = 80, chargeCounterMicroAh = 4_000_000L, isCharging = false, elapsedRealtimeMs = 50_000_000L, uptimeMs = 20_000_000L),
            sample(t0 + 5 * 60_000L, levelPct = 80, chargeCounterMicroAh = 1_000_000L, isCharging = false, elapsedRealtimeMs = 120_000L, uptimeMs = 100_000L)
        )
        val resetReport = RootCauseAnalyzer.analyze(
            samples = resetSamples,
            latestSample = resetSamples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.ONE_HOUR,
            ratedCapacityMah = 5000,
            thermalAlertCelsius = 39f
        )
        assertTrue("Abrupt 3,000 mAh counter reset/reboot across 5m with 0% SOC drop must not be counted as 3,000 mAh loss", resetReport.totalDischargedMah < 50f)
    }

    @Test
    fun section1b_preservesSignedCurrentFromCollectionThroughEntityAndCsvEvenWhenChargingOrFull() {
        // At 4 V, -250,000 uA means -250 mA and -1.0 W even when charging status says CHARGING or FULL
        val normalizedNegWhileCharging = BatteryHardwareReader.normalizeCurrentReadingToMicroAmps(
            raw = -250_000L,
            isCharging = true,
            halUsesMicroAmps = true,
            isLegacyMilliampHal = false
        )
        val normalizedPosWhileUnplugged = BatteryHardwareReader.normalizeCurrentReadingToMicroAmps(
            raw = 250_000L,
            isCharging = false,
            halUsesMicroAmps = true,
            isLegacyMilliampHal = false
        )
        val normalizedZero = BatteryHardwareReader.normalizeCurrentReadingToMicroAmps(
            raw = 0L,
            isCharging = true
        )
        val normalizedUnavailable = BatteryHardwareReader.normalizeCurrentReadingToMicroAmps(
            raw = Long.MIN_VALUE,
            isCharging = true
        )

        assertEquals(-250_000L, normalizedNegWhileCharging)
        assertEquals(250_000L, normalizedPosWhileUnplugged)
        assertEquals(0L, normalizedZero)
        assertEquals(0L, normalizedUnavailable)
        assertFalse(BatteryHardwareReader.isCurrentReadingAvailable(Long.MIN_VALUE))
        assertTrue(BatteryHardwareReader.isCurrentReadingAvailable(-250_000L))

        val chargingNetDischargeSample = sample(
            timestampMs = utcTime(2026, 11, 25, 11, 0),
            levelPct = 100,
            voltageMv = 4000,
            currentMicroA = normalizedNegWhileCharging,
            isCharging = true,
            pluggedType = "USB"
        )
        assertEquals(-250.0f, chargingNetDischargeSample.currentSignedMa, 0.01f)
        assertEquals(-1000.0f, chargingNetDischargeSample.powerSignedMw, 0.1f)
        assertEquals(-1.0f, chargingNetDischargeSample.powerSignedWatts, 0.001f)
        assertTrue(chargingNetDischargeSample.isConnectedToExternalPower)
        assertTrue(chargingNetDischargeSample.isCharging)

        // Round-trip through CSV export and import must preserve -250 mA and -1.0 W while is_charging=true
        val csv = DiagnosticReportGenerator.generateCsvTelemetry(listOf(chargingNetDischargeSample))
        val parsed = CsvTelemetryParser.parse(csv)
        assertEquals(1, parsed.size)
        val reimported = parsed.first()
        assertTrue(reimported.isCharging)
        assertEquals("USB", reimported.pluggedType)
        assertEquals(-250_000L, reimported.currentMicroA)
        assertEquals(-250.0f, reimported.currentSignedMa, 0.01f)
        assertEquals(-1.0f, reimported.powerSignedWatts, 0.001f)
    }

    @Test
    fun section1c_treatsAmbiguousPreV8RowsAsUncertainWhilePreservingGenuineHighCurrentNewRows() {
        val t0 = utcTime(2026, 11, 25, 2, 0)
        // Upgraded pre-v8 row with currentUnitVerified = false and falsely scaled -8,000,000 uA
        val legacyFalselyScaledRow = sample(
            timestampMs = t0,
            levelPct = 85,
            voltageMv = 4000,
            currentMicroA = -8_000_000L,
            chargeCounterMicroAh = 3_825_000L,
            currentUnitVerified = false
        )
        // Calm verified v8 sample
        val calmNewRow = sample(
            timestampMs = t0 + 30 * 60_000L,
            levelPct = 84,
            voltageMv = 4000,
            currentMicroA = -50_000L, // 0.20 W
            chargeCounterMicroAh = 3_780_000L,
            currentUnitVerified = true
        )
        val calmNewRow2 = sample(
            timestampMs = t0 + 180 * 60_000L,
            levelPct = 82,
            voltageMv = 4000,
            currentMicroA = -50_000L, // 0.20 W
            chargeCounterMicroAh = 3_690_000L,
            currentUnitVerified = true
        )

        assertTrue(legacyFalselyScaledRow.hasUncertainLegacyCurrentUnit)
        assertFalse(calmNewRow.hasUncertainLegacyCurrentUnit)
        // Original raw data is retained intact
        assertEquals(-8_000_000L, legacyFalselyScaledRow.currentMicroA)

        // Analysis & Overnight Replay must exclude the uncertain pre-v8 row from peaks and watch alerts
        val analysis = RootCauseAnalyzer.analyze(
            samples = listOf(legacyFalselyScaledRow, calmNewRow, calmNewRow2),
            latestSample = calmNewRow2,
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.SIX_HOURS,
            ratedCapacityMah = 4500,
            thermalAlertCelsius = 39f
        )
        assertEquals(1, analysis.uncertainLegacyUnitSampleCount)
        assertEquals(200.0f, analysis.peakDischargePowerMw, 1.0f) // 0.20 W, NOT 32.0 W

        val overnight = OvernightReplayEngine.analyze(
            allSamples = listOf(legacyFalselyScaledRow, calmNewRow, calmNewRow2),
            watchConfig = WatchInvestigationConfig(
                active = true,
                title = "Watch Peak",
                triggerPeakMw = 2_000f,
                armedAtMs = t0 - 60_000L
            ),
            ratedCapacityMah = 4500,
            nowMs = t0 + 185 * 60_000L,
            timeZone = utc
        )
        assertEquals(200.0f, overnight.peakDischargeMw, 1.0f)
        assertFalse("Ambiguous legacy row must not trigger a 32 W watch alert", overnight.watchTriggeredThisWindow)

        // Genuine high-current NEW row (e.g. -2,000,000 uA = 2 A @ 4 V = 8 W with currentUnitVerified = true) IS preserved
        val genuineHighCurrentNewRow = sample(
            timestampMs = t0 + 60 * 60_000L,
            levelPct = 83,
            voltageMv = 4000,
            currentMicroA = -2_000_000L,
            chargeCounterMicroAh = 3_735_000L,
            currentUnitVerified = true
        )
        assertFalse(genuineHighCurrentNewRow.hasUncertainLegacyCurrentUnit)
        val analysisWithGenuinePeak = RootCauseAnalyzer.analyze(
            samples = listOf(calmNewRow, genuineHighCurrentNewRow, calmNewRow2),
            latestSample = calmNewRow2,
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.SIX_HOURS,
            ratedCapacityMah = 4500,
            thermalAlertCelsius = 39f
        )
        assertEquals(8_000.0f, analysisWithGenuinePeak.peakDischargePowerMw, 1.0f)
    }

    // =========================================================================
    // SECTION 2: Select the correct morning, represent connection state vs
    //            charging, and keep calculated boundary readings out of alerts
    // =========================================================================

    @Test
    fun section2a_wednesdayMorningAfterTuesdayNightChargingSelectsTuesdayPrePlugSessionNotMondayNightOrWednesdayHalfHourUse() {
        // Monday night (Nov 23 22:00 -> Tuesday Nov 24 07:30): unplugged overnight standby
        // Tuesday evening (Nov 24 18:00 -> 22:00): unplugged discharge 75% -> 60% (-675 mAh)
        // Tuesday night (Nov 24 22:00 -> Wednesday Nov 25 06:30): plugged in & charging 60% -> 100%
        // Wednesday morning (Nov 25 06:30 -> 07:00): 30 minutes of post-charge unplugged use 100% -> 98%
        // Evaluated at 07:00 Wednesday Nov 25 (within 24h of Tuesday 07:30 end of Monday night!).
        val samples = listOf(
            // Monday night standby
            sample(utcTime(2026, 11, 23, 22, 0), levelPct = 90, chargeCounterMicroAh = 4_050_000L, isScreenOn = false),
            sample(utcTime(2026, 11, 24, 3, 0), levelPct = 88, chargeCounterMicroAh = 3_960_000L, isScreenOn = false),
            sample(utcTime(2026, 11, 24, 7, 30), levelPct = 86, chargeCounterMicroAh = 3_870_000L, isScreenOn = false),
            // Tuesday evening unplugged session before plugging in at 22:00
            sample(utcTime(2026, 11, 24, 18, 0), levelPct = 75, chargeCounterMicroAh = 3_375_000L, isScreenOn = true),
            sample(utcTime(2026, 11, 24, 20, 0), levelPct = 67, chargeCounterMicroAh = 3_015_000L, isScreenOn = true),
            sample(utcTime(2026, 11, 24, 21, 55), levelPct = 60, chargeCounterMicroAh = 2_700_000L, isScreenOn = false),
            // Tuesday night charging 22:00 -> Wednesday 06:30
            sample(utcTime(2026, 11, 24, 22, 0), levelPct = 61, chargeCounterMicroAh = 2_745_000L, isCharging = true, currentMicroA = 1_500_000L),
            sample(utcTime(2026, 11, 25, 2, 0), levelPct = 95, chargeCounterMicroAh = 4_275_000L, isCharging = true, currentMicroA = 500_000L),
            sample(utcTime(2026, 11, 25, 6, 25), levelPct = 100, chargeCounterMicroAh = 4_500_000L, isCharging = true, currentMicroA = 50_000L),
            // Wednesday morning 30 minutes of unplugged use (06:30 -> 07:00)
            sample(utcTime(2026, 11, 25, 6, 30), levelPct = 100, chargeCounterMicroAh = 4_500_000L, isCharging = false, isScreenOn = true),
            sample(utcTime(2026, 11, 25, 7, 0), levelPct = 98, chargeCounterMicroAh = 4_410_000L, isCharging = false, isScreenOn = true)
        )

        val wednesdayMorningReport = OvernightReplayEngine.analyze(
            allSamples = samples,
            ratedCapacityMah = 4500,
            nowMs = utcTime(2026, 11, 25, 7, 0),
            timeZone = utc
        )

        assertEquals(OvernightReportMode.BEFORE_PLUGGED_IN, wednesdayMorningReport.mode)
        assertTrue(wednesdayMorningReport.chargedOvernight)
        assertEquals(75, wednesdayMorningReport.startLevelPct)
        assertEquals(60, wednesdayMorningReport.endLevelPct)
        assertEquals(15, wednesdayMorningReport.levelLostPct)
        assertEquals(675.0f, wednesdayMorningReport.chargeLostMah, 1.0f)
        assertEquals(utcTime(2026, 11, 24, 22, 0), wednesdayMorningReport.pluggedInAtMs)

        // A noon-only plug-in must be described as a noon plug-in and must NOT establish overnight charging
        val noonPlugSamples = listOf(
            sample(utcTime(2026, 11, 25, 9, 0), levelPct = 80, chargeCounterMicroAh = 3_600_000L, isScreenOn = true),
            sample(utcTime(2026, 11, 25, 11, 55), levelPct = 70, chargeCounterMicroAh = 3_150_000L, isScreenOn = true),
            sample(utcTime(2026, 11, 25, 12, 0), levelPct = 71, chargeCounterMicroAh = 3_195_000L, isCharging = true, currentMicroA = 1_200_000L),
            sample(utcTime(2026, 11, 25, 12, 30), levelPct = 85, chargeCounterMicroAh = 3_825_000L, isCharging = true, currentMicroA = 1_200_000L)
        )
        val noonPlugReport = OvernightReplayEngine.analyze(
            allSamples = noonPlugSamples,
            ratedCapacityMah = 4500,
            nowMs = utcTime(2026, 11, 25, 12, 35),
            timeZone = utc
        )
        assertEquals(OvernightReportMode.BEFORE_PLUGGED_IN, noonPlugReport.mode)
        assertFalse("Noon-only plug-in must not claim overnight charging", noonPlugReport.chargedOvernight)
        val explanation = noonPlugReport.fallbackExplanation
        assertNotNull(explanation)
        assertFalse(explanation!!.contains("charged overnight", ignoreCase = true))
        assertTrue(explanation.contains("12:00"))
    }

    @Test
    fun section2b_usbConnectedWithChargingPausedDoesNotQualifyAsUnpluggedAndUnknownConnectionIsTracked() {
        // Entire night on USB with charging paused (isCharging = false, pluggedType = "USB")
        val usbPausedNightSamples = listOf(
            sample(utcTime(2026, 11, 24, 22, 0), levelPct = 80, chargeCounterMicroAh = 3_600_000L, isCharging = false, pluggedType = "USB", currentMicroA = 0L),
            sample(utcTime(2026, 11, 25, 2, 0), levelPct = 80, chargeCounterMicroAh = 3_600_000L, isCharging = false, pluggedType = "USB", currentMicroA = 0L),
            sample(utcTime(2026, 11, 25, 7, 0), levelPct = 80, chargeCounterMicroAh = 3_600_000L, isCharging = false, pluggedType = "USB", currentMicroA = 0L)
        )
        val usbPausedReport = OvernightReplayEngine.analyze(
            allSamples = usbPausedNightSamples,
            ratedCapacityMah = 4500,
            nowMs = utcTime(2026, 11, 25, 7, 15),
            timeZone = utc
        )
        assertEquals(OvernightReportMode.AWAITING_DATA, usbPausedReport.mode)
        assertTrue(usbPausedReport.connectedIdleOvernight)
        assertTrue(usbPausedReport.whatPhoneRecordedSummary.first().contains("connected to external power", ignoreCase = true))

        // Historical input lacking connection state while not charging (pluggedType = "UNKNOWN")
        val unknownConnSamples = listOf(
            sample(utcTime(2026, 11, 24, 22, 0), levelPct = 85, chargeCounterMicroAh = 3_825_000L, isCharging = false, pluggedType = "UNKNOWN"),
            sample(utcTime(2026, 11, 25, 2, 0), levelPct = 83, chargeCounterMicroAh = 3_735_000L, isCharging = false, pluggedType = "UNKNOWN"),
            sample(utcTime(2026, 11, 25, 6, 0), levelPct = 81, chargeCounterMicroAh = 3_645_000L, isCharging = false, pluggedType = "UNKNOWN")
        )
        val unknownConnReport = OvernightReplayEngine.analyze(
            allSamples = unknownConnSamples,
            ratedCapacityMah = 4500,
            nowMs = utcTime(2026, 11, 25, 6, 15),
            timeZone = utc
        )
        assertTrue(unknownConnReport.hasUnknownConnectionState)
        assertTrue(unknownConnReport.whatRemainsUnknown.any { it.contains("connection state was not recorded", ignoreCase = true) })
    }

    @Test
    fun section2c_calculatedBoundaryPointsDoNotAppearInReportSamplesOrDrivePeaksOrWatchAlerts() {
        // Real 6 W sample at 8:30 p.m. (20:30), watch armed at 8:54 p.m. (20:54),
        // real readings of only 0.2 W at 9:30 p.m. (21:30), 1:00 a.m., and 7:00 a.m.
        val sample830Pm = sample(
            timestampMs = utcTime(2026, 11, 24, 20, 30),
            levelPct = 90,
            voltageMv = 4000,
            currentMicroA = -1_500_000L, // 6.0 W pre-night, pre-arm
            chargeCounterMicroAh = 4_050_000L,
            isScreenOn = true,
            foregroundPackage = "com.example.video",
            foregroundLabel = "Daytime Video"
        )
        val armedAt854Pm = utcTime(2026, 11, 24, 20, 54)
        val sample930Pm = sample(
            timestampMs = utcTime(2026, 11, 24, 21, 30),
            levelPct = 88,
            voltageMv = 4000,
            currentMicroA = -50_000L, // 0.20 W
            chargeCounterMicroAh = 3_960_000L,
            isScreenOn = false
        )
        val sample100Am = sample(
            timestampMs = utcTime(2026, 11, 25, 1, 0),
            levelPct = 86,
            voltageMv = 4000,
            currentMicroA = -50_000L, // 0.20 W
            chargeCounterMicroAh = 3_870_000L,
            isScreenOn = false
        )
        val sample400Am = sample(
            timestampMs = utcTime(2026, 11, 25, 4, 0),
            levelPct = 85,
            voltageMv = 4000,
            currentMicroA = -50_000L, // 0.20 W
            chargeCounterMicroAh = 3_825_000L,
            isScreenOn = false
        )
        val sample700Am = sample(
            timestampMs = utcTime(2026, 11, 25, 7, 0),
            levelPct = 84,
            voltageMv = 4000,
            currentMicroA = -50_000L, // 0.20 W
            chargeCounterMicroAh = 3_780_000L,
            isScreenOn = false
        )

        val watchConfig = WatchInvestigationConfig(
            active = true,
            title = "Watch Standby Peak",
            suspectLabel = "Daytime Video",
            suspectPackage = "com.example.video",
            triggerDrainPctPerHour = 1.8f,
            triggerPeakMw = 2_000f,
            armedAtMs = armedAt854Pm
        )

        val report = OvernightReplayEngine.analyze(
            allSamples = listOf(sample830Pm, sample930Pm, sample100Am, sample400Am, sample700Am),
            watchConfig = watchConfig,
            ratedCapacityMah = 4500,
            nowMs = utcTime(2026, 11, 25, 7, 15),
            timeZone = utc
        )

        assertEquals(OvernightReportMode.OVERNIGHT_STANDBY, report.mode)
        // Calculated 21:00 boundary point must NOT appear in report.samples
        assertEquals(4, report.samples.size)
        assertTrue(report.samples.none { it.timestampMs == utcTime(2026, 11, 24, 21, 0) })
        // Peak power must be 200 mW (0.20 W), NOT 3.10 W
        assertEquals(200.0f, report.peakDischargeMw, 1.0f)
        // Watch alert must NOT trigger on an interpolated 9 p.m. point
        assertFalse("Watch must not fire on calculated 9 p.m. boundary point", report.watchTriggeredThisWindow)
        assertNull(report.watchMatchReason)
        // Boundary-interpolated 45 mAh (21:00->21:30) + 180 mAh counter-measured (21:30->07:00) = 225 mAh MIXED
        assertEquals(225.0f, report.chargeLostMah, 1.0f)
        assertEquals(180.0f, report.counterMeasuredMah, 1.0f)
        assertEquals(45.0f, report.estimatedMah, 1.0f)
        assertEquals(ChargeEvidenceSource.MIXED, report.chargeEvidenceSource)
        // App attribution and verbatim source readings must not contain 21:00:00 or Daytime Video
        assertTrue(report.segments.all { it.suspectLabel == null })
        assertTrue(report.segments.flatMap { it.sourceReadings }.none { it.contains("21:00:00") })
    }

    // =========================================================================
    // SECTION 3: Keep app attribution inside observed time and discharge bounds
    // =========================================================================

    @Test
    fun section3a_noStoredEventsFallbackScalesAppEnergyByWindowTimeCoverage() {
        val t0 = utcTime(2026, 11, 25, 13, 0)
        // 60-minute window with 135 mAh total discharge and 1 minute of foreground use by a single app -> 2.25 mAh
        val samples = listOf(
            sample(t0, levelPct = 80, chargeCounterMicroAh = 3_600_000L, isScreenOn = false),
            sample(t0 + 60 * 60_000L, levelPct = 77, chargeCounterMicroAh = 3_465_000L, isScreenOn = false)
        )
        val report = RootCauseAnalyzer.analyze(
            samples = samples,
            latestSample = samples.last(),
            appEvents = emptyList(),
            liveAppUsage = listOf(
                ObservedAppUsage(
                    packageName = "com.example.brief",
                    appLabel = "Brief App",
                    foregroundMs = 60_000L, // 1 minute out of 60 minutes
                    launchCount = 1,
                    windowStartMs = t0,
                    windowEndMs = t0 + 60 * 60_000L
                )
            ),
            window = AnalysisWindow.ONE_HOUR,
            ratedCapacityMah = 4500,
            thermalAlertCelsius = 39f
        )

        assertEquals(135.0f, report.totalDischargedMah, 0.5f)
        assertEquals(1, report.appCulprits.size)
        val culprit = report.appCulprits.first()
        assertEquals(2.25f, culprit.estimatedMah, 0.05f)
        assertEquals(1.666f, culprit.shareOfTotalDrainPct, 0.1f)
        assertTrue("No-stored-events fallback must be marked as uniform time estimate", culprit.isUniformTimeEstimate)

        val md = DiagnosticReportGenerator.generateMarkdownSummary(report, samples.last())
        assertTrue("Markdown export must label uniform time estimate: $md", md.contains("uniform time estimate"))
    }

    @Test
    fun section3b_analyzerExcludesStaleLiveAppUsageFromBeforeCurrentRollingWindow() {
        val t8Am = utcTime(2026, 11, 25, 8, 0)
        val t9Am = utcTime(2026, 11, 25, 9, 0)
        val t10Am = utcTime(2026, 11, 25, 10, 0)

        // Current 1H sample window is 09:00 -> 10:00
        val currentWindowSamples = listOf(
            sample(t9Am, levelPct = 80, chargeCounterMicroAh = 3_600_000L),
            sample(t10Am, levelPct = 78, chargeCounterMicroAh = 3_510_000L)
        )
        // Stale app usage from 07:00 -> 08:00 (before the current 09:00 -> 10:00 window)
        val staleAppFrom7To8 = ObservedAppUsage(
            packageName = "com.example.morningnews",
            appLabel = "7am News",
            foregroundMs = 15 * 60_000L,
            launchCount = 1,
            activeIntervals = listOf((t8Am - 30 * 60_000L) to (t8Am - 15 * 60_000L)),
            windowStartMs = t8Am - 60 * 60_000L,
            windowEndMs = t8Am
        )

        val report = RootCauseAnalyzer.analyze(
            samples = currentWindowSamples,
            latestSample = currentWindowSamples.last(),
            appEvents = emptyList(),
            liveAppUsage = listOf(staleAppFrom7To8),
            window = AnalysisWindow.ONE_HOUR,
            ratedCapacityMah = 4500,
            thermalAlertCelsius = 39f
        )

        assertTrue("App usage from 07:00-08:00 must not appear in the 09:00-10:00 1H window", report.appCulprits.isEmpty())
    }

    @Test
    fun section3c_workerPreservesPrePlugDischargeAttributionAndExcludesChargingSubIntervalAppUsage() {
        val t0 = utcTime(2026, 11, 25, 12, 0)
        val t10 = t0 + 10 * 60_000L
        val t15 = t0 + 15 * 60_000L

        // 12:00 -> 12:10: discharging unplugged (-100 mAh across 10 minutes)
        // 12:10 -> 12:15: plugged in & charging (+80 mAh across 5 minutes)
        val s0 = sample(t0, levelPct = 80, chargeCounterMicroAh = 4_000_000L, isCharging = false, currentMicroA = -600_000L)
        val s10 = sample(t10, levelPct = 78, chargeCounterMicroAh = 3_900_000L, isCharging = false, currentMicroA = -600_000L)
        val s15Charging = sample(t15, levelPct = 80, chargeCounterMicroAh = 3_980_000L, isCharging = true, currentMicroA = 1_000_000L)

        // App A ran for 5 minutes (12:02..12:07) inside the 10-minute discharging sub-interval (50% of 100 mAh = 50 mAh)
        val prePlugApp = ObservedAppUsage(
            packageName = "com.example.preplug",
            appLabel = "Pre-Plug Reader",
            foregroundMs = 5 * 60_000L,
            launchCount = 1,
            activeIntervals = listOf((t0 + 2 * 60_000L) to (t0 + 7 * 60_000L)),
            windowStartMs = t0,
            windowEndMs = t15
        )
        // App B ran for 4 minutes (12:11..12:15) only while charging (must receive 0 mAh and no drain event)
        val chargingOnlyApp = ObservedAppUsage(
            packageName = "com.example.charginggame",
            appLabel = "Charging Game",
            foregroundMs = 4 * 60_000L,
            launchCount = 1,
            activeIntervals = listOf((t10 + 1 * 60_000L) to t15),
            windowStartMs = t0,
            windowEndMs = t15
        )

        val events = BatteryTelemetryWorker.computeAppDrainEvents(
            observedApps = listOf(prePlugApp, chargingOnlyApp),
            windowStartMs = t0,
            windowEndMs = t15,
            samplesInWindow = listOf(s0, s10, s15Charging),
            fallbackPrevSample = s0,
            currentSample = s15Charging,
            ratedCapacityMah = 5000
        )

        assertEquals("Pre-plug discharge app must be preserved and charging-only app excluded", 1, events.size)
        val recordedEvent = events.first()
        assertEquals("com.example.preplug", recordedEvent.packageName)
        assertEquals(50.0f, recordedEvent.estimatedMah, 0.5f)
        assertEquals(5 * 60_000L, recordedEvent.foregroundMs)
    }

    // =========================================================================
    // SECTION 4: Reconcile episodes with summary totals and label mixed
    //            or estimated provenance
    // =========================================================================

    @Test
    fun section4ab_detectDrainEpisodesUsesReconciledIntervalsAndDetectsLevelOnlyPercentageDrop() {
        val base = utcTime(2026, 11, 25, 3, 0)
        // 1) Non-counter phone: 10 minutes at -270 mA on 4,500 mAh battery with level 80% -> 79% on final minute.
        //    Reconciled total is 45 mAh (not 90 mAh double-counted). Episode total must match 45 mAh.
        val reconciledSamples = (0..10).map { min ->
            sample(
                timestampMs = base + min * 60_000L,
                levelPct = if (min < 10) 80 else 79,
                voltageMv = 4000,
                currentMicroA = -270_000L,
                chargeCounterMicroAh = 0L,
                isScreenOn = false
            )
        }
        val reconciledReport = RootCauseAnalyzer.analyze(
            samples = reconciledSamples,
            latestSample = reconciledSamples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.ONE_HOUR,
            ratedCapacityMah = 4500,
            thermalAlertCelsius = 39f
        )
        assertEquals(45.0f, reconciledReport.totalDischargedMah, 0.5f)
        assertEquals(1, reconciledReport.drainEpisodes.size)
        assertEquals(45.0f, reconciledReport.drainEpisodes.first().chargeLostMah, 0.5f)
        assertEquals(ChargeEvidenceSource.ESTIMATED, reconciledReport.drainEpisodes.first().chargeEvidenceSource)
        assertFalse(reconciledReport.drainEpisodes.first().counterMeasured)

        // 2) Phone with neither charge counter nor current readings: 80% -> 77% drop on 4,500 mAh = 135 mAh
        val levelOnlySamples = listOf(
            sample(base, levelPct = 80, currentMicroA = 0L, chargeCounterMicroAh = 0L, isScreenOn = false),
            sample(base + 30 * 60_000L, levelPct = 77, currentMicroA = 0L, chargeCounterMicroAh = 0L, isScreenOn = false)
        )
        val levelOnlyReport = RootCauseAnalyzer.analyze(
            samples = levelOnlySamples,
            latestSample = levelOnlySamples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.ONE_HOUR,
            ratedCapacityMah = 4500,
            thermalAlertCelsius = 39f
        )
        assertEquals(135.0f, levelOnlyReport.totalDischargedMah, 0.5f)
        assertEquals(1, levelOnlyReport.drainEpisodes.size)
        val ep = levelOnlyReport.drainEpisodes.first()
        assertEquals(135.0f, ep.chargeLostMah, 0.5f)
        assertEquals(ChargeEvidenceSource.ESTIMATED, ep.chargeEvidenceSource)
        assertFalse(ep.counterMeasured)
    }

    @Test
    fun section4c_mixedAndEstimatedProvenanceLabeledAccuratelyInSummaryAndEpisodes() {
        val base = utcTime(2026, 11, 25, 4, 0)
        // 1 mAh counter reading + 100 mAh estimated reading (2% drop on 5,000 mAh) = 101 mAh MIXED
        val mixedSamples = listOf(
            sample(base, levelPct = 90, chargeCounterMicroAh = 4_500_000L, currentMicroA = -20_000L, isScreenOn = false),
            sample(base + 5 * 60_000L, levelPct = 90, chargeCounterMicroAh = 4_499_000L, currentMicroA = -20_000L, isScreenOn = false), // 1 mAh counter
            sample(base + 10 * 60_000L, levelPct = 88, chargeCounterMicroAh = 0L, currentMicroA = 0L, isScreenOn = false) // 100 mAh estimated
        )

        val mixedReport = RootCauseAnalyzer.analyze(
            samples = mixedSamples,
            latestSample = mixedSamples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.ONE_HOUR,
            ratedCapacityMah = 5000,
            thermalAlertCelsius = 39f
        )

        assertEquals(ChargeEvidenceSource.MIXED, mixedReport.chargeEvidenceSource)
        assertFalse("1 mAh counter + 100 mAh estimate must NOT be labeled purely chargeCounterMeasured", mixedReport.chargeCounterMeasured)
        assertEquals(1.0f, mixedReport.counterMeasuredDischargedMah, 0.2f)
        assertEquals(100.0f, mixedReport.estimatedDischargedMah, 0.5f)
        assertEquals(1, mixedReport.drainEpisodes.size)
        val mixedEp = mixedReport.drainEpisodes.first()
        assertEquals(ChargeEvidenceSource.MIXED, mixedEp.chargeEvidenceSource)
        assertFalse(mixedEp.counterMeasured)

        val md = DiagnosticReportGenerator.generateMarkdownSummary(mixedReport, mixedSamples.last())
        assertTrue("Markdown must label mixed provenance: $md", md.contains("Mixed: 1.0 mAh Hardware Battery Counter + 100.0 mAh Estimated"))
        assertTrue("Episode section must label Mixed provenance: $md", md.contains("mixed: 1.0 mAh counter-measured + 100.0 mAh estimated"))
    }

    // =========================================================================
    // SECTION 5: Keep spike drill-down provenance and event windows consistent
    // =========================================================================

    @Test
    fun section5a_spikeForensicReportTracksChargeProvenanceSeparatelyBeforeAndAfterSpike() {
        val t0 = utcTime(2026, 11, 25, 1, 0)
        // Before spike: 45 mAh estimate (80% -> 79% on 4,500 mAh with counter unavailable = 0)
        // At/After spike: 5 mAh counter reading (3,555,000 uAh -> 3,550,000 uAh)
        val samples = listOf(
            sample(t0, levelPct = 80, chargeCounterMicroAh = 0L, currentMicroA = -100_000L),
            sample(t0 + 10 * 60_000L, levelPct = 79, chargeCounterMicroAh = 3_555_000L, currentMicroA = -800_000L),
            sample(t0 + 20 * 60_000L, levelPct = 79, chargeCounterMicroAh = 3_550_000L, currentMicroA = -100_000L)
        )

        val report = SpikeInspector.analyzeSpike(
            samples = samples,
            targetIndex = 1,
            usageEvents = emptyList(),
            appUsage = emptyList(),
            appDrainEvents = emptyList(),
            energyMeters = emptyList(),
            hasUsageAccess = true,
            ratedCapacityMah = 4500
        )

        assertEquals(45.0f, report.chargeLostBeforeMah ?: 0f, 0.5f)
        assertEquals(5.0f, report.chargeLostAfterMah ?: 0f, 0.5f)
        assertFalse("45 mAh before-spike loss was estimated, not counter-measured", report.counterMeasuredBefore)
        assertTrue("5 mAh after-spike loss was counter-measured", report.counterMeasuredAfter)
        assertFalse("Overall counterMeasured must not be true when before-spike loss is estimated", report.counterMeasured)
        assertTrue(
            "Likely drivers must describe before-spike 45 mAh loss as Estimated, not Confirmed: ${report.likelyDrivers}",
            report.likelyDrivers.any { it.contains("Estimated 45.0 mAh charge loss", ignoreCase = true) }
        )
        assertFalse(
            report.likelyDrivers.any { it.contains("Confirmed 45.0 mAh", ignoreCase = true) }
        )
    }

    @Test
    fun section5b_spikeDrillDownRestrictsEventsToDisplayedWindowBounds() {
        // Samples at 1:59, 2:00, and 2:01 a.m. -> displayed window is 01:59..02:01 (2 minutes)
        val t156 = utcTime(2026, 11, 25, 1, 56)
        val t159 = utcTime(2026, 11, 25, 1, 59)
        val t200 = utcTime(2026, 11, 25, 2, 0)
        val t201 = utcTime(2026, 11, 25, 2, 1)

        val samples = listOf(
            sample(t159, levelPct = 85, currentMicroA = -100_000L, chargeCounterMicroAh = 3_825_000L),
            sample(t200, levelPct = 85, currentMicroA = -800_000L, chargeCounterMicroAh = 3_810_000L), // 3.2 W spike
            sample(t201, levelPct = 84, currentMicroA = -100_000L, chargeCounterMicroAh = 3_805_000L)
        )

        val (winStart, winEnd) = SpikeInspector.computeSpikeWindowBounds(samples, targetIndex = 1)
        assertEquals(t159, winStart)
        assertEquals(t201, winEnd)

        val eventOutsideAt156 = WindowUsageEventItem(
            timestampMs = t156,
            packageName = "com.example.earlysync",
            appLabel = "1:56am Sync Service",
            eventLabel = "Foreground Service Started",
            isBackgroundService = true
        )
        val eventInsideAt200 = WindowUsageEventItem(
            timestampMs = t200,
            packageName = "com.example.inwindowsync",
            appLabel = "2:00am Backup Service",
            eventLabel = "Foreground Service Started",
            isBackgroundService = true
        )

        val report = SpikeInspector.analyzeSpike(
            samples = samples,
            targetIndex = 1,
            usageEvents = listOf(eventOutsideAt156, eventInsideAt200),
            appUsage = emptyList(),
            appDrainEvents = emptyList(),
            energyMeters = listOf(
                EnergyMeterSnapshotEntity(
                    timestampMs = t159,
                    elapsedRealtimeMs = 10_000_000L,
                    meterName = "CELLULAR",
                    meterType = "MEASUREMENT",
                    energyMicroWattSec = 100_000_000L,
                    readingTimestampMs = 10_000_000L
                ),
                EnergyMeterSnapshotEntity(
                    timestampMs = t201,
                    elapsedRealtimeMs = 10_120_000L,
                    meterName = "CELLULAR",
                    meterType = "MEASUREMENT",
                    energyMicroWattSec = 280_000_000L,
                    readingTimestampMs = 10_120_000L
                )
            ),
            hasUsageAccess = true,
            ratedCapacityMah = 4500
        )

        assertEquals(t159, report.windowStartMs)
        assertEquals(t201, report.windowEndMs)
        assertEquals(1, report.correlatedEvents.size)
        assertEquals("2:00am Backup Service", report.correlatedEvents.first().appLabel)
        assertFalse("1:56 a.m. service outside 1:59-2:01 window must not be attributed", report.likelyDrivers.any { it.contains("1:56am") })
        assertTrue(report.likelyDrivers.any { it.contains("2:00am Backup Service") })
        assertEquals(1, report.energyMeterDeltas.size)
        assertEquals("CELLULAR", report.energyMeterDeltas.first().meterName)
    }
}

/**
 * Robolectric tests verifying watch match counting and notification delivery across both
 * execution orders (screen-first vs worker-first) and worker retries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class V8WatchDeduplicationRobolectricTest {

    @Test
    fun section2d_watchMatchAndNotificationDeduplicateAcrossScreenFirstAndWorkerFirstOrders() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        org.robolectric.Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val prefs = MonitorPreferences(context)

        val armedAt = 1_764_000_000_000L
        val eventTs1 = armedAt + 30 * 60_000L
        val eventId1 = "watch_peak_$eventTs1"

        // Order A: Screen opens BEFORE worker runs -> screen records match count = 1,
        //          then worker runs -> must still deliver notification (not suppressed!) and keep match count = 1.
        prefs.clearWatchInvestigation()
        prefs.setCriticalAlertsEnabled(true)
        prefs.armWatchInvestigation(
            WatchInvestigationConfig(
                active = true,
                title = "Watch Standby Spike",
                suspectLabel = "Sample Sync",
                triggerDrainPctPerHour = 1.8f,
                triggerPeakMw = 2_000f,
                armedAtMs = armedAt
            )
        )

        // 1) Screen opens first and calls recordWatchMatch
        val screenRecorded = prefs.recordWatchMatch(eventId1, eventTs1, "Screen-off peak hit -3.10 W")
        assertTrue("Screen-first evaluation should record the new match", screenRecorded)
        val afterScreenSettings = prefs.settingsFlow.first()
        assertEquals(1, afterScreenSettings.watchConfig.matchCount)
        assertEquals(eventId1, afterScreenSettings.watchConfig.lastMatchedEventId)
        assertEquals("", afterScreenSettings.watchConfig.lastNotifiedEventId)

        // 2) Worker runs afterward and calls notifyWatchMatchIfNeeded
        val notifiedAfterScreen = BatteryAlertManager.notifyWatchMatchIfNeeded(
            context = context,
            title = afterScreenSettings.watchConfig.title,
            reason = "Screen-off peak hit -3.10 W",
            matchedEventId = eventId1,
            matchedAtMs = eventTs1,
            settings = afterScreenSettings,
            preferences = prefs
        )
        assertTrue("Opening the screen before the worker runs must NOT suppress the notification", notifiedAfterScreen)
        val afterWorkerSettings = prefs.settingsFlow.first()
        assertEquals("Worker after screen must not double-count matchCount", 1, afterWorkerSettings.watchConfig.matchCount)
        assertEquals(eventId1, afterWorkerSettings.watchConfig.lastNotifiedEventId)

        // 3) Worker retries on the same event -> must neither double-count nor re-notify
        val notifiedOnRetry = BatteryAlertManager.notifyWatchMatchIfNeeded(
            context = context,
            title = afterWorkerSettings.watchConfig.title,
            reason = "Screen-off peak hit -3.10 W",
            matchedEventId = eventId1,
            matchedAtMs = eventTs1,
            settings = afterWorkerSettings,
            preferences = prefs
        )
        assertFalse("Worker retry must not re-post notification", notifiedOnRetry)
        assertEquals(1, prefs.settingsFlow.first().watchConfig.matchCount)

        // Order B: Worker runs BEFORE screen opens on a second distinct post-arm event
        val eventTs2 = armedAt + 90 * 60_000L
        val eventId2 = "watch_peak_$eventTs2"
        val beforeEvent2Settings = prefs.settingsFlow.first()
        val workerFirstNotified = BatteryAlertManager.notifyWatchMatchIfNeeded(
            context = context,
            title = beforeEvent2Settings.watchConfig.title,
            reason = "Screen-off peak hit -3.40 W",
            matchedEventId = eventId2,
            matchedAtMs = eventTs2,
            settings = beforeEvent2Settings,
            preferences = prefs
        )
        assertTrue("Worker-first evaluation should post notification", workerFirstNotified)
        val afterWorkerFirstSettings = prefs.settingsFlow.first()
        assertEquals(2, afterWorkerFirstSettings.watchConfig.matchCount)

        // Screen opens afterward -> must not increment matchCount again
        val screenAfterWorkerRecorded = prefs.recordWatchMatch(eventId2, eventTs2, "Screen-off peak hit -3.40 W")
        assertFalse("Screen after worker must not double-count", screenAfterWorkerRecorded)
        assertEquals(2, prefs.settingsFlow.first().watchConfig.matchCount)
    }
}
