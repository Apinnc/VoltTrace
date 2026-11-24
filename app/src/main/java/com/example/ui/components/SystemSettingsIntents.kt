package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.example.domain.SettingsAction

object SystemSettingsIntents {
    fun launch(context: Context, action: SettingsAction, packageName: String? = null) {
        if (action == SettingsAction.NONE) return
        val intent = when (action) {
            SettingsAction.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            SettingsAction.DISPLAY_SETTINGS -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            SettingsAction.LOCATION_SETTINGS -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            SettingsAction.WIFI_SETTINGS -> Intent(Settings.ACTION_WIFI_SETTINGS)
            SettingsAction.BLUETOOTH_SETTINGS -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            SettingsAction.NETWORK_SETTINGS -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            SettingsAction.BATTERY_SAVER -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            SettingsAction.BATTERY_OPTIMIZATION ->
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            SettingsAction.APP_DETAILS -> if (!packageName.isNullOrBlank()) {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_SETTINGS)
            }
            SettingsAction.NONE -> return
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            context.startActivity(intent)
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
