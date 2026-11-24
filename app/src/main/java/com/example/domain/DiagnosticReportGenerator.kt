package com.example.domain

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.hardware.BatteryHardwareReader
import com.example.data.local.BatterySampleEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ExportFormat(val extension: String, val mimeType: String) {
    MARKDOWN_SUMMARY("md", "text/markdown"),
    CSV_TELEMETRY("csv", "text/csv")
}

object DiagnosticReportGenerator {

    private fun isoFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private fun readableFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun generateMarkdownSummary(
        report: BatteryAnalysisReport,
        latestSample: BatterySampleEntity?
    ): String {
        val nowStr = readableFormat().format(Date())
        val sb = StringBuilder()

        sb.appendLine("# VoltTrace Battery Forensic Report")
        sb.appendLine("Generated: $nowStr | Window: **${report.window.label}** | Samples: ${report.sampleCount} (${RootCauseAnalyzer.formatDuration(report.spanDurationMs)})")
        sb.appendLine()

        sb.appendLine("## 1. Executive Summary")
        sb.appendLine("- **Efficiency Score**: ${report.efficiencyScore}/100")
        val chargeSourceTag = if (report.chargeCounterMeasured) "Hardware Battery Counter" else "Estimated"
        sb.appendLine(
            "- **Net Charge Loss**: ${String.format(Locale.US, "%.1f mAh", report.totalDischargedMah)} " +
                "($chargeSourceTag) | Overall Rate: ${String.format(Locale.US, "%.2f%%/hr", report.overallDrainRatePctPerHour)}"
        )
        sb.appendLine("- **Pure Screen-On Drain**: ${String.format(Locale.US, "%.2f%%/hr", report.screenOnDrainRatePctPerHour)} across ${RootCauseAnalyzer.formatDuration(report.screenOnDurationMs)}")
        sb.appendLine("- **Pure Standby (Screen-Off) Drain**: ${String.format(Locale.US, "%.2f%%/hr", report.screenOffDrainRatePctPerHour)} across ${RootCauseAnalyzer.formatDuration(report.screenOffDurationMs)}")
        val budgetParts = buildList {
            add("Screen-On ${RootCauseAnalyzer.formatDuration(report.screenOnDurationMs)}")
            add("Pure Standby ${RootCauseAnalyzer.formatDuration(report.screenOffDurationMs)}")
            if (report.mixedTransitionDurationMs > 0L) {
                add("Mixed Screen Transitions ${RootCauseAnalyzer.formatDuration(report.mixedTransitionDurationMs)}")
            }
            if (report.chargingDurationMs > 0L) {
                add(
                    "Charging ${RootCauseAnalyzer.formatDuration(report.chargingDurationMs)} " +
                        "(+${String.format(Locale.US, "%.1f mAh", report.totalChargedMah)})"
                )
            }
            if (report.gapDurationMs > 0L) {
                add("Collection Gaps ${RootCauseAnalyzer.formatDuration(report.gapDurationMs)}")
            }
        }
        sb.appendLine("- **Window Time Budget**: ${budgetParts.joinToString(" | ")}")
        if (report.totalUnpluggedElapsedMs > 0L) {
            val totalSleepPct = (report.totalUnpluggedDeepSleepMs.toFloat() / report.totalUnpluggedElapsedMs.toFloat()) * 100f
            val totalAwakePct = (report.totalUnpluggedAwakeMs.toFloat() / report.totalUnpluggedElapsedMs.toFloat()) * 100f
            val totalSleepMin = report.totalUnpluggedDeepSleepMs / 60_000f
            val totalAwakeMin = report.totalUnpluggedAwakeMs / 60_000f
            sb.appendLine(
                "- **Total Unplugged Kernel Deep Sleep**: ${RootCauseAnalyzer.formatDuration(report.totalUnpluggedDeepSleepMs)} " +
                    "(${String.format(Locale.US, "%.1f min", totalSleepMin)}, ${String.format(Locale.US, "%.1f%%", totalSleepPct)}) asleep / " +
                    "${RootCauseAnalyzer.formatDuration(report.totalUnpluggedAwakeMs)} " +
                    "(${String.format(Locale.US, "%.1f min", totalAwakeMin)}, ${String.format(Locale.US, "%.1f%%", totalAwakePct)}) CPU awake across " +
                    "${RootCauseAnalyzer.formatDuration(report.totalUnpluggedElapsedMs)} valid unplugged clock span"
            )
        }
        val standbyClockTotal = report.totalDeepSleepMs + report.totalStandbyAwakeMs
        if (standbyClockTotal > 0L) {
            val sleepPct = (report.totalDeepSleepMs.toFloat() / standbyClockTotal.toFloat()) * 100f
            sb.appendLine(
                "- **Kernel Standby Deep Sleep (Pure Screen-Off)**: ${String.format(Locale.US, "%.1f%%", sleepPct)} " +
                    "(${RootCauseAnalyzer.formatDuration(report.totalDeepSleepMs)} asleep / " +
                    "${RootCauseAnalyzer.formatDuration(report.totalStandbyAwakeMs)} CPU awake)"
            )
        }
        sb.appendLine("- **Radio Efficiency**: Wi-Fi ${String.format(Locale.US, "%.2f%%/hr", report.wifiDrainRatePctPerHour)} vs Cellular ${String.format(Locale.US, "%.2f%%/hr", report.cellularDrainRatePctPerHour)}")
        val peakTempStamp = report.peakTempTimestampMs?.let { " @ ${RootCauseAnalyzer.formatIsoUtc(it)}" }.orEmpty()
        val peakTempState = if (report.peakTempWasCharging) "Charging" else "Discharging"
        val peakDisStamp = report.peakDischargePowerTimestampMs?.let { " @ ${RootCauseAnalyzer.formatIsoUtc(it)}" }.orEmpty()
        val peakChgStr = if (report.peakChargePowerMw > 10f) {
            val chgStamp = report.peakChargePowerTimestampMs?.let { " @ ${RootCauseAnalyzer.formatIsoUtc(it)}" }.orEmpty()
            " | Peak Charge +${String.format(Locale.US, "%.2f W", report.peakChargePowerMw / 1000f)}$chgStamp"
        } else {
            ""
        }
        sb.appendLine(
            "- **Thermal & Electrical**: Avg ${String.format(Locale.US, "%.1f°C", report.avgTempC)} " +
                "(Peak ${String.format(Locale.US, "%.1f°C", report.peakTempC)}$peakTempStamp · $peakTempState) | " +
                "Voltage ${report.minVoltageMv}–${report.maxVoltageMv} mV | " +
                "Peak Instantaneous Discharge -${String.format(Locale.US, "%.2f W", report.peakDischargePowerMw / 1000f)}$peakDisStamp$peakChgStr"
        )
        sb.appendLine("- **Hardware Attribution Mode**: ${report.hardwareAttributionMode}")
        if (report.missingClockSampleCount > 0) {
            sb.appendLine(
                "- **Data Quality / Clock Provenance**: ${report.missingClockSampleCount} legacy/pre-upgrade records had `0` in `elapsed_realtime_ms`/`uptime_ms` and were excluded from kernel deep-sleep deltas."
            )
        }
        if (latestSample != null) {
            val signedPower = String.format(Locale.US, "%+.2f W", latestSample.powerSignedWatts)
            val state = if (latestSample.isCharging) "Charging (${latestSample.pluggedType})" else "Discharging"
            sb.appendLine("- **Cell Profile**: ${latestSample.technology} | Health: ${BatteryHardwareReader.healthLabel(latestSample.healthCode)} | Current SOC: ${latestSample.levelPct}% ($state · $signedPower)")
        }
        sb.appendLine()

        sb.appendLine("## 2. Discovered Hardware Energy Meters (SystemHealthManager PowerMonitor)")
        if (report.discoveredEnergyMeters.isEmpty()) {
            sb.appendLine("No on-device PowerMonitor energy meter snapshots were recorded for this window. Per-component hardware attribution below uses VoltTrace's calibrated power-profile model bounded by the measured battery counter.")
        } else {
            sb.appendLine("| Meter Name | Type | Delta Energy (mWh) | Est. mAh | Samples |")
            sb.appendLine("|---|---|---:|---:|---:|")
            report.discoveredEnergyMeters.forEach { m ->
                sb.appendLine(
                    "| ${m.meterName} | ${m.meterType} | ${String.format(Locale.US, "%.2f", m.deltaMWh)} | " +
                        "${String.format(Locale.US, "%.1f", m.estimatedMah)} | ${m.sampleCount} |"
                )
            }
        }
        sb.appendLine()

        if (report.drainEpisodes.isNotEmpty()) {
            sb.appendLine("## 3. Significant Drain Episodes (Traceable to Source Readings)")
            report.drainEpisodes.forEachIndexed { idx, ep ->
                val startIso = RootCauseAnalyzer.formatIsoUtc(ep.startMs)
                val endIso = RootCauseAnalyzer.formatIsoUtc(ep.endMs)
                val chargeLabel = if (ep.counterMeasured) "counter-measured" else "estimated"
                sb.appendLine(
                    "### Episode ${idx + 1} [${ep.severity.name}]: $startIso → $endIso (${RootCauseAnalyzer.formatDuration(ep.durationMs)})"
                )
                sb.appendLine(
                    "- **Charge Lost**: **${String.format(Locale.US, "%.1f mAh", ep.chargeLostMah)}** ($chargeLabel; " +
                        "${ep.startLevelPct}% → ${ep.endLevelPct}%, counter ${ep.startCounterMicroAh} → ${ep.endCounterMicroAh} µAh, " +
                        "${String.format(Locale.US, "%.1f mAh/hr", ep.drainRateMahPerHour)})"
                )
                if (ep.cpuAwakeMs != null && ep.deepSleepMs != null && ep.awakePct != null) {
                    sb.appendLine(
                        "- **Awake vs. Deep Sleep**: ${RootCauseAnalyzer.formatDuration(ep.cpuAwakeMs)} CPU awake " +
                            "(${String.format(Locale.US, "%.0f%%", ep.awakePct)}) / " +
                            "${RootCauseAnalyzer.formatDuration(ep.deepSleepMs)} deep sleep"
                    )
                } else {
                    sb.appendLine("- **Awake vs. Deep Sleep**: Unavailable (missing or reboot-crossed boot clocks)")
                }
                sb.appendLine("- **Observed Activity**:")
                ep.observedActivity.forEach { obs -> sb.appendLine("  - $obs") }
                sb.appendLine("- **What Remains Unknown**:")
                ep.whatRemainsUnknown.forEach { unk -> sb.appendLine("  - $unk") }
                sb.appendLine("- **Source Readings**:")
                ep.sourceReadings.forEach { src -> sb.appendLine("  - `$src`") }
                sb.appendLine()
            }
        }

        sb.appendLine("## 4. Hardware Component Attribution (${report.hardwareAttributionMode})")
        sb.appendLine("| Component | Share | Attributed mAh | Avg Active Power | Duty | Verdict |")
        sb.appendLine("|---|---:|---:|---:|---:|---|")
        report.hardwareBreakdown.forEach { hw ->
            sb.appendLine(
                "| ${hw.component.displayName} | ${String.format(Locale.US, "%.1f%%", hw.sharePct)} | " +
                    "${String.format(Locale.US, "%.1f", hw.attributedMah)} | " +
                    "${hw.avgPowerMwWhenActive.toInt()} mW | ${hw.activeDutyPct.toInt()}% | ${hw.verdict} |"
            )
        }
        sb.appendLine()

        sb.appendLine("## 5. Diagnosed Root Causes")
        report.findings.forEachIndexed { idx, f ->
            sb.appendLine("### ${idx + 1}. [${f.severity.name}] ${f.title} (${f.headlineMetric})")
            sb.appendLine(f.explanation)
            f.evidencePoints.forEach { ev -> sb.appendLine("- $ev") }
            sb.appendLine("- *Action*: ${f.recommendation}")
            sb.appendLine()
        }

        if (report.sleepSessions.isNotEmpty()) {
            sb.appendLine("## 6. Sleep & Standby Forensic Audit (Pure Screen-Off Sessions)")
            report.sleepSessions.forEach { s ->
                val radios = if (s.activeRadiosDuringSleep.isEmpty()) "None" else s.activeRadiosDuringSleep.joinToString(", ")
                val deepSleepInfo = if (s.deepSleepPct != null && s.deepSleepMs != null && s.cpuAwakeMs != null) {
                    " | Deep Sleep: ${String.format(Locale.US, "%.0f%%", s.deepSleepPct)} " +
                        "(${RootCauseAnalyzer.formatDuration(s.deepSleepMs)} asleep / ${RootCauseAnalyzer.formatDuration(s.cpuAwakeMs)} awake)"
                } else {
                    ""
                }
                sb.appendLine(
                    "- **${RootCauseAnalyzer.formatIsoUtc(s.startMs)} → ${RootCauseAnalyzer.formatIsoUtc(s.endMs)}** " +
                        "(${RootCauseAnalyzer.formatDuration(s.durationMs)}): " +
                        "${s.startLevelPct}% → ${s.endLevelPct}% " +
                        "(${String.format(Locale.US, "%.2f%%/hr", s.drainRatePctPerHour)}, ${String.format(Locale.US, "%.1f mAh", s.mahLost)}) — " +
                        "**${s.verdict}**$deepSleepInfo | Radios/Settings: $radios"
                )
            }
            sb.appendLine()
        }

        if (report.appCulprits.isNotEmpty()) {
            sb.appendLine("## 7. Per-App Drain Correlation")
            sb.appendLine("| App | % of Total Drain | % of App Pool | Est. mAh | Active Time | Sessions | Intensity |")
            sb.appendLine("|---|---:|---:|---:|---:|---:|---:|")
            report.appCulprits.forEach { app ->
                sb.appendLine(
                    "| ${app.appLabel} | ${String.format(Locale.US, "%.1f%%", app.shareOfTotalDrainPct)} | " +
                        "${String.format(Locale.US, "%.1f%%", app.sharePct)} | " +
                        "${String.format(Locale.US, "%.1f", app.estimatedMah)} | " +
                        "${RootCauseAnalyzer.formatDuration(app.totalForegroundMs)} | " +
                        "${app.wakeupCount} | ${String.format(Locale.US, "%.1f%%/hr", app.drainRatePctPerHour)} |"
                )
            }
        }

        return sb.toString()
    }

