package com.example.dashcam.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioDao {
    @Insert suspend fun insert(audio: AudioEntity): Long

    @Query("SELECT * FROM audio_recordings ORDER BY startTime DESC")
    fun observeAll(): Flow<List<AudioEntity>>

    @Query("SELECT * FROM audio_recordings WHERE uploadStatus IN ('Pending', 'Failed') ORDER BY createdAt ASC")
    suspend fun uploadCandidates(): List<AudioEntity>

    @Query("UPDATE audio_recordings SET uploadStatus = 'Uploading', lastUploadAttemptAt = :now, errorMessage = NULL WHERE id = :id AND uploadStatus IN ('Pending', 'Failed')")
    suspend fun markUploading(id: Long, now: Long): Int

    @Query("UPDATE audio_recordings SET uploadStatus = 'Uploaded', uploadedAt = :now, serverAudioId = :serverId, errorMessage = NULL WHERE id = :id")
    suspend fun markUploaded(id: Long, serverId: Long, now: Long)

    @Query("UPDATE audio_recordings SET uploadStatus = 'Failed', errorMessage = :message, retryCount = retryCount + 1, lastUploadAttemptAt = :now WHERE id = :id")
    suspend fun markFailed(id: Long, message: String, now: Long)

    @Query("UPDATE audio_recordings SET uploadStatus = 'Failed', errorMessage = 'Upload interrupted; queued for retry' WHERE uploadStatus = 'Uploading' AND lastUploadAttemptAt < :before")
    suspend fun recoverInterruptedUploads(before: Long)

    @Query("UPDATE audio_recordings SET uploadStatus = 'Failed', errorMessage = 'Previous manual upload was interrupted; retrying' WHERE uploadStatus = 'Uploading'")
    suspend fun recoverManualUploads()

    @Query("UPDATE audio_recordings SET locked = NOT locked WHERE id = :id")
    suspend fun toggleLock(id: Long)

    @Query("SELECT localPath FROM audio_recordings WHERE locked = 1")
    suspend fun lockedPaths(): List<String>

    @Query("DELETE FROM audio_recordings WHERE localPath = :localPath")
    suspend fun deleteByLocalPath(localPath: String)

    @Query("SELECT * FROM audio_recordings WHERE localPath = :localPath LIMIT 1")
    suspend fun findByLocalPath(localPath: String): AudioEntity?

    @Delete suspend fun delete(audio: AudioEntity)
}
