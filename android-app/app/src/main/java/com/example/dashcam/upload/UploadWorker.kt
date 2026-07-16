package com.example.dashcam.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
        uploadMutex.withLock {
            val outcome = performUpload(applicationContext, manual = false, UploadTarget.All)
            if (outcome.success) {
                Result.success(workDataOf(KEY_MESSAGE to outcome.message))
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        const val PREFS = "dashcam_settings"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEFAULT_PLAYBACK_ROTATION = "default_playback_rotation"
        const val KEY_AUTO_UPLOAD_ENABLED = "auto_upload_enabled"
        const val DEFAULT_SERVER_URL = "http://192.168.1.50:5000"
        const val KEY_MESSAGE = "upload_message"
        const val KEY_ERROR = "upload_error"
        private const val UNIQUE_NOW = "dashcam-upload-now"
        private const val UNIQUE_PERIODIC = "dashcam-upload-periodic"
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

        suspend fun uploadManually(context: Context): String =
            uploadManually(context, UploadTarget.All)

        suspend fun uploadAudioManually(context: Context): String =
            uploadManually(context, UploadTarget.Audio)

        suspend fun uploadVideoManually(context: Context): String =
            uploadManually(context, UploadTarget.Video)

        private suspend fun uploadManually(context: Context, target: UploadTarget): String =
            withContext(Dispatchers.IO) {
                uploadMutex.withLock {
                    val outcome = performUpload(context.applicationContext, manual = true, target)
                    if (!outcome.success) throw IllegalStateException(outcome.message)
                    outcome.message
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

        private suspend fun performUpload(
            context: Context,
            manual: Boolean,
            target: UploadTarget
        ): UploadOutcome {
            if (!manual && !isAutomaticUploadEnabled(context)) {
                return UploadOutcome(true, "Automatic upload is disabled")
            }
            if (!isWifiConnectedStatic(context)) {
                return UploadOutcome(false, "Connect to Wi-Fi before uploading")
            }

            val database = DashcamDatabase.get(context)
            val videoDao = database.videoDao()
            val audioDao = database.audioDao()
            if (manual) {
                if (target != UploadTarget.Audio) videoDao.recoverManualUploads()
                if (target != UploadTarget.Video) audioDao.recoverManualUploads()
            } else {
                val before = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10)
                videoDao.recoverInterruptedUploads(before)
                audioDao.recoverInterruptedUploads(before)
            }

            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val serverUrl = preferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
            val defaultPlaybackRotation = preferences.getInt(KEY_DEFAULT_PLAYBACK_ROTATION, 0)
            val client = ServerClient(serverUrl)
            if (!client.health()) return UploadOutcome(false, "Server is unreachable: $serverUrl")

            var uploadedVideos = 0
            var uploadedAudio = 0
            var lastError: String? = null
            val videoCandidates = if (target == UploadTarget.Audio) emptyList() else videoDao.uploadCandidates()
            for (video in videoCandidates) {
                if (!manual && !isAutomaticUploadEnabled(context)) break
                if (videoDao.markUploading(video.id, System.currentTimeMillis()) == 0) continue
                try {
                    val serverId = client.upload(video, video.playbackRotationDegrees ?: defaultPlaybackRotation)
                    videoDao.markUploaded(video.id, serverId, System.currentTimeMillis())
                    uploadedVideos += 1
                } catch (error: Exception) {
                    lastError = error.message?.take(500) ?: "Upload failed"
                    videoDao.markFailed(video.id, lastError, System.currentTimeMillis())
                }
            }

            val audioCandidates = if (target == UploadTarget.Video) emptyList() else audioDao.uploadCandidates()
            for (audio in audioCandidates) {
                if (!manual && !isAutomaticUploadEnabled(context)) break
                if (audioDao.markUploading(audio.id, System.currentTimeMillis()) == 0) continue
                try {
                    val serverId = client.uploadAudio(audio)
                    audioDao.markUploaded(audio.id, serverId, System.currentTimeMillis())
                    uploadedAudio += 1
                } catch (error: Exception) {
                    lastError = error.message?.take(500) ?: "Upload failed"
                    audioDao.markFailed(audio.id, lastError, System.currentTimeMillis())
                }
            }

            if (lastError != null) return UploadOutcome(false, lastError)
            if (videoCandidates.isEmpty() && audioCandidates.isEmpty()) {
                return UploadOutcome(true, when (target) {
                    UploadTarget.Audio -> "No pending audio to upload"
                    UploadTarget.Video -> "No pending videos to upload"
                    UploadTarget.All -> "No pending recordings to upload"
                })
            }
            return UploadOutcome(true, when (target) {
                UploadTarget.Audio -> "Uploaded $uploadedAudio audio recording(s)"
                UploadTarget.Video -> "Uploaded $uploadedVideos video(s)"
                UploadTarget.All -> "Uploaded $uploadedVideos video(s) and $uploadedAudio audio recording(s)"
            })
        }

        private fun isWifiConnectedStatic(context: Context): Boolean {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val networkInfo = manager.activeNetworkInfo ?: return false
                @Suppress("DEPRECATION")
                return networkInfo.isConnected && networkInfo.type == ConnectivityManager.TYPE_WIFI
            }
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        private enum class UploadTarget { All, Audio, Video }
        private data class UploadOutcome(val success: Boolean, val message: String)
    }
}
