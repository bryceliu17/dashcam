package com.example.dashcam.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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
        uploadMutex.withLock {
            val outcome = performUpload(applicationContext, manual = false)
            if (outcome.success) Result.success(workDataOf(KEY_MESSAGE to outcome.message)) else Result.retry()
        }
    }

    companion object {
        const val PREFS = "dashcam_settings"
        const val KEY_SERVER_URL = "server_url"
        const val DEFAULT_SERVER_URL = "http://192.168.1.50:5000"
        private const val UNIQUE_NOW = "dashcam-upload-now"
        private const val UNIQUE_PERIODIC = "dashcam-upload-periodic"
        const val KEY_MESSAGE = "upload_message"
        const val KEY_ERROR = "upload_error"
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

        suspend fun uploadManually(context: Context): String = withContext(Dispatchers.IO) {
            uploadMutex.withLock {
                val outcome = performUpload(context.applicationContext, manual = true)
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

        private suspend fun performUpload(context: Context, manual: Boolean): UploadOutcome {
            if (!isWifiConnectedStatic(context)) return UploadOutcome(false, "Connect to Wi-Fi before uploading")

            val dao = DashcamDatabase.get(context).videoDao()
            if (manual) dao.recoverManualUploads()
            else dao.recoverInterruptedUploads(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10))

            val serverUrl = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
            val client = ServerClient(serverUrl)
            if (!client.health()) return UploadOutcome(false, "Server is unreachable: $serverUrl")

            var uploaded = 0
            var lastError: String? = null
            val candidates = dao.uploadCandidates()
            for (video in candidates) {
                if (dao.markUploading(video.id, System.currentTimeMillis()) == 0) continue
                try {
                    val serverId = client.upload(video)
                    dao.markUploaded(video.id, serverId, System.currentTimeMillis())
                    uploaded += 1
                } catch (error: Exception) {
                    lastError = error.message?.take(500) ?: "Upload failed"
                    dao.markFailed(video.id, lastError, System.currentTimeMillis())
                }
            }
            return when {
                lastError != null -> UploadOutcome(false, lastError)
                candidates.isEmpty() -> UploadOutcome(true, "No pending videos to upload")
                else -> UploadOutcome(true, "Uploaded $uploaded video(s)")
            }
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

        private data class UploadOutcome(val success: Boolean, val message: String)
    }
}
