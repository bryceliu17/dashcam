package com.example.dashcam.recording

import android.content.Context
import com.example.dashcam.data.DashcamDatabase
import java.io.File

object AudioStoragePolicy {
    const val MAX_AUDIO_BYTES = 3L * 1024 * 1024 * 1024 / 2

    suspend fun enforceLimit(context: Context, audioDirectory: File): Int {
        val dao = DashcamDatabase.get(context).audioDao()
        val lockedPaths = dao.lockedPaths().toHashSet()
        val allRecordings = audioDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
        val recordings = allRecordings
            .filter { it.absolutePath !in lockedPaths }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        var totalBytes = allRecordings.sumOf { it.length() }
        var deletedCount = 0

        for (recording in recordings) {
            if (totalBytes <= MAX_AUDIO_BYTES) break
            val fileSize = recording.length()
            if (recording.delete()) {
                dao.deleteByLocalPath(recording.absolutePath)
                totalBytes = (totalBytes - fileSize).coerceAtLeast(0)
                deletedCount += 1
            }
        }
        return deletedCount
    }
}
