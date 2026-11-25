package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.WatchInvestigationConfig
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.voltTraceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "volttrace_preferences"
)

data class UserMonitorSettings(
    val backgroundMonitoringEnabled: Boolean = true,
    val highPrecisionMonitorEnabled: Boolean = false,
    val criticalAlertsEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val thermalAlertCelsius: Float = 39.0f,
    val ratedCapacityMah: Int = 4500,
    val lastThermalAlertMs: Long = 0L,
    val lastStandbyAlertMs: Long = 0L,
    val lastAppUsageCursorMs: Long = 0L,
    val watchConfig: WatchInvestigationConfig = WatchInvestigationConfig()
)

class MonitorPreferences(private val context: Context) {

    private object Keys {
        val BACKGROUND_MONITORING = booleanPreferencesKey("background_monitoring_enabled")
        val HIGH_PRECISION_MONITOR = booleanPreferencesKey("high_precision_monitor_enabled")
        val CRITICAL_ALERTS = booleanPreferencesKey("critical_alerts_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THERMAL_ALERT_C = floatPreferencesKey("thermal_alert_celsius")
        val RATED_CAPACITY_MAH = intPreferencesKey("rated_capacity_mah")
        val LAST_THERMAL_ALERT_MS = longPreferencesKey("last_thermal_alert_ms")
        val LAST_STANDBY_ALERT_MS = longPreferencesKey("last_standby_alert_ms")
        val LAST_APP_USAGE_CURSOR_MS = longPreferencesKey("last_app_usage_cursor_ms")

        // "Watch for this again" persistent investigation rule
        val WATCH_ACTIVE = booleanPreferencesKey("watch_investigation_active")
        val WATCH_TITLE = stringPreferencesKey("watch_investigation_title")
        val WATCH_SUSPECT_LABEL = stringPreferencesKey("watch_investigation_suspect_label")
        val WATCH_SUSPECT_PACKAGE = stringPreferencesKey("watch_investigation_suspect_package")
        val WATCH_TRIGGER_RATE = floatPreferencesKey("watch_investigation_trigger_rate")
        val WATCH_TRIGGER_PEAK_MW = floatPreferencesKey("watch_investigation_trigger_peak_mw")
        val WATCH_ARMED_AT_MS = longPreferencesKey("watch_investigation_armed_at_ms")
        val WATCH_MATCH_COUNT = intPreferencesKey("watch_investigation_match_count")
        val WATCH_LAST_MATCHED_MS = longPreferencesKey("watch_investigation_last_matched_ms")
        val WATCH_LAST_MATCHED_EVENT_ID = stringPreferencesKey("watch_investigation_last_matched_event_id")
        val WATCH_LAST_MATCH_SUMMARY = stringPreferencesKey("watch_investigation_last_match_summary")
    }

    val settingsFlow: Flow<UserMonitorSettings> = context.voltTraceDataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name
        val theme = runCatching { ThemeMode.valueOf(themeStr) }.getOrDefault(ThemeMode.DARK)
        val watch = WatchInvestigationConfig(
            active = prefs[Keys.WATCH_ACTIVE] ?: false,
            title = prefs[Keys.WATCH_TITLE] ?: "",
            suspectLabel = prefs[Keys.WATCH_SUSPECT_LABEL] ?: "",
            suspectPackage = prefs[Keys.WATCH_SUSPECT_PACKAGE]?.takeIf { it.isNotBlank() },
            triggerDrainPctPerHour = prefs[Keys.WATCH_TRIGGER_RATE] ?: 1.8f,
            triggerPeakMw = prefs[Keys.WATCH_TRIGGER_PEAK_MW] ?: 2_200f,
            armedAtMs = prefs[Keys.WATCH_ARMED_AT_MS] ?: 0L,
            matchCount = prefs[Keys.WATCH_MATCH_COUNT] ?: 0,
            lastMatchedAtMs = prefs[Keys.WATCH_LAST_MATCHED_MS] ?: 0L,
            lastMatchedEventId = prefs[Keys.WATCH_LAST_MATCHED_EVENT_ID] ?: "",
            lastMatchSummary = prefs[Keys.WATCH_LAST_MATCH_SUMMARY] ?: ""
        )
        UserMonitorSettings(
            backgroundMonitoringEnabled = prefs[Keys.BACKGROUND_MONITORING] ?: true,
            highPrecisionMonitorEnabled = prefs[Keys.HIGH_PRECISION_MONITOR] ?: false,
            criticalAlertsEnabled = prefs[Keys.CRITICAL_ALERTS] ?: true,
            themeMode = theme,
            thermalAlertCelsius = prefs[Keys.THERMAL_ALERT_C] ?: 39.0f,
            ratedCapacityMah = prefs[Keys.RATED_CAPACITY_MAH] ?: 4500,
            lastThermalAlertMs = prefs[Keys.LAST_THERMAL_ALERT_MS] ?: 0L,
            lastStandbyAlertMs = prefs[Keys.LAST_STANDBY_ALERT_MS] ?: 0L,
            lastAppUsageCursorMs = prefs[Keys.LAST_APP_USAGE_CURSOR_MS] ?: 0L,
            watchConfig = watch
        )
    }