    fun generateCsvTelemetry(samples: List<BatterySampleEntity>): String {
        val iso = isoFormat()
        val sb = StringBuilder()
        sb.appendLine(
            "timestamp_utc,level_pct,voltage_mv,temp_c,current_ma,current_signed_ma,power_mw,power_signed_mw," +
                "charge_counter_uah,elapsed_realtime_ms,uptime_ms," +
                "is_charging,plugged_type,screen_on,brightness_pct,refresh_hz,auto_brightness," +
                "network_transport,cellular_active,wifi_enabled,wifi_connected,gps_enabled," +
                "bluetooth_enabled,airplane_mode,link_down_kbps,metered_network,thermal_status," +
                "power_save,foreground_app,source"
        )
        samples.forEach { s ->
            val appSafe = (s.foregroundLabel ?: s.foregroundPackage ?: "").replace("\"", "\"\"")
            sb.appendLine(
                listOf(
                    iso.format(Date(s.timestampMs)),
                    s.levelPct,
                    s.voltageMv,
                    String.format(Locale.US, "%.1f", s.temperatureCelsius),
                    String.format(Locale.US, "%.1f", s.currentAbsMa),
                    String.format(Locale.US, "%.1f", s.currentSignedMa),
                    String.format(Locale.US, "%.1f", s.powerMw),
                    String.format(Locale.US, "%.1f", s.powerSignedMw),
                    s.chargeCounterMicroAh,
                    s.elapsedRealtimeMs,
                    s.uptimeMs,
                    s.isCharging,
                    s.pluggedType,
                    s.isScreenOn,
                    s.screenBrightnessPct,
                    String.format(Locale.US, "%.0f", s.displayRefreshRateHz),
                    s.displayAutoBrightness,
                    s.networkTransport,
                    s.cellularActive,
                    s.wifiEnabled,
                    s.wifiConnected,
                    s.gpsEnabled,
                    s.bluetoothEnabled,
                    s.airplaneModeOn,
                    s.linkDownKbps,
                    s.isMeteredNetwork,
                    s.thermalStatus,
                    s.isPowerSaveMode,
                    "\"$appSafe\"",
                    s.source
                ).joinToString(",")
            )
        }
        return sb.toString()
    }

    fun writeExportToCacheUri(
        context: Context,
        format: ExportFormat,
        report: BatteryAnalysisReport,
        samples: List<BatterySampleEntity>,
        latestSample: BatterySampleEntity?
    ): Uri {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "volttrace_${report.window.label.lowercase(Locale.US)}_$stamp.${format.extension}"
        val file = File(dir, fileName)
        val content = when (format) {
            ExportFormat.MARKDOWN_SUMMARY -> generateMarkdownSummary(report, latestSample)
            ExportFormat.CSV_TELEMETRY -> generateCsvTelemetry(samples)
        }
        file.writeText(content)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
}
