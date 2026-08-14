package com.example.dashcam.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "battery_temperature_samples",
    indices = [Index(value = ["recordedAt"])]
)
data class BatteryTemperatureSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordedAt: Long,
    val temperatureTenthsC: Int,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val videoRecordingActive: Boolean,
    val audioRecordingActive: Boolean
)
