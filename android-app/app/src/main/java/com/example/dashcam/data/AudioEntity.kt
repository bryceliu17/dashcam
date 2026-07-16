package com.example.dashcam.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_recordings")
data class AudioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filename: String,
    val localPath: String,
    val startTime: Long,
    val endTime: Long,
    val durationSeconds: Int,
    val fileSizeBytes: Long,
    val uploadStatus: UploadStatus = UploadStatus.Pending,
    val retryCount: Int = 0,
    val lastUploadAttemptAt: Long? = null,
    val uploadedAt: Long? = null,
    val serverAudioId: Long? = null,
    val errorMessage: String? = null,
    val locked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
