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

@Database(entities = [VideoEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class DashcamDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao

    companion object {
        @Volatile private var instance: DashcamDatabase? = null
        fun get(context: Context): DashcamDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, DashcamDatabase::class.java, "dashcam.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE videos ADD COLUMN playbackRotationDegrees INTEGER DEFAULT NULL")
            }
        }
    }
}
