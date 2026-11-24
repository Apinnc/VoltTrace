package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted snapshot of an on-device PowerMonitor energy rail or consumer
 * (Android 15+ SystemHealthManager), storing cumulative energy since boot in µWs.
 */
@Entity(
    tableName = "energy_meter_snapshots",
    indices = [Index(value = ["timestampMs"]), Index(value = ["meterName"])]
)
data class EnergyMeterSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val elapsedRealtimeMs: Long,
    val meterName: String,
    val meterType: String, // "MEASUREMENT" or "CONSUMER"
    val energyMicroWattSec: Long,
    val readingTimestampMs: Long
) {
    val energyMWh: Float
        get() = energyMicroWattSec / 3_600_000f
}
