package com.example.dashcam.recording

import android.content.Context
import com.example.dashcam.data.DashcamDatabase
import java.io.File

object StoragePolicy {
    const val MAX_VIDEO_BYTES = 11L * 1024 * 1024 * 1024 / 2
    private const val MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024

    suspend fun prepareForRecording(context: Context, videoDirectory: File): Boolean {
        return prepareForRecordingWithResult(context, videoDirectory).canRecord
    }

    suspend fun prepareForRecordingWithResult(context: Context, videoDirectory: File): StoragePreparation {
        val dao = DashcamDatabase.get(context).videoDao()
        var totalVideoBytes = dao.totalSize()
        var deletedCount = 0
        if (totalVideoBytes >= MAX_VIDEO_BYTES || videoDirectory.usableSpace < MIN_FREE_BYTES) {
            for (candidate in dao.cleanupCandidatesForLocalStorage()) {
                if (totalVideoBytes < MAX_VIDEO_BYTES && videoDirectory.usableSpace >= MIN_FREE_BYTES) break

                val file = File(candidate.localPath)
                if (!file.exists() || file.delete()) {
                    dao.delete(candidate)
                    totalVideoBytes = (totalVideoBytes - candidate.fileSizeBytes).coerceAtLeast(0)
                    deletedCount += 1
                }
            }
        }

        return StoragePreparation(
            canRecord = totalVideoBytes < MAX_VIDEO_BYTES && videoDirectory.usableSpace >= MIN_FREE_BYTES,
            deletedCount = deletedCount
        )
    }
}

data class StoragePreparation(val canRecord: Boolean, val deletedCount: Int)
