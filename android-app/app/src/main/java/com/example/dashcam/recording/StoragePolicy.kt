package com.example.dashcam.recording

import android.content.Context
import com.example.dashcam.data.DashcamDatabase
import java.io.File

object StoragePolicy {
    const val MAX_VIDEO_BYTES = 25L * 1024 * 1024 * 1024
    private const val MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024

    suspend fun prepareForRecording(context: Context, videoDirectory: File): Boolean {
        return prepareForRecordingWithResult(context, videoDirectory).canRecord
    }

    suspend fun prepareForRecordingWithResult(context: Context, videoDirectory: File): StoragePreparation {
        val dao = DashcamDatabase.get(context).videoDao()
        val totalVideoBytes = dao.totalSize()
        var deletedCount = 0
        val videoLimitReached = totalVideoBytes >= MAX_VIDEO_BYTES
        val cleanupRequired = videoLimitReached || videoDirectory.usableSpace < MIN_FREE_BYTES
        if (cleanupRequired) {
            dao.cleanupCandidatesForLocalStorage().firstOrNull()?.let { candidate ->
                val file = File(candidate.localPath)
                if (!file.exists() || file.delete()) {
                    dao.delete(candidate)
                    deletedCount = 1
                }
            }
        }

        return StoragePreparation(
            canRecord = !cleanupRequired || deletedCount == 1,
            deletedCount = deletedCount
        )
    }
}

data class StoragePreparation(val canRecord: Boolean, val deletedCount: Int)