    suspend fun setBackgroundMonitoring(enabled: Boolean) {
        context.voltTraceDataStore.edit { it[Keys.BACKGROUND_MONITORING] = enabled }
    }

    suspend fun setHighPrecisionMonitor(enabled: Boolean) {
        context.voltTraceDataStore.edit { it[Keys.HIGH_PRECISION_MONITOR] = enabled }
    }

    suspend fun setCriticalAlertsEnabled(enabled: Boolean) {
        context.voltTraceDataStore.edit { it[Keys.CRITICAL_ALERTS] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.voltTraceDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setThermalAlertCelsius(tempC: Float) {
        context.voltTraceDataStore.edit { it[Keys.THERMAL_ALERT_C] = tempC.coerceIn(34f, 48f) }
    }

    suspend fun setRatedCapacityMah(capacityMah: Int) {
        context.voltTraceDataStore.edit { it[Keys.RATED_CAPACITY_MAH] = capacityMah.coerceIn(1500, 10000) }
    }

    suspend fun recordThermalAlertTimestamp(timestampMs: Long) {
        context.voltTraceDataStore.edit { it[Keys.LAST_THERMAL_ALERT_MS] = timestampMs }
    }

    suspend fun recordStandbyAlertTimestamp(timestampMs: Long) {
        context.voltTraceDataStore.edit { it[Keys.LAST_STANDBY_ALERT_MS] = timestampMs }
    }

    suspend fun setLastAppUsageCursorMs(timestampMs: Long) {
        context.voltTraceDataStore.edit { it[Keys.LAST_APP_USAGE_CURSOR_MS] = timestampMs }
    }

    suspend fun armWatchInvestigation(config: WatchInvestigationConfig) {
        context.voltTraceDataStore.edit {
            it[Keys.WATCH_ACTIVE] = true
            it[Keys.WATCH_TITLE] = config.title
            it[Keys.WATCH_SUSPECT_LABEL] = config.suspectLabel
            it[Keys.WATCH_SUSPECT_PACKAGE] = config.suspectPackage.orEmpty()
            it[Keys.WATCH_TRIGGER_RATE] = config.triggerDrainPctPerHour
            it[Keys.WATCH_TRIGGER_PEAK_MW] = config.triggerPeakMw
            it[Keys.WATCH_ARMED_AT_MS] = config.armedAtMs
            it[Keys.WATCH_MATCH_COUNT] = config.matchCount
            it[Keys.WATCH_LAST_MATCHED_MS] = config.lastMatchedAtMs
            it[Keys.WATCH_LAST_MATCHED_EVENT_ID] = config.lastMatchedEventId
            it[Keys.WATCH_LAST_MATCH_SUMMARY] = config.lastMatchSummary
        }
    }

    suspend fun clearWatchInvestigation() {
        context.voltTraceDataStore.edit {
            it[Keys.WATCH_ACTIVE] = false
            it[Keys.WATCH_TITLE] = ""
            it[Keys.WATCH_SUSPECT_LABEL] = ""
            it[Keys.WATCH_SUSPECT_PACKAGE] = ""
            it[Keys.WATCH_MATCH_COUNT] = 0
            it[Keys.WATCH_LAST_MATCHED_MS] = 0L
            it[Keys.WATCH_LAST_MATCHED_EVENT_ID] = ""
            it[Keys.WATCH_LAST_MATCH_SUMMARY] = ""
        }
    }

    suspend fun recordWatchMatch(matchedEventId: String, matchedAtMs: Long, summary: String) {
        context.voltTraceDataStore.edit {
            val prevCount = it[Keys.WATCH_MATCH_COUNT] ?: 0
            val prevTs = it[Keys.WATCH_LAST_MATCHED_MS] ?: 0L
            val prevId = it[Keys.WATCH_LAST_MATCHED_EVENT_ID] ?: ""
            val isNewEvent = matchedEventId.isNotBlank() && matchedEventId != prevId && matchedAtMs > prevTs
            if (isNewEvent) {
                it[Keys.WATCH_MATCH_COUNT] = prevCount + 1
                it[Keys.WATCH_LAST_MATCHED_MS] = matchedAtMs
                it[Keys.WATCH_LAST_MATCHED_EVENT_ID] = matchedEventId
                it[Keys.WATCH_LAST_MATCH_SUMMARY] = summary
            }
        }
    }
}
