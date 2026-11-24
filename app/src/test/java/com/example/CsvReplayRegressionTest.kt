package com.example

import com.example.data.local.EnergyMeterSnapshotEntity
import com.example.domain.AnalysisWindow
import com.example.domain.CsvTelemetryParser
import com.example.domain.DiagnosticReportGenerator
import com.example.domain.RootCauseAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvReplayRegressionTest {

    /**
     * Anonymized, synthetic 6-hour telemetry CSV exercising:
     * - Legacy records with zeroed boot clocks (elapsed_realtime_ms = 0, uptime_ms = 0)
     * - Counter-first charge loss accounting
     * - Kernel deep sleep vs. CPU awake calculations from monotonic boot clocks
     * - Instantaneous discharge peak and thermal peak timestamping
     * - Traceable DrainEpisode generation
     */
    private val syntheticTelemetryCsv = """
timestamp_utc,level_pct,voltage_mv,temp_c,current_ma,current_signed_ma,power_mw,power_signed_mw,charge_counter_uah,elapsed_realtime_ms,uptime_ms,is_charging,plugged_type,screen_on,brightness_pct,refresh_hz,auto_brightness,network_transport,cellular_active,wifi_enabled,wifi_connected,gps_enabled,bluetooth_enabled,airplane_mode,link_down_kbps,metered_network,thermal_status,power_save,foreground_app,source
2025-01-15T10:00:00Z,90,4200,28.5,120.0,-120.0,504.0,-504.0,4500000,0,0,false,BATTERY,false,0,60,true,WIFI,false,true,true,false,false,false,50000,false,0,false,"",WORK
2025-01-15T10:15:00Z,89,4180,29.0,150.0,-150.0,627.0,-627.0,4460000,0,0,false,BATTERY,false,0,60,true,WIFI,false,true,true,false,false,false,50000,false,0,false,"",WORK
2025-01-15T10:30:00Z,88,4150,30.2,300.0,-300.0,1245.0,-1245.0,4400000,0,0,false,BATTERY,true,55,120,true,WIFI,false,true,true,false,false,false,50000,false,0,false,"Sample Browser",LIVE
2025-01-15T10:45:00Z,85,4060,33.4,1950.0,-1950.0,7917.0,-7917.0,4260000,10000000,4000000,false,BATTERY,true,75,120,true,CELLULAR,true,true,false,true,true,false,12000,true,0,false,"Sample Maps",LIVE
2025-01-15T11:00:00Z,82,4020,35.6,2200.0,-2200.0,8844.0,-8844.0,4110000,10900000,4900000,false,BATTERY,true,80,120,true,CELLULAR,true,true,false,true,true,false,12000,true,0,false,"Sample Maps",LIVE
2025-01-15T11:30:00Z,81,4120,30.1,95.0,-95.0,391.4,-391.4,4070000,12700000,5200000,false,BATTERY,false,0,60,true,WIFI,false,true,true,false,false,false,60000,false,0,false,"",WORK
2025-01-15T12:30:00Z,80,4110,28.8,80.0,-80.0,328.8,-328.8,4020000,16300000,5620000,false,BATTERY,false,0,60,true,WIFI,false,true,true,false,false,false,60000,false,0,false,"",WORK
2025-01-15T13:00:00Z,76,4010,34.2,1200.0,-1200.0,4812.0,-4812.0,3820000,18100000,7200000,false,BATTERY,true,65,120,true,WIFI,false,true,true,false,true,false,60000,false,0,false,"Sample Video",LIVE
    """.trimIndent()

    @Test
    fun replaysSyntheticCsvAndVerifiesCounterChargeDeepSleepAndEpisodes() {
        val samples = CsvTelemetryParser.parse(syntheticTelemetryCsv)
        assertEquals("Expected 8 telemetry rows in synthetic CSV", 8, samples.size)

        val report = RootCauseAnalyzer.analyze(
            samples = samples,
            latestSample = samples.last(),
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.SIX_HOURS,
            ratedCapacityMah = 5000,
            thermalAlertCelsius = 39.0f
        )

        // 1. Exactly 3 legacy records with 0 in both clock fields
        assertEquals(3, report.missingClockSampleCount)

        // 2. Net charge loss measured from hardware battery counter: 4,500,000 -> 3,820,000 uAh = 680.0 mAh
        assertTrue("Charge loss must be counter-measured", report.chargeCounterMeasured)
        assertEquals(680.0f, report.totalDischargedMah, 0.5f)

        // 3. Total unplugged deep sleep across valid clock spans:
        //    10:45->11:00: 15m elapsed, 15m awake, 0m sleep
        //    11:00->11:30: 30m elapsed, 5m awake, 25m sleep
        //    11:30->12:30: 60m elapsed, 7m awake, 53m sleep
        //    12:30->13:00: 30m elapsed, 26.33m awake, 3.67m sleep
        //    Total deep sleep ~= 81.67 min
        val deepSleepMinutes = report.totalUnpluggedDeepSleepMs / 60_000.0
        assertTrue(
            "Expected ~81.7 minutes of deep sleep (81.0..82.5), got $deepSleepMinutes min",
            deepSleepMinutes in 81.0..82.5
        )

        // 4. Instantaneous discharge peak: 8.844 W (8,844 mW @ 11:00:00Z)
        assertEquals(8844.0f, report.peakDischargePowerMw, 2.0f)
        assertEquals(
            "2025-01-15T11:00:00Z",
            report.peakDischargePowerTimestampMs?.let { RootCauseAnalyzer.formatIsoUtc(it) }
        )

        // 5. Peak battery temperature: 35.6 C discharging at 11:00:00Z
        assertEquals(35.6f, report.peakTempC, 0.05f)
        assertEquals(false, report.peakTempWasCharging)
        assertEquals(
            "2025-01-15T11:00:00Z",
            report.peakTempTimestampMs?.let { RootCauseAnalyzer.formatIsoUtc(it) }
        )

        // 6. Traceable drain episodes must include observed activity, unknowns, and raw source readings
        assertTrue("Expected significant drain episodes to be detected", report.drainEpisodes.size >= 2)
        report.drainEpisodes.forEach { ep ->
            assertTrue("Episode ${ep.id} missing observedActivity", ep.observedActivity.isNotEmpty())
            assertTrue("Episode ${ep.id} missing whatRemainsUnknown", ep.whatRemainsUnknown.isNotEmpty())
            assertTrue("Episode ${ep.id} missing sourceReadings", ep.sourceReadings.size >= 2)
        }

        // 7. Hardware attribution honesty: 0 energy meters => explicitly labeled modeled
        assertTrue(report.hardwareAttributionMode.contains("Modeled", ignoreCase = true))

        val regeneratedMarkdown = DiagnosticReportGenerator.generateMarkdownSummary(
            report = report,
            latestSample = samples.last()
        )
        assertTrue(regeneratedMarkdown.contains("680.0 mAh"))
        assertTrue(regeneratedMarkdown.contains("-8.84 W"))
        assertTrue(regeneratedMarkdown.contains("3 legacy/pre-upgrade records"))
        assertTrue(regeneratedMarkdown.contains("Significant Drain Episodes"))
        assertTrue(regeneratedMarkdown.contains("What Remains Unknown"))
        assertTrue(regeneratedMarkdown.contains("Source Readings"))
    }

    @Test
    fun summarizesPowerMonitorEnergyMetersAcrossSameBootSnapshotsAndRejectsRebootResets() {
        val base = 1_736_935_200_000L
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
