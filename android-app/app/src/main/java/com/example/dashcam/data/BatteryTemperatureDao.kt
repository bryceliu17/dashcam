package com.example.dashcam.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BatteryTemperatureDao {
    @Insert suspend fun insert(sample: BatteryTemperatureSample)

    @Query("SELECT * FROM battery_temperature_samples WHERE recordedAt >= :since ORDER BY recordedAt ASC")
    suspend fun samplesSince(since: Long): List<BatteryTemperatureSample>

    @Query("DELETE FROM battery_temperature_samples WHERE recordedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
