package com.example.dashcam.recording

import android.content.Context
import com.example.dashcam.data.DashcamDatabase
import java.io.File

object StoragePolicy {
    private const val MAX_VIDEO_COUNT = 15
    private const val MIN_FREE_BYTES = 500L * 1024 * 1024

    suspend fun prepareForRecording(context: Context, videoDirectory: File): Boolean {
        return prepareForRecordingWithResult(context, videoDirectory).canRecord
    }

    suspend fun prepareForRecordingWithResult(context: Context, videoDirectory: File): StoragePreparation {
        val dao = DashcamDatabase.get(context).videoDao()
        var videoCount = dao.videoCount()
        var deletedCount = 0
        if (videoCount >= MAX_VIDEO_COUNT || videoDirectory.usableSpace < MIN_FREE_BYTES) {
            for (candidate in dao.cleanupCandidatesForLocalStorage()) {
                if (videoCount < MAX_VIDEO_COUNT && videoDirectory.usableSpace >= MIN_FREE_BYTES) break

                val file = File(candidate.localPath)
                if (!file.exists() || file.delete()) {
                    dao.delete(candidate)
                    videoCount = (videoCount - 1).coerceAtLeast(0)
                    deletedCount += 1
                }
            }
        }

        return StoragePreparation(
            canRecord = videoCount < MAX_VIDEO_COUNT && videoDirectory.usableSpace >= MIN_FREE_BYTES,
            deletedCount = deletedCount
        )
    }
}

data class StoragePreparation(val canRecord: Boolean, val deletedCount: Int)
