package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BatterySampleEntity::class,
        AppDrainEventEntity::class,
        EnergyMeterSnapshotEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class VoltTraceDatabase : RoomDatabase() {
    abstract fun batteryDao(): BatteryDao

    companion object {
        @Volatile
        private var INSTANCE: VoltTraceDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE battery_samples ADD COLUMN linkDownKbps INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE battery_samples ADD COLUMN isMeteredNetwork INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE battery_samples ADD COLUMN elapsedRealtimeMs INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE battery_samples ADD COLUMN uptimeMs INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `energy_meter_snapshots` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `elapsedRealtimeMs` INTEGER NOT NULL,
                        `meterName` TEXT NOT NULL,
                        `meterType` TEXT NOT NULL,
                        `energyMicroWattSec` INTEGER NOT NULL,
                        `readingTimestampMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_energy_meter_snapshots_timestampMs` ON `energy_meter_snapshots` (`timestampMs`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_energy_meter_snapshots_meterName` ON `energy_meter_snapshots` (`meterName`)"
                )
            }
        }

        fun getInstance(context: Context): VoltTraceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VoltTraceDatabase::class.java,
                    "volttrace_telemetry.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
