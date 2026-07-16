package com.example.dashcam.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {
    @Insert suspend fun insert(video: VideoEntity): Long

    @Query("SELECT * FROM videos ORDER BY startTime DESC")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE uploadStatus IN ('Pending', 'Failed') ORDER BY createdAt ASC")
    suspend fun uploadCandidates(): List<VideoEntity>

    @Query("UPDATE videos SET uploadStatus = 'Uploading', lastUploadAttemptAt = :now, errorMessage = NULL WHERE id = :id AND uploadStatus IN ('Pending', 'Failed')")
    suspend fun markUploading(id: Long, now: Long): Int

    @Query("UPDATE videos SET uploadStatus = 'Uploaded', uploadedAt = :now, serverVideoId = :serverId, errorMessage = NULL WHERE id = :id")
    suspend fun markUploaded(id: Long, serverId: Long, now: Long)

    @Query("UPDATE videos SET uploadStatus = 'Failed', errorMessage = :message, retryCount = retryCount + 1, lastUploadAttemptAt = :now WHERE id = :id")
    suspend fun markFailed(id: Long, message: String, now: Long)

    @Query("UPDATE videos SET uploadStatus = 'Failed', errorMessage = 'Upload interrupted; queued for retry' WHERE uploadStatus = 'Uploading' AND lastUploadAttemptAt < :before")
    suspend fun recoverInterruptedUploads(before: Long)

    @Query("UPDATE videos SET uploadStatus = 'Failed', errorMessage = 'Previous manual upload was interrupted; retrying' WHERE uploadStatus = 'Uploading'")
    suspend fun recoverManualUploads()

    @Query("UPDATE videos SET locked = NOT locked WHERE id = :id")
    suspend fun toggleLock(id: Long)

    @Query("UPDATE videos SET playbackRotationDegrees = :degrees WHERE id = :id")
    suspend fun setPlaybackRotation(id: Long, degrees: Int)

    @Query("UPDATE videos SET playbackRotationDegrees = :degrees")
    suspend fun setAllPlaybackRotations(degrees: Int)

    @Query("SELECT COALESCE(SUM(fileSizeBytes), 0) FROM videos")
    suspend fun totalSize(): Long

    @Query("SELECT COUNT(*) FROM videos")
    suspend fun videoCount(): Int

    @Query("SELECT * FROM videos ORDER BY startTime ASC LIMIT :limit")
    suspend fun oldestVideos(limit: Int): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE uploadStatus = 'Uploaded' AND locked = 0 AND uploadedAt < :before ORDER BY uploadedAt ASC")
    suspend fun cleanupCandidates(before: Long): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE locked = 0 ORDER BY startTime ASC")
    suspend fun cleanupCandidatesForLocalStorage(): List<VideoEntity>

    @Delete suspend fun delete(video: VideoEntity)
}
