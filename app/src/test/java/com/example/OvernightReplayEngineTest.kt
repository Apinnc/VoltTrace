package com.example

import com.example.domain.CsvTelemetryParser
import com.example.domain.OvernightReplayEngine
import com.example.domain.OvernightReportMode
import com.example.domain.WatchInvestigationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class OvernightReplayEngineTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun loadSyntheticCsvSamples() =
        CsvTelemetryParser.parse(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("synthetic-discharge.csv")) {
                "synthetic-discharge.csv test resource missing"
            }.bufferedReader().use { it.readText() }
        )

    @Test
    fun buildsMorningReportWithPersonalBaselineAndKeySegmentFromSyntheticCsv() {
        val samples = loadSyntheticCsvSamples()
        assertEquals(18, samples.size)

        val report = OvernightReplayEngine.analyze(
            allSamples = samples,
            ratedCapacityMah = 5000,
            nowMs = samples.last().timestampMs + 10 * 60_000L,
            timeZone = utc
        )

        // 1. Mode & how much battery was lost (4,500 -> 4,055 mAh = 445 mAh; 90% -> 81% = 9%)
        assertEquals(OvernightReportMode.OVERNIGHT_STANDBY, report.mode)
        assertEquals(90, report.startLevelPct)
        assertEquals(81, report.endLevelPct)
        assertEquals(9, report.levelLostPct)
        assertTrue(report.counterMeasured)
        assertEquals(445.0f, report.chargeLostMah, 1.0f)

        // 2. Personal baseline built from the 3 prior calm nights (~0.45%/hr) and flags night 4 as elevated
        assertEquals(3, report.baseline.nightsCount)
        assertNotNull(report.baseline.typicalDrainPctPerHour)
        assertTrue(
            "Expected typical baseline rate ~0.4-0.5%/hr, got ${report.baseline.typicalDrainPctPerHour}",
            (report.baseline.typicalDrainPctPerHour ?: 0f) in 0.35f..0.60f
        )
        assertTrue("Anomalous night should be elevated vs 3-night personal baseline", report.baseline.isElevatedVsBaseline)
        assertTrue(report.baseline.statusLabel.contains("above your"))

        // 3. Which part of the night mattered most (01:15 -> 02:00 drove ~285 mAh = ~64% of total loss)
        val key = requireNotNull(report.keySegment) { "Expected a key segment for the 01:15-02:00 burst" }
        assertTrue("Expected key segment to drive >= 55% of night loss, got ${key.shareOfNightLossPct}%", key.shareOfNightLossPct >= 55f)
        assertEquals(285.0f, key.chargeLostMah, 2.0f)
        assertEquals("Sample Cloud Sync", key.suspectLabel)
        assertTrue(key.headline.contains("01:15"))
        assertTrue(key.headline.contains("02:00"))
        assertTrue(key.sourceReadings.size >= 2)
        assertTrue(key.whatPhoneRecorded.isNotEmpty())
        assertTrue(key.whatRemainsUnknown.isNotEmpty())

        // 4. What the phone actually recorded summary is populated for the 10-second check
        assertTrue(report.whatPhoneRecordedSummary.size >= 3)
        assertTrue(report.whatPhoneRecordedSummary.any { it.contains("445 mAh") })
        assertTrue(report.whatPhoneRecordedSummary.any { it.contains("Kernel clocks") })
    }

    @Test
    fun fallsBackToBeforeYouPluggedInWhenPhoneChargedOvernight() {
        val report = OvernightReplayEngine.createSyntheticPreviewReport(OvernightReportMode.BEFORE_PLUGGED_IN)

        assertEquals(OvernightReportMode.BEFORE_PLUGGED_IN, report.mode)
        assertNotNull("Fallback should capture exact plug-in timestamp", report.pluggedInAtMs)
        assertTrue("Fallback should retain pre-plug replay samples", report.samples.size >= 3)
        assertEquals(68, report.startLevelPct)
        assertEquals(59, report.endLevelPct)
        assertEquals(9, report.levelLostPct)
        assertEquals(420.0f, report.chargeLostMah, 2.0f)
        assertEquals(2, report.baseline.nightsCount)
        assertNotNull(report.keySegment)
    }

    @Test
    fun watchForThisAgainMatchesRecurrenceOnlyAfterArmedTimestamp() {
        val samples = loadSyntheticCsvSamples()
        val night4StartMs = samples[9].timestampMs
        val night4EndMs = samples.last().timestampMs

        // Armed BEFORE night 4 started -> must match night 4's spike and suspect app
        val armedBefore = WatchInvestigationConfig(
            active = true,
            title = "Watch: Sample Cloud Sync (01:15–02:00)",
            suspectLabel = "Sample Cloud Sync",
            triggerDrainPctPerHour = 1.0f,
            triggerPeakMw = 2_500f,
            armedAtMs = night4StartMs - 60_000L
        )
        val matchedReport = OvernightReplayEngine.analyze(
            allSamples = samples,
            watchConfig = armedBefore,
            ratedCapacityMah = 5000,
            nowMs = night4EndMs + 10 * 60_000L,
            timeZone = utc
        )
        assertTrue("Watch armed before night 4 should match", matchedReport.watchTriggeredThisWindow)
        assertNotNull(matchedReport.watchMatchReason)
        assertTrue(matchedReport.watchMatchReason!!.contains("Sample Cloud Sync"))

        // Armed AFTER night 4 ended -> must NOT falsely fire on already-finished history
        val armedAfter = armedBefore.copy(armedAtMs = night4EndMs + 60_000L)
        val postArmedReport = OvernightReplayEngine.analyze(
            allSamples = samples,
            watchConfig = armedAfter,
            ratedCapacityMah = 5000,
            nowMs = night4EndMs + 10 * 60_000L,
            timeZone = utc
        )
        assertFalse("Watch armed after night ended must not fire on past data", postArmedReport.watchTriggeredThisWindow)

        // Armed BEFORE calm night 3 -> must NOT falsely fire when night stays calm
        val calmSamples = samples.take(9)
        val armedBeforeCalm = WatchInvestigationConfig(
            active = true,
            title = "Watch: Standby Spike",
            suspectLabel = "Sample Cloud Sync",
            triggerDrainPctPerHour = 1.5f,
            triggerPeakMw = 2_000f,
            armedAtMs = calmSamples[6].timestampMs - 60_000L
        )
        val calmReport = OvernightReplayEngine.analyze(
            allSamples = calmSamples,
            watchConfig = armedBeforeCalm,
            ratedCapacityMah = 5000,
            nowMs = calmSamples.last().timestampMs + 10 * 60_000L,
            timeZone = utc
        )
        assertFalse("Calm night should not trigger elevated watch rule", calmReport.watchTriggeredThisWindow)
    }

    @Test
    fun returnsAwaitingDataModeForFreshInstall() {
        val report = OvernightReplayEngine.analyze(emptyList())
        assertEquals(OvernightReportMode.AWAITING_DATA, report.mode)
        assertTrue(report.samples.isEmpty())
    }
}
