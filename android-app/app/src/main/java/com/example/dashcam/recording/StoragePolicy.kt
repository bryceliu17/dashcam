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

        // Low device space may trigger only one deletion for each new segment. After that
        // deletion, keep checking only the configured video archive limit so a phone that
        // remains below 1 GiB does not erase several recordings at once.
        val initialCleanupRequired =
            totalVideoBytes >= MAX_VIDEO_BYTES || videoDirectory.usableSpace < MIN_FREE_BYTES
        if (!initialCleanupRequired) return StoragePreparation(canRecord = true, deletedCount = 0)

        if (!deleteOldestUnlockedVideo(dao)) {
            return StoragePreparation(canRecord = false, deletedCount = 0)
        }
        deletedCount += 1
        totalVideoBytes = dao.totalSize()

        // The archive limit is strict: continue deleting and rechecking until the next
        // segment can start below the limit. Free device space is intentionally not
        // rechecked here; it will be checked again before the following segment.
        while (totalVideoBytes >= MAX_VIDEO_BYTES) {
            if (!deleteOldestUnlockedVideo(dao)) {
                return StoragePreparation(canRecord = false, deletedCount = deletedCount)
            }
            deletedCount += 1
            totalVideoBytes = dao.totalSize()
        }

        return StoragePreparation(
            canRecord = true,
            deletedCount = deletedCount
        )
    }

    private suspend fun deleteOldestUnlockedVideo(
        dao: com.example.dashcam.data.VideoDao
    ): Boolean {
        val candidate = dao.cleanupCandidatesForLocalStorage().firstOrNull() ?: return false
        val file = File(candidate.localPath)
        if (file.exists() && !file.delete()) return false
        dao.delete(candidate)
        return true
    }
}

data class StoragePreparation(val canRecord: Boolean, val deletedCount: Int)
