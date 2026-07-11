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
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!isWifiConnected()) return@withContext failureOrRetry(manual, "Connect to Wi-Fi before uploading")

        val dao = DashcamDatabase.get(applicationContext).videoDao()
        if (manual) {
            dao.recoverManualUploads()
        } else {
            dao.recoverInterruptedUploads(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10))
        }
        val serverUrl = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        val client = ServerClient(serverUrl)
        if (!client.health()) return@withContext failureOrRetry(manual, "Server is unreachable: $serverUrl")

        var failed = false
        var uploaded = 0
        var lastError = "Upload failed"
        val candidates = dao.uploadCandidates()
        for (video in candidates) {
            if (dao.markUploading(video.id, System.currentTimeMillis()) == 0) continue
            try {
                val serverId = client.upload(video)
                dao.markUploaded(video.id, serverId, System.currentTimeMillis())
                uploaded += 1
            } catch (error: Exception) {
                failed = true
                lastError = error.message?.take(500) ?: "Upload failed"
                dao.markFailed(video.id, lastError, System.currentTimeMillis())
            }
        }
        when {
            failed && manual -> Result.failure(workDataOf(KEY_ERROR to lastError))
            failed -> Result.retry()
            candidates.isEmpty() -> Result.success(workDataOf(KEY_MESSAGE to "No pending videos to upload"))
            else -> Result.success(workDataOf(KEY_MESSAGE to "Uploaded $uploaded video(s)"))
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
        const val DEFAULT_SERVER_URL = "http://192.168.1.50:5000"
        private const val UNIQUE_NOW = "dashcam-upload-now"
        private const val UNIQUE_PERIODIC = "dashcam-upload-periodic"
        const val KEY_MESSAGE = "upload_message"
        const val KEY_ERROR = "upload_error"
        private const val KEY_MANUAL = "manual_upload"

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
