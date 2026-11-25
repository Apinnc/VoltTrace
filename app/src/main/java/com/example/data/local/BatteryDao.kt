package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BatteryDao {

    @Query("SELECT * FROM battery_samples WHERE timestampMs >= :sinceMs ORDER BY timestampMs ASC")
    fun observeSamplesSince(sinceMs: Long): Flow<List<BatterySampleEntity>>

    @Query("SELECT * FROM battery_samples WHERE timestampMs >= :startMs AND timestampMs <= :endMs ORDER BY timestampMs ASC")
    suspend fun getSamplesInRange(startMs: Long, endMs: Long): List<BatterySampleEntity>

    @Query("SELECT * FROM battery_samples WHERE timestampMs <= :atOrBeforeMs ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatestSampleAtOrBefore(atOrBeforeMs: Long): BatterySampleEntity?

    @Query("SELECT * FROM battery_samples ORDER BY timestampMs DESC LIMIT 1")
    fun observeLatestSample(): Flow<BatterySampleEntity?>

    @Query("SELECT * FROM battery_samples ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatestSample(): BatterySampleEntity?

    @Query("SELECT COUNT(*) FROM battery_samples")
    fun observeSampleCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: BatterySampleEntity): Long

    @Query("DELETE FROM battery_samples WHERE timestampMs < :olderThanMs")
    suspend fun pruneSamplesOlderThan(olderThanMs: Long)

    @Query("DELETE FROM battery_samples")
    suspend fun clearAllSamples()

    @Query("SELECT * FROM app_drain_events WHERE timestampMs >= :sinceMs ORDER BY timestampMs DESC")
    fun observeAppEventsSince(sinceMs: Long): Flow<List<AppDrainEventEntity>>

    @Query("SELECT * FROM app_drain_events WHERE timestampMs >= :startMs AND timestampMs <= :endMs ORDER BY timestampMs DESC")
    suspend fun getAppEventsInRange(startMs: Long, endMs: Long): List<AppDrainEventEntity>

    @Query("DELETE FROM app_drain_events WHERE timestampMs = :timestampMs AND intervalDurationMs = :intervalDurationMs")
    suspend fun deleteAppEventsForWindow(timestampMs: Long, intervalDurationMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppEvents(events: List<AppDrainEventEntity>)

    @Query("DELETE FROM app_drain_events WHERE timestampMs < :olderThanMs")
    suspend fun pruneAppEventsOlderThan(olderThanMs: Long)

    @Query("DELETE FROM app_drain_events")
    suspend fun clearAllAppEvents()

    @Query("SELECT * FROM energy_meter_snapshots WHERE timestampMs >= :sinceMs ORDER BY timestampMs ASC")
    fun observeEnergyMetersSince(sinceMs: Long): Flow<List<EnergyMeterSnapshotEntity>>

    @Query("SELECT * FROM energy_meter_snapshots WHERE timestampMs >= :startMs AND timestampMs <= :endMs ORDER BY timestampMs ASC")
    suspend fun getEnergyMetersInRange(startMs: Long, endMs: Long): List<EnergyMeterSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEnergyMeterSnapshots(snapshots: List<EnergyMeterSnapshotEntity>)

    @Query("DELETE FROM energy_meter_snapshots WHERE timestampMs < :olderThanMs")
    suspend fun pruneEnergyMetersOlderThan(olderThanMs: Long)

    @Query("DELETE FROM energy_meter_snapshots")
    suspend fun clearAllEnergyMeters()
}
