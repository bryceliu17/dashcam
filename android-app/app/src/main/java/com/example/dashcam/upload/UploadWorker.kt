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
import com.example.dashcam.data.DashcamDatabase
import com.example.dashcam.network.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!isWifiConnected()) return@withContext Result.retry()

        val dao = DashcamDatabase.get(applicationContext).videoDao()
        dao.recoverInterruptedUploads(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10))
        val serverUrl = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        val client = ServerClient(serverUrl)
        if (!client.health()) return@withContext Result.retry()

        var failed = false
        for (video in dao.uploadCandidates()) {
            if (dao.markUploading(video.id, System.currentTimeMillis()) == 0) continue
            try {
                val serverId = client.upload(video)
                dao.markUploaded(video.id, serverId, System.currentTimeMillis())
            } catch (error: Exception) {
                failed = true
                dao.markFailed(video.id, error.message?.take(500) ?: "Upload failed", System.currentTimeMillis())
            }
        }
        if (failed) Result.retry() else Result.success()
    }

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

        private val wifiConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED).build()

        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(wifiConstraint)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request)
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
