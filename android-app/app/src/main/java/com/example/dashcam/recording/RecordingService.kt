package com.example.dashcam.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.dashcam.MainActivity
import com.example.dashcam.R
import com.example.dashcam.data.DashcamDatabase
import com.example.dashcam.data.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class RecordingService : LifecycleService() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private var continueRecording = false
    private var segmentStart = 0L
    private var segmentFile: File? = null
    private var segmentDurationSeconds = 0
    private var stopMessage: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Monitoring-camera mode: do not stop recording automatically when power is
    // disconnected. Start/stop is controlled manually from the UI.
    // private val powerReceiver = object : BroadcastReceiver() {
    //     override fun onReceive(context: Context?, intent: Intent?) {
    //         if (intent?.action == Intent.ACTION_POWER_DISCONNECTED) stopSafely("Power disconnected")
    //     }
    // }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // ContextCompat.registerReceiver(
        //     this, powerReceiver, IntentFilter(Intent.ACTION_POWER_DISCONNECTED),
        //     ContextCompat.RECEIVER_NOT_EXPORTED
        // )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSafely("Stopped by user")
            else -> if (recording == null && !continueRecording) {
                startForeground(NOTIFICATION_ID, buildNotification())
                // Monitoring-camera mode: allow manual recording on battery.
                // if (!isCharging()) {
                //     broadcastState(false, "Connect power before starting the dashcam")
                //     stopSelf()
                // } else {
                continueRecording = true
                stopMessage = null
                acquireWakeLock()
                setRecordingPreference(true)
                broadcastState(true, null)
                startCamera()
                // }
            }
        }
        return Service.START_NOT_STICKY
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                startSegment()
            } catch (error: Exception) {
                failAndStop("Camera unavailable: ${error.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startSegment() {
        if (!continueRecording || recording != null) return
        val directory = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "dashcam").apply { mkdirs() }
        lifecycleScope.launch {
            val hasSpace = withContext(Dispatchers.IO) { StoragePolicy.prepareForRecording(this@RecordingService, directory) }
            if (!hasSpace) {
                failAndStop("Storage full, recording stopped.")
                return@launch
            }
            try {
                val capture = bindCameraForRecording()

                segmentStart = System.currentTimeMillis()
                segmentDurationSeconds = 0
                val filename = SimpleDateFormat("'dashcam_'yyyyMMdd_HHmmss'.mp4'", Locale.US).format(Date(segmentStart))
                segmentFile = File(directory, filename)
                val options = FileOutputOptions.Builder(segmentFile!!).build()
                recording = capture.output.prepareRecording(this@RecordingService, options)
                    .start(cameraExecutor) { event -> handleVideoEvent(event) }
            } catch (error: Exception) {
                failAndStop("Unable to start recording: ${error.message}")
            }
        }
    }

    private fun handleVideoEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                updateSegmentDuration(event)
                broadcastState(true, "Recording segment started")
                scheduleSegmentRotation()
            }
            is VideoRecordEvent.Status -> updateSegmentDuration(event)
            is VideoRecordEvent.Finalize -> {
                updateSegmentDuration(event)
                val finishedFile = segmentFile
                val startedAt = segmentStart
                recording = null
                segmentFile = null
                if (finishedFile != null && finishedFile.length() > 0) {
                    val endedAt = System.currentTimeMillis()
                    val durationSeconds = segmentDurationSeconds.takeIf { it > 0 }
                        ?: ((endedAt - startedAt) / 1000).toInt().coerceAtLeast(1)
                    lifecycleScope.launch(Dispatchers.IO) {
                        DashcamDatabase.get(this@RecordingService).videoDao().insert(
                            VideoEntity(
                                filename = finishedFile.name,
                                localPath = finishedFile.absolutePath,
                                startTime = startedAt,
                                endTime = endedAt,
                                durationSeconds = durationSeconds,
                                fileSizeBytes = finishedFile.length()
                            )
                        )
                        UploadScheduler.onSegmentReady(this@RecordingService)
                        withContext(Dispatchers.Main) {
                            val message = if (event.hasError()) {
                                "Saved ${finishedFile.name} after stop"
                            } else {
                                "Saved ${finishedFile.name}"
                            }
                            broadcastState(continueRecording, message)
                            afterSegmentFinalized()
                        }
                    }
                } else {
                    val stoppedByUser = !continueRecording && stopMessage != null
                    val error = if (stoppedByUser) {
                        stopMessage
                    } else if (event.hasError()) {
                        "Recording failed: ${event.error} ${event.cause?.message.orEmpty()}".trim()
                    } else {
                        "Recording did not produce a playable video"
                    }
                    broadcastState(continueRecording, error)
                    finishedFile?.delete()
                    ContextCompat.getMainExecutor(this).execute { afterSegmentFinalized() }
                }
            }
        }
    }

    private fun updateSegmentDuration(event: VideoRecordEvent) {
        segmentDurationSeconds = (event.recordingStats.recordedDurationNanos / 1_000_000_000L)
            .toInt()
            .coerceAtLeast(segmentDurationSeconds)
    }

    private fun bindCameraForRecording(): VideoCapture<Recorder> {
        val provider = cameraProvider ?: throw IllegalStateException("Camera provider not ready")
        val cameras = listOf(
            "back" to CameraSelector.DEFAULT_BACK_CAMERA,
            "front" to CameraSelector.DEFAULT_FRONT_CAMERA
        )
        val qualities = listOf(
            "1080p" to Quality.FHD,
            "720p" to Quality.HD,
            "480p" to Quality.SD
        )
        var lastError: Exception? = null

        for ((cameraName, selector) in cameras) {
            if (!provider.hasCamera(selector)) continue
            for ((qualityName, quality) in qualities) {
                try {
                    val recorder = Recorder.Builder().setQualitySelector(
                        QualitySelector.fromOrderedList(
                            listOf(quality),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    ).build()
                    val capture = VideoCapture.withOutput(recorder)
                    capture.targetRotation = (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                        .defaultDisplay.rotation
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider { request -> provideDummyPreviewSurface(request) }
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, preview, capture)

                    broadcastState(true, "Camera ready: $cameraName $qualityName background preview")
                    return capture
                } catch (error: Exception) {
                    lastError = error
                }
            }
        }

        throw IllegalStateException("No supported camera recording combination: ${lastError?.message.orEmpty()}")
    }

    private fun provideDummyPreviewSurface(request: SurfaceRequest) {
        val texture = SurfaceTexture(0).apply {
            setDefaultBufferSize(request.resolution.width, request.resolution.height)
        }
        val surface = Surface(texture)
        request.provideSurface(surface, cameraExecutor) {
            surface.release()
            texture.release()
        }
    }

    private fun scheduleSegmentRotation() {
        ContextCompat.getMainExecutor(this).execute {
            android.os.Handler(mainLooper).postDelayed({
                if (continueRecording) recording?.stop()
            }, SEGMENT_DURATION_MS)
        }
    }

    private fun afterSegmentFinalized() {
        if (continueRecording) startSegment() else finishService()
    }

    private fun stopSafely(reason: String) {
        continueRecording = false
        stopMessage = reason
        val current = recording
        if (current != null) {
            broadcastState(true, "Stopping and saving current segment")
            current.stop()
        } else {
            broadcastState(false, reason)
            finishService()
        }
    }

    private fun failAndStop(message: String) {
        continueRecording = false
        broadcastState(false, message)
        recording?.stop() ?: finishService()
    }

    private fun finishService() {
        setRecordingPreference(false)
        cameraProvider?.unbindAll()
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:DashcamRecording").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun isCharging(): Boolean {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_dashcam)
        .setContentTitle("Dashcam recording")
        .setContentText("Recording 3-minute video segments")
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            pendingIntentFlags()
        ))
        .addAction(0, "Stop", PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            pendingIntentFlags()
        )).build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dashcam recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun broadcastState(active: Boolean, message: String?) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_ACTIVE, active).putExtra(EXTRA_MESSAGE, message))
    }

    private fun setRecordingPreference(active: Boolean) {
        getSharedPreferences("dashcam_settings", MODE_PRIVATE).edit().putBoolean("recording_active", active).apply()
    }

    override fun onDestroy() {
        // try { unregisterReceiver(powerReceiver) } catch (_: IllegalArgumentException) { }
        setRecordingPreference(false)
        cameraProvider?.unbindAll()
        recording?.close()
        releaseWakeLock()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.dashcam.START"
        const val ACTION_STOP = "com.example.dashcam.STOP"
        const val ACTION_STATE = "com.example.dashcam.STATE"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_MESSAGE = "message"
        @Volatile var previewSurfaceProvider: Preview.SurfaceProvider? = null
        private const val CHANNEL_ID = "dashcam_recording"
        private const val NOTIFICATION_ID = 1001
        private const val SEGMENT_DURATION_MS = 3 * 60 * 1000L
    }
}

private object UploadScheduler {
    fun onSegmentReady(context: Context) = com.example.dashcam.upload.UploadWorker.enqueueNow(context)
}
