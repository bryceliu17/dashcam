package com.example.dashcam.recording

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.dashcam.MainActivity
import com.example.dashcam.R
import com.example.dashcam.data.AudioEntity
import com.example.dashcam.data.DashcamDatabase
import com.example.dashcam.upload.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecordingService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: MediaRecorder? = null
    private var temporaryFile: File? = null
    private var finalFile: File? = null
    private var segmentStartMs = 0L
    private var recordingActive = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val rotateRunnable = Runnable {
        if (recordingActive) finishSegment(restart = true)
    }
    private val statusRunnable = object : Runnable {
        override fun run() {
            if (!recordingActive) return
            broadcastState(true, elapsedSeconds(), finalFile?.name)
            mainHandler.postDelayed(this, STATUS_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_QUERY_STATE -> {
                broadcastState(recordingActive, elapsedSeconds(), finalFile?.name)
                if (!recordingActive) stopSelf(startId)
            }
            else -> startRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording() {
        if (recordingActive) return
        startForeground(NOTIFICATION_ID, buildNotification("Starting audio recording"))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishService("Microphone permission is required")
            return
        }
        if (PowerRecordingSettings.isVideoRecordingActive(this)) {
            finishService("Stop video recording before starting audio")
            return
        }

        recordingActive = true
        PowerRecordingSettings.setAudioRecordingActive(this, true)
        acquireWakeLock()
        val directory = audioDirectory()
        serviceScope.launch {
            AudioStoragePolicy.enforceLimit(this@AudioRecordingService, directory)
            withContext(Dispatchers.Main) {
                if (recordingActive) startSegment()
            }
        }
    }

    private fun startSegment() {
        if (!recordingActive || recorder != null) return
        val directory = audioDirectory()
        val startedAt = System.currentTimeMillis()
        val filename = SimpleDateFormat("'audio_'yyyyMMdd_HHmmss_SSS'.m4a'", Locale.US).format(Date(startedAt))
        val destination = File(directory, filename)
        val pending = File(directory, "$filename.recording")

        var nextRecorder: MediaRecorder? = null
        try {
            nextRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(AUDIO_BIT_RATE)
                setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                setOutputFile(pending.absolutePath)
                prepare()
                start()
            }
            temporaryFile = pending
            finalFile = destination
            segmentStartMs = startedAt
            recorder = nextRecorder
            updateNotification("Recording ${destination.name}")
            broadcastState(true, 0, destination.name)
            mainHandler.removeCallbacks(rotateRunnable)
            mainHandler.postDelayed(rotateRunnable, SEGMENT_DURATION_MS)
            mainHandler.removeCallbacks(statusRunnable)
            mainHandler.post(statusRunnable)
        } catch (error: Exception) {
            try { nextRecorder?.release() } catch (_: RuntimeException) { }
            pending.delete()
            failAndStop("Unable to start audio recording: ${error.message.orEmpty()}")
        }
    }

    private fun stopRecording() {
        if (!recordingActive) {
            finishService()
            return
        }
        recordingActive = false
        finishSegment(restart = false)
    }

    private fun finishSegment(restart: Boolean) {
        mainHandler.removeCallbacks(rotateRunnable)
        mainHandler.removeCallbacks(statusRunnable)
        val currentRecorder = recorder
        val pending = temporaryFile
        val destination = finalFile
        val startedAt = segmentStartMs
        val endedAt = System.currentTimeMillis()
        recorder = null
        temporaryFile = null
        finalFile = null

        var saved = false
        try {
            currentRecorder?.stop()
            saved = pending != null && destination != null && pending.length() > 0 && pending.renameTo(destination)
        } catch (_: RuntimeException) {
        } finally {
            try { currentRecorder?.release() } catch (_: RuntimeException) { }
        }
        if (!saved) pending?.delete()
        if (saved && destination != null) {
            serviceScope.launch {
                val durationSeconds = ((endedAt - startedAt) / 1000L).toInt().coerceAtLeast(1)
                DashcamDatabase.get(this@AudioRecordingService).audioDao().insert(
                    AudioEntity(
                        filename = destination.name,
                        localPath = destination.absolutePath,
                        startTime = startedAt,
                        endTime = endedAt,
                        durationSeconds = durationSeconds,
                        fileSizeBytes = destination.length()
                    )
                )
                UploadWorker.enqueueNow(this@AudioRecordingService)
                val deletedCount = AudioStoragePolicy.enforceLimit(
                    this@AudioRecordingService,
                    destination.parentFile ?: audioDirectory()
                )
                withContext(Dispatchers.Main) {
                    completeFinishedSegment(restart, destination, deletedCount)
                }
            }
        } else {
            completeFinishedSegment(restart, null, 0)
        }
    }

    private fun completeFinishedSegment(restart: Boolean, destination: File?, deletedCount: Int) {
        val savedMessage = destination?.let {
            if (deletedCount > 0) {
                "Saved ${it.name}; overwritten $deletedCount old audio recording(s)"
            } else {
                "Saved ${it.name}"
            }
        }
        if (restart && recordingActive) {
            broadcastState(true, 0, null, savedMessage)
            startSegment()
        } else {
            finishService(savedMessage)
        }
    }

    private fun audioDirectory() =
        File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), AUDIO_DIRECTORY).apply { mkdirs() }

    private fun failAndStop(message: String) {
        recordingActive = false
        temporaryFile?.delete()
        try { recorder?.release() } catch (_: RuntimeException) { }
        recorder = null
        temporaryFile = null
        finalFile = null
        finishService(message)
    }

    private fun finishService(message: String? = null) {
        mainHandler.removeCallbacks(rotateRunnable)
        mainHandler.removeCallbacks(statusRunnable)
        recordingActive = false
        segmentStartMs = 0L
        PowerRecordingSettings.setAudioRecordingActive(this, false)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        broadcastState(false, 0, null, message)
        stopSelf()
    }

    private fun elapsedSeconds(): Int =
        if (recordingActive && segmentStartMs > 0) {
            ((System.currentTimeMillis() - segmentStartMs) / 1000L).toInt().coerceAtLeast(0)
        } else {
            0
        }

    private fun broadcastState(active: Boolean, elapsed: Int, filename: String?, message: String? = null) {
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName)
                .putExtra(EXTRA_ACTIVE, active)
                .putExtra(EXTRA_ELAPSED_SECONDS, elapsed)
                .putExtra(EXTRA_FILENAME, filename)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_dashcam)
        .setContentTitle("Dashcam audio recording")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            0, "Stop",
            PendingIntent.getService(
                this, 1, Intent(this, AudioRecordingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Dashcam audio recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AudioRecording").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (recorder != null) {
            try { recorder?.stop() } catch (_: RuntimeException) { }
            try { recorder?.release() } catch (_: RuntimeException) { }
            temporaryFile?.delete()
        }
        recorder = null
        PowerRecordingSettings.setAudioRecordingActive(this, false)
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.dashcam.audio.START"
        const val ACTION_STOP = "com.example.dashcam.audio.STOP"
        const val ACTION_QUERY_STATE = "com.example.dashcam.audio.QUERY_STATE"
        const val ACTION_STATE = "com.example.dashcam.audio.STATE"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_ELAPSED_SECONDS = "elapsed_seconds"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_MESSAGE = "message"
        const val AUDIO_DIRECTORY = "dashcam-audio"
        private const val CHANNEL_ID = "dashcam_audio_recording"
        private const val NOTIFICATION_ID = 2003
        private const val AUDIO_BIT_RATE = 128_000
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val STATUS_INTERVAL_MS = 1_000L
        private const val SEGMENT_DURATION_MS = 30 * 60 * 1000L
    }
}
