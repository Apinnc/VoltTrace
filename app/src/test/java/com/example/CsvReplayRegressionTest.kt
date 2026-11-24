package com.example

import com.example.data.local.EnergyMeterSnapshotEntity
import com.example.domain.AnalysisWindow
import com.example.domain.CsvTelemetryParser
import com.example.domain.DiagnosticReportGenerator
import com.example.domain.RootCauseAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CsvReplayRegressionTest {

    @Test
    fun replaysSyntheticCsvAndReproducesKnownMetrics() {
        val csv = requireNotNull(javaClass.getResource("/synthetic-discharge.csv")).readText()
        val samples = CsvTelemetryParser.parse(csv)
        assertEquals(7, samples.size)

        val report = RootCauseAnalyzer.analyze(
            samples = samples,
            latestSample = samples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.SIX_HOURS,
            ratedCapacityMah = 5000,
            thermalAlertCelsius = 39.0f
        )

        // Six 15-minute intervals each lose 50 mAh. The first two rows lack boot clocks.
        assertEquals(2, report.missingClockSampleCount)
        assertTrue(report.chargeCounterMeasured)
        assertEquals(300f, report.totalDischargedMah, 0.01f)
        // Across the four intervals with clock readings, 60 minutes elapse and 20 are awake.
        assertEquals(40L * 60_000L, report.totalUnpluggedDeepSleepMs)
        assertEquals(6000f, report.peakDischargePowerMw, 0.01f)
        assertEquals(
            "2000-01-01T00:45:00Z",
            report.peakDischargePowerTimestampMs?.let { RootCauseAnalyzer.formatIsoUtc(it) }
        )
        assertEquals(32f, report.peakTempC, 0.01f)
        assertEquals(false, report.peakTempWasCharging)
        assertEquals(
            "2000-01-01T01:00:00Z",
            report.peakTempTimestampMs?.let { RootCauseAnalyzer.formatIsoUtc(it) }
        )

        assertEquals(6, report.drainEpisodes.size)
        report.drainEpisodes.forEach { ep ->
            assertTrue("Episode ${ep.id} missing observedActivity", ep.observedActivity.isNotEmpty())
            assertTrue("Episode ${ep.id} missing whatRemainsUnknown", ep.whatRemainsUnknown.isNotEmpty())
            assertTrue("Episode ${ep.id} missing sourceReadings", ep.sourceReadings.size >= 2)
        }
        assertTrue(report.hardwareAttributionMode.contains("Modeled", ignoreCase = true))

        val regeneratedMarkdown = DiagnosticReportGenerator.generateMarkdownSummary(
            report = report,
            latestSample = samples.last()
        )
        assertTrue(regeneratedMarkdown.contains("300.0 mAh"))
        assertTrue(regeneratedMarkdown.contains("40m 0s"))
        assertTrue(regeneratedMarkdown.contains("40.0 min"))
        assertTrue(regeneratedMarkdown.contains("-6.00 W"))
        assertTrue(regeneratedMarkdown.contains("2 legacy/pre-upgrade records"))
        assertTrue(regeneratedMarkdown.contains("Significant Drain Episodes"))
        assertTrue(regeneratedMarkdown.contains("What Remains Unknown"))
        assertTrue(regeneratedMarkdown.contains("Source Readings"))

        val outFile = File("build/outputs/volttrace_regenerated_report.md").apply {
            parentFile?.mkdirs()
        }
        outFile.writeText(regeneratedMarkdown)
    }

    @Test
    fun summarizesPowerMonitorEnergyMetersAcrossSameBootSnapshotsAndRejectsRebootResets() {
        val base = 1_760_000_000_000L
        val snapshots = listOf(
            // Boot 1: Display ODPM rail increases by 360_000_000 uWs = 100.0 mWh
            EnergyMeterSnapshotEntity(
                timestampMs = base,
                elapsedRealtimeMs = 1_000_000L,
                meterName = "DISPLAY",
                meterType = "MEASUREMENT",
                energyMicroWattSec = 500_000_000L,
                readingTimestampMs = 1_000_000L
            ),
            EnergyMeterSnapshotEntity(
                timestampMs = base + 15 * 60_000L,
                elapsedRealtimeMs = 1_900_000L,
                meterName = "DISPLAY",
                meterType = "MEASUREMENT",
                energyMicroWattSec = 860_000_000L,
                readingTimestampMs = 1_900_000L
            ),
            // Simulated reboot: elapsedRealtime resets to 60_000L and counter resets to 10_000_000L
            EnergyMeterSnapshotEntity(
                timestampMs = base + 30 * 60_000L,
                elapsedRealtimeMs = 60_000L,
                meterName = "DISPLAY",
                meterType = "MEASUREMENT",
                energyMicroWattSec = 10_000_000L,
                readingTimestampMs = 60_000L
            ),
            // Boot 2: increases by another 180_000_000 uWs = 50.0 mWh => total 150.0 mWh
            EnergyMeterSnapshotEntity(
                timestampMs = base + 45 * 60_000L,
                elapsedRealtimeMs = 960_000L,
                meterName = "DISPLAY",
                meterType = "MEASUREMENT",
                energyMicroWattSec = 190_000_000L,
                readingTimestampMs = 960_000L
            )
        )

        val summaries = RootCauseAnalyzer.summarizeEnergyMeters(snapshots, avgVoltageVolts = 4.0f)
        assertEquals(1, summaries.size)
        val display = summaries.first()
        assertEquals("DISPLAY", display.meterName)
        assertEquals("MEASUREMENT", display.meterType)
        assertEquals(150.0f, display.deltaMWh, 0.05f)
        assertEquals(37.5f, display.estimatedMah, 0.05f)
    }
}
