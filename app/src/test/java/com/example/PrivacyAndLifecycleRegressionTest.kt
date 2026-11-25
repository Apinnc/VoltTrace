package com.example

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.BatterySampleEntity
import com.example.data.repository.BatteryRepository
import com.example.domain.AnalysisWindow
import com.example.domain.DiagnosticReportGenerator
import com.example.domain.ExportFormat
import com.example.domain.RootCauseAnalyzer
import com.example.ui.viewmodel.VoltTraceViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric regression suite verifying privacy, backup rules, export cache cleanup,
 * lifecycle-bound live polling, and rolling time-window query cutoffs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivacyAndLifecycleRegressionTest {

    @Test
    fun manifestDisablesBackupAndXmlRulesExcludeTelemetryDatabaseAndPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val allowBackupFlag = (context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
        assertFalse("android:allowBackup must be false to protect local telemetry", allowBackupFlag)

        val backupParser = context.resources.getXml(R.xml.backup_rules)
        val backupExcludedDomains = mutableSetOf<String>()
        var event = backupParser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && backupParser.name == "exclude") {
                backupParser.getAttributeValue(null, "domain")?.let { backupExcludedDomains.add(it) }
            }
            event = backupParser.next()
        }
        assertTrue("backup_rules.xml must exclude database", backupExcludedDomains.contains("database"))
        assertTrue("backup_rules.xml must exclude file (datastore)", backupExcludedDomains.contains("file"))
        assertTrue("backup_rules.xml must exclude sharedpref", backupExcludedDomains.contains("sharedpref"))

        val extractionParser = context.resources.getXml(R.xml.data_extraction_rules)
        val extractionExcludedDomains = mutableListOf<String>()
        event = extractionParser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && extractionParser.name == "exclude") {
                extractionParser.getAttributeValue(null, "domain")?.let { extractionExcludedDomains.add(it) }
            }
            event = extractionParser.next()
        }
        assertTrue(
            "data_extraction_rules.xml must exclude database in both cloud-backup and device-transfer",
            extractionExcludedDomains.count { it == "database" } >= 2
        )
        assertTrue(
            "data_extraction_rules.xml must exclude file (datastore) in both sections",
            extractionExcludedDomains.count { it == "file" } >= 2
        )
    }

    @Test
    fun clearingHistoryRemovesCachedExportedReportFiles() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VoltTraceApp>()
        val context: Context = app
        val repo = app.repository
        val sample = BatterySampleEntity(
            timestampMs = 1_764_000_000_000L,
            levelPct = 80,
            voltageMv = 4000,
            temperatureDeciC = 290,
            currentMicroA = -120_000L,
            chargeCounterMicroAh = 3_600_000L,
            isCharging = false,
            pluggedType = "BATTERY",
            healthCode = 2,
            technology = "Li-ion",
            isScreenOn = true,
            isPowerSaveMode = false,
            thermalStatus = 0,
            networkTransport = "WIFI",
            screenBrightnessPct = 50,
            displayRefreshRateHz = 60f,
            displayAutoBrightness = true,
            gpsEnabled = false,
            wifiEnabled = true,
            wifiConnected = true,
            bluetoothEnabled = false,
            cellularActive = false,
            airplaneModeOn = false,
            foregroundPackage = null,
            foregroundLabel = null,
            source = "TEST"
        )
        val report = RootCauseAnalyzer.analyze(
            samples = listOf(sample),
            latestSample = sample,
            appEvents = emptyList(),
            liveAppUsage = emptyList(),
            window = AnalysisWindow.ONE_HOUR,
            ratedCapacityMah = 4500,
            thermalAlertCelsius = 39f
        )

        DiagnosticReportGenerator.writeExportToCacheUri(
            context = context,
            format = ExportFormat.MARKDOWN_SUMMARY,
            report = report,
            samples = listOf(sample),
            latestSample = sample
        )
        val reportsDir = File(context.cacheDir, "reports")
        assertTrue("Export should create cached report file", reportsDir.listFiles()?.isNotEmpty() == true)

        // Simulate user cancelling share sheet and later clearing telemetry history
        repo.clearAllHistory()
        assertTrue(
            "clearAllHistory must delete cached export files",
            reportsDir.listFiles().isNullOrEmpty()
        )
    }

    @Test
    fun viewModelTiesLivePollingToForegroundAndRollsOneHourCutoffAsClockAdvances() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VoltTraceApp>()
        val repo = app.repository
        var fakeNowMs = 1_764_000_000_000L // e.g., 08:00
        val vm = VoltTraceViewModel(repository = repo, clockMs = { fakeNowMs })

        // Before ON_START, live polling must NOT be running
        assertFalse("Live polling must not run until Activity enters foreground", vm.isLivePollingActive)

        vm.setForegroundActive(true)
        assertTrue("Live polling should be active while foregrounded", vm.isLivePollingActive)

        // Select 1H at 08:00 -> cutoff is 07:00
        vm.selectWindow(AnalysisWindow.ONE_HOUR)
        val cutoffAt8Am = vm.windowSinceMsFlow.first()
        assertEquals(fakeNowMs - 60L * 60_000L, cutoffAt8Am)

        // Advance time by 2 hours to 10:00 -> rolling 1H cutoff must advance to 09:00
        fakeNowMs += 2L * 60L * 60_000L
        vm.advanceWindowClock(fakeNowMs)
        val cutoffAt10Am = vm.windowSinceMsFlow.first()
        assertEquals(fakeNowMs - 60L * 60_000L, cutoffAt10Am)

        // Leaving app (ON_STOP) must stop live polling immediately
        vm.setForegroundActive(false)
        assertFalse("Live polling must stop immediately on ON_STOP", vm.isLivePollingActive)
    }
}
