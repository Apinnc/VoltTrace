package com.example.data.hardware

import android.content.Context
import android.os.Build
import android.os.OutcomeReceiver
import android.os.PowerMonitor
import android.os.PowerMonitorReadings
import android.os.SystemClock
import android.os.health.SystemHealthManager
import androidx.annotation.RequiresApi
import com.example.data.local.EnergyMeterSnapshotEntity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class DiscoveredPowerMeter(
    val name: String,
    val type: String, // "MEASUREMENT" (ODPM rail) or "CONSUMER" (modeled/measured consumer)
    val energyMicroWattSec: Long?,
    val readingTimestampMs: Long?
)

data class EnergyMeterInventory(
    val apiSupported: Boolean,
    val apiLevel: Int,
    val meters: List<DiscoveredPowerMeter>,
    val statusMessage: String
)

/**
 * Discovers and samples hardware energy meters exposed by Android 15+ (API 35+)
 * via [SystemHealthManager.getSupportedPowerMonitors] and
 * [SystemHealthManager.getPowerMonitorReadings] using ordinary app permissions.
 *
 * Gracefully degrades on Android 14 and below or on devices whose HAL exposes
 * zero ODPM rails / energy consumers.
 */
class PowerMonitorDiscovery(private val context: Context) {

    private val callbackExecutor = Executors.newSingleThreadExecutor()

    suspend fun discoverAndSample(): EnergyMeterInventory {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return EnergyMeterInventory(
                apiSupported = false,
                apiLevel = Build.VERSION.SDK_INT,
                meters = emptyList(),
                statusMessage = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) does not expose SystemHealthManager PowerMonitor APIs (requires Android 15 / API 35+)."
            )
        }
        return discoverOnApi35Plus()
    }

    suspend fun captureSnapshots(nowMs: Long = System.currentTimeMillis()): List<EnergyMeterSnapshotEntity> {
        val inventory = discoverAndSample()
        if (!inventory.apiSupported || inventory.meters.isEmpty()) return emptyList()
        val elapsedMs = SystemClock.elapsedRealtime()
        return inventory.meters.mapNotNull { meter ->
            val energy = meter.energyMicroWattSec ?: return@mapNotNull null
            EnergyMeterSnapshotEntity(
                timestampMs = nowMs,
                elapsedRealtimeMs = elapsedMs,
                meterName = meter.name,
                meterType = meter.type,
                energyMicroWattSec = energy,
                readingTimestampMs = meter.readingTimestampMs ?: elapsedMs
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun discoverOnApi35Plus(): EnergyMeterInventory {
        val shm = context.getSystemService(Context.SYSTEM_HEALTH_SERVICE) as? SystemHealthManager
            ?: return EnergyMeterInventory(
                apiSupported = false,
                apiLevel = Build.VERSION.SDK_INT,
                meters = emptyList(),
                statusMessage = "SystemHealthManager service unavailable on this device."
            )

        val supportedMonitors: List<PowerMonitor> = withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    shm.getSupportedPowerMonitors(callbackExecutor) { monitors ->
                        if (cont.isActive) cont.resume(monitors ?: emptyList())
                    }
                }.onFailure {
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
        } ?: emptyList()

        if (supportedMonitors.isEmpty()) {
            return EnergyMeterInventory(
                apiSupported = true,
                apiLevel = Build.VERSION.SDK_INT,
                meters = emptyList(),
                statusMessage = "Device HAL exposes 0 PowerMonitor rails/consumers via SystemHealthManager; falling back to modeled hardware attribution."
            )
        }

        val readings: PowerMonitorReadings? = withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    shm.getPowerMonitorReadings(
                        supportedMonitors,
                        callbackExecutor,
                        object : OutcomeReceiver<PowerMonitorReadings, RuntimeException> {
                            override fun onResult(result: PowerMonitorReadings) {
                                if (cont.isActive) cont.resume(result)
                            }

                            override fun onError(error: RuntimeException) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        val discovered = supportedMonitors.map { monitor ->
            val typeLabel = when (monitor.type) {
                PowerMonitor.POWER_MONITOR_TYPE_MEASUREMENT -> "MEASUREMENT"
                PowerMonitor.POWER_MONITOR_TYPE_CONSUMER -> "CONSUMER"
                else -> "UNKNOWN"
            }
            val rawEnergy = readings?.let { r ->
                runCatching { r.getConsumedEnergy(monitor) }.getOrNull()
            }
            val validEnergy = rawEnergy?.takeIf {
                it != PowerMonitorReadings.ENERGY_UNAVAILABLE.toLong() && it >= 0L
            }
            val readTs = readings?.let { r ->
                runCatching { r.getTimestampMillis(monitor) }.getOrNull()
            }
            DiscoveredPowerMeter(
                name = monitor.name,
                type = typeLabel,
                energyMicroWattSec = validEnergy,
                readingTimestampMs = readTs
            )
        }.sortedWith(compareBy<DiscoveredPowerMeter> { it.type != "MEASUREMENT" }.thenBy { it.name })

        val railCount = discovered.count { it.type == "MEASUREMENT" }
        val consumerCount = discovered.count { it.type == "CONSUMER" }
        return EnergyMeterInventory(
            apiSupported = true,
            apiLevel = Build.VERSION.SDK_INT,
            meters = discovered,
            statusMessage = "Discovered ${discovered.size} PowerMonitor meters ($railCount ODPM hardware rails, $consumerCount energy consumers)."
        )
    }
}
