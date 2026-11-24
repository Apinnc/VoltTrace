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
    val lastStandbyAlertMs: Long = 0L
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
    }

    val settingsFlow: Flow<UserMonitorSettings> = context.voltTraceDataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name
        val theme = runCatching { ThemeMode.valueOf(themeStr) }.getOrDefault(ThemeMode.DARK)
        UserMonitorSettings(
            backgroundMonitoringEnabled = prefs[Keys.BACKGROUND_MONITORING] ?: true,
            highPrecisionMonitorEnabled = prefs[Keys.HIGH_PRECISION_MONITOR] ?: false,
            criticalAlertsEnabled = prefs[Keys.CRITICAL_ALERTS] ?: true,
            themeMode = theme,
            thermalAlertCelsius = prefs[Keys.THERMAL_ALERT_C] ?: 39.0f,
            ratedCapacityMah = prefs[Keys.RATED_CAPACITY_MAH] ?: 4500,
            lastThermalAlertMs = prefs[Keys.LAST_THERMAL_ALERT_MS] ?: 0L,
            lastStandbyAlertMs = prefs[Keys.LAST_STANDBY_ALERT_MS] ?: 0L
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
}
