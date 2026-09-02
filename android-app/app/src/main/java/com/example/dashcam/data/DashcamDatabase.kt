package com.example.dashcam.data

import android.content.Context
import android.os.Build
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.dashcam.battery.BatteryElectricalUnits

class Converters {
    @TypeConverter fun fromStatus(value: UploadStatus) = value.name
    @TypeConverter fun toStatus(value: String) = UploadStatus.valueOf(value)
}

@Database(
    entities = [VideoEntity::class, AudioEntity::class, BatteryTemperatureSample::class],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class DashcamDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun audioDao(): AudioDao
    abstract fun batteryTemperatureDao(): BatteryTemperatureDao

    companion object {
        @Volatile private var instance: DashcamDatabase? = null
        fun get(context: Context): DashcamDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, DashcamDatabase::class.java, "dashcam.db"
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6
            ).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE videos ADD COLUMN playbackRotationDegrees INTEGER DEFAULT NULL")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS audio_recordings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        filename TEXT NOT NULL,
                        localPath TEXT NOT NULL,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER NOT NULL,
                        durationSeconds INTEGER NOT NULL,
                        fileSizeBytes INTEGER NOT NULL,
                        uploadStatus TEXT NOT NULL,
                        retryCount INTEGER NOT NULL,
                        lastUploadAttemptAt INTEGER,
                        uploadedAt INTEGER,
                        serverAudioId INTEGER,
                        errorMessage TEXT,
                        locked INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS battery_temperature_samples (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        recordedAt INTEGER NOT NULL,
                        temperatureTenthsC INTEGER NOT NULL,
                        batteryLevel INTEGER NOT NULL,
                        isCharging INTEGER NOT NULL,
                        videoRecordingActive INTEGER NOT NULL,
                        audioRecordingActive INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_battery_temperature_samples_recordedAt " +
                        "ON battery_temperature_samples (recordedAt)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE battery_temperature_samples ADD COLUMN voltageMillivolts INTEGER")
                db.execSQL("ALTER TABLE battery_temperature_samples ADD COLUMN currentNowMicroamps INTEGER")
                db.execSQL("ALTER TABLE battery_temperature_samples ADD COLUMN estimatedBatteryPowerMilliwatts INTEGER")
                db.execSQL("ALTER TABLE battery_temperature_samples ADD COLUMN chargingSource TEXT NOT NULL DEFAULT 'Unknown'")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!BatteryElectricalUnits.usesMilliampCurrentReporting(Build.MANUFACTURER, Build.MODEL)) return
                db.execSQL(
                    """
                    UPDATE battery_temperature_samples
                    SET estimatedBatteryPowerMilliwatts = CASE
                            WHEN voltageMillivolts IS NULL THEN NULL
                            ELSE currentNowMicroamps * voltageMillivolts / 1000
                        END,
                        currentNowMicroamps = currentNowMicroamps * 1000
                    WHERE currentNowMicroamps IS NOT NULL
                      AND ABS(currentNowMicroamps) <= 20000
                    """.trimIndent()
                )
            }
        }
    }
}
