package com.example.dashcam.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.workDataOf
import com.example.dashcam.data.DashcamDatabase
import com.example.dashcam.network.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        uploadMutex.withLock { runUpload() }
    }

    private suspend fun runUpload(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val audioOnly = inputData.getBoolean(KEY_AUDIO_ONLY, false)
        val videoOnly = inputData.getBoolean(KEY_VIDEO_ONLY, false)
        if (!manual && !isAutomaticUploadEnabled(applicationContext)) {
            return Result.success(workDataOf(KEY_MESSAGE to "Automatic upload is disabled"))
        }
        if (!isWifiConnected()) return failureOrRetry(manual, "Connect to Wi-Fi before uploading")

        val database = DashcamDatabase.get(applicationContext)
        val dao = database.videoDao()
        val audioDao = database.audioDao()
        if (manual) {
            if (!audioOnly) dao.recoverManualUploads()
            if (!videoOnly) audioDao.recoverManualUploads()
        } else {
            dao.recoverInterruptedUploads(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10))
            audioDao.recoverInterruptedUploads(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10))
        }
        val serverUrl = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        val defaultPlaybackRotation = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_DEFAULT_PLAYBACK_ROTATION, 0)
        val client = ServerClient(serverUrl)
        if (!client.health()) return failureOrRetry(manual, "Server is unreachable: $serverUrl")

        var failed = false
        var uploadedVideos = 0
        var uploadedAudio = 0
        var lastError = "Upload failed"
        val candidates = if (audioOnly) emptyList() else dao.uploadCandidates()
        for (video in candidates) {
            if (!manual && !isAutomaticUploadEnabled(applicationContext)) break
            if (dao.markUploading(video.id, System.currentTimeMillis()) == 0) continue
            try {
                val serverId = client.upload(video, video.playbackRotationDegrees ?: defaultPlaybackRotation)
                dao.markUploaded(video.id, serverId, System.currentTimeMillis())
                uploadedVideos += 1
            } catch (error: Exception) {
                failed = true
                lastError = error.message?.take(500) ?: "Upload failed"
                dao.markFailed(video.id, lastError, System.currentTimeMillis())
            }
        }
        val audioCandidates = if (videoOnly) emptyList() else audioDao.uploadCandidates()
        for (audio in audioCandidates) {
            if (!manual && !isAutomaticUploadEnabled(applicationContext)) break
            if (audioDao.markUploading(audio.id, System.currentTimeMillis()) == 0) continue
            try {
                val serverId = client.uploadAudio(audio)
                audioDao.markUploaded(audio.id, serverId, System.currentTimeMillis())
                uploadedAudio += 1
            } catch (error: Exception) {
                failed = true
                lastError = error.message?.take(500) ?: "Upload failed"
                audioDao.markFailed(audio.id, lastError, System.currentTimeMillis())
            }
        }
        return when {
            failed && manual -> Result.failure(workDataOf(KEY_ERROR to lastError))
            failed -> Result.retry()
            candidates.isEmpty() && audioCandidates.isEmpty() ->
                Result.success(workDataOf(KEY_MESSAGE to when {
                    audioOnly -> "No pending audio to upload"
                    videoOnly -> "No pending videos to upload"
                    else -> "No pending recordings to upload"
                }))
            audioOnly -> Result.success(workDataOf(KEY_MESSAGE to "Uploaded $uploadedAudio audio recording(s)"))
            videoOnly -> Result.success(workDataOf(KEY_MESSAGE to "Uploaded $uploadedVideos video(s)"))
            else -> Result.success(
                workDataOf(KEY_MESSAGE to "Uploaded $uploadedVideos video(s) and $uploadedAudio audio recording(s)")
            )
        }
    }

    private fun failureOrRetry(manual: Boolean, message: String): Result =
        if (manual) Result.failure(workDataOf(KEY_ERROR to message)) else Result.retry()

    private fun isWifiConnected(): Boolean {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        const val PREFS = "dashcam_settings"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEFAULT_PLAYBACK_ROTATION = "default_playback_rotation"
        const val KEY_AUTO_UPLOAD_ENABLED = "auto_upload_enabled"
        const val DEFAULT_SERVER_URL = "http://192.168.1.50:5000"
        private const val UNIQUE_NOW = "dashcam-upload-now"
        private const val UNIQUE_PERIODIC = "dashcam-upload-periodic"
        const val KEY_MESSAGE = "upload_message"
        const val KEY_ERROR = "upload_error"
        private const val KEY_MANUAL = "manual_upload"
        private const val KEY_AUDIO_ONLY = "audio_only"
        private const val KEY_VIDEO_ONLY = "video_only"
        private val uploadMutex = Mutex()

        private val wifiConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED).build()

        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(wifiConstraint)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request)
        }

        fun enqueueManual(context: Context): java.util.UUID {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .setConstraints(wifiConstraint)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.REPLACE, request)
            return request.id
        }

        fun enqueueManualAudio(context: Context): java.util.UUID {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true, KEY_AUDIO_ONLY to true))
                .setConstraints(wifiConstraint)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.REPLACE, request)
            return request.id
        }

        fun enqueueManualVideo(context: Context): java.util.UUID {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true, KEY_VIDEO_ONLY to true))
                .setConstraints(wifiConstraint)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.REPLACE, request)
            return request.id
        }

        fun isAutomaticUploadEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_UPLOAD_ENABLED, true)

        fun setAutomaticUploadEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_UPLOAD_ENABLED, enabled).apply()
            if (enabled) {
                schedulePeriodic(context)
                enqueueNow(context)
            }
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(wifiConstraint)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
