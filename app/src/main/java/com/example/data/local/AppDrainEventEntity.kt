package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "app_drain_events",
    indices = [Index(value = ["timestampMs"]), Index(value = ["packageName"])]
)
data class AppDrainEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val intervalDurationMs: Long,
    val packageName: String,
    val appLabel: String,
    val foregroundMs: Long,
    val launchCount: Int,
    val estimatedMah: Float,
    val avgPowerMw: Float
)
