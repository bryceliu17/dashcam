package com.example.dashcam.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun fromStatus(value: UploadStatus) = value.name
    @TypeConverter fun toStatus(value: String) = UploadStatus.valueOf(value)
}

@Database(entities = [VideoEntity::class, AudioEntity::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class DashcamDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun audioDao(): AudioDao

    companion object {
        @Volatile private var instance: DashcamDatabase? = null
        fun get(context: Context): DashcamDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, DashcamDatabase::class.java, "dashcam.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
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
    }
}
