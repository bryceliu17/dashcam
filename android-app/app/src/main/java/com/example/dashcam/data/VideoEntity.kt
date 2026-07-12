package com.example.dashcam.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class UploadStatus { Pending, Uploading, Uploaded, Failed }

@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filename: String,
    val localPath: String,
    val startTime: Long,
    val endTime: Long,
    val durationSeconds: Int,
    val fileSizeBytes: Long,
    val uploadStatus: UploadStatus = UploadStatus.Pending,
    val uploadedAt: Long? = null,
    val locked: Boolean = false,
    val serverVideoId: Long? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val lastUploadAttemptAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val playbackRotationDegrees: Int? = null,
)
