package com.example.dashcam.recording

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.dashcam.MainActivity
import com.example.dashcam.R
import com.example.dashcam.data.DashcamDatabase
import com.example.dashcam.data.VideoEntity
import com.example.dashcam.upload.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackgroundRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler()
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeCameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var legacyCamera: Camera? = null
    private var legacySurfaceTexture: SurfaceTexture? = null
    private var legacySurface: Surface? = null
    private var captureSession: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var segmentStartMs = 0L
    private var continueRecording = false
    private var stopAfterCurrentSegmentRequested = false

    private val rotateRunnable = Runnable {
        if (continueRecording) stopSegment(restart = !stopAfterCurrentSegmentRequested)
    }
    private val statusRunnable = object : Runnable {
        override fun run() {
            if (continueRecording) {
                broadcastState(true, currentElapsedSeconds(), currentFile?.name)
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        cameraThread = HandlerThread("dashcam-background-camera").also { it.start() }
        cameraHandler = Handler(cameraThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_STOP_AFTER_SEGMENT -> stopAfterCurrentSegment()
            else -> startRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording() {
        if (continueRecording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        continueRecording = true
        stopAfterCurrentSegmentRequested = false
        PowerRecordingSettings.setBackgroundRecordingActive(this, true)
        startForeground(NOTIFICATION_ID, buildNotification("Starting background recording"))
        broadcastState(true, 0, null)
        acquireWakeLock()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
            openLegacyCamera()
        } else {
            openCamera()
        }
    }

    private fun openLegacyCamera() {
        try {
            legacyCamera = Camera.open(findLegacyBackCameraId()).also {
                it.setDisplayOrientation(90)
            }
            startLegacySegment()
        } catch (error: Exception) {
            failAndStop("Background camera unavailable: ${error.message.orEmpty()}")
        }
    }

    private fun findLegacyBackCameraId(): Int {
        val info = Camera.CameraInfo()
        for (id in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(id, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return id
        }
        return 0
    }

    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.first()
        activeCameraId = cameraId

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startSegment()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected while background recording")
                    camera.close()
                    stopRecordingAndSaveCurrentSegment()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera open error: $error")
                    camera.close()
                    failAndStop()
                }
            }, cameraHandler)
        } catch (error: SecurityException) {
            Log.e(TAG, "Camera permission unavailable while starting background recording", error)
            failAndStop()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to open camera for background recording", error)
            failAndStop()
        }
    }

    private fun startLegacySegment() {
        val camera = legacyCamera ?: return failAndStop("Legacy camera unavailable")
        val directory = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "dashcam").apply { mkdirs() }

        scope.launch {
            if (!StoragePolicy.prepareForRecording(this@BackgroundRecordingService, directory)) {
                mainHandler.post { failAndStop("Storage full, background recording stopped") }
                return@launch
            }

            val startedAt = System.currentTimeMillis()
            val filename = SimpleDateFormat("'dashcam_bg_'yyyyMMdd_HHmmss'.mp4'", Locale.US).format(Date(startedAt))
            val file = File(directory, filename)

            mainHandler.post {
                try {
                    currentFile = file
                    segmentStartMs = startedAt
                    broadcastState(true, 0, file.name)
                    recorder = createLegacyRecorder(file, camera).also {
                        it.prepare()
                        it.start()
                    }
                    updateNotification("Background recording")
                    mainHandler.removeCallbacks(statusRunnable)
                    mainHandler.post(statusRunnable)
                    mainHandler.removeCallbacks(rotateRunnable)
                    mainHandler.postDelayed(rotateRunnable, SEGMENT_DURATION_MS)
                } catch (error: Exception) {
                    failAndStop("Unable to start background recording: ${error.message.orEmpty()}")
                }
            }
        }
    }

    private fun startSegment() {
        val camera = cameraDevice ?: return
        val directory = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "dashcam").apply { mkdirs() }

        scope.launch {
            if (!StoragePolicy.prepareForRecording(this@BackgroundRecordingService, directory)) {
                mainHandler.post { failAndStop() }
                return@launch
            }

            val startedAt = System.currentTimeMillis()
            val filename = SimpleDateFormat("'dashcam_bg_'yyyyMMdd_HHmmss'.mp4'", Locale.US).format(Date(startedAt))
            val file = File(directory, filename)

            mainHandler.post {
                try {
                    currentFile = file
                    segmentStartMs = startedAt
                    broadcastState(true, 0, file.name)
                    recorder = createRecorder(file).also { it.prepare() }
                    val recorderSurface = recorder!!.surface
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(recorderSurface)
                    }

                    camera.createCaptureSession(listOf(recorderSurface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            session.setRepeatingRequest(request.build(), null, cameraHandler)
                            recorder?.start()
                            updateNotification("Background recording")
                            mainHandler.removeCallbacks(statusRunnable)
                            mainHandler.post(statusRunnable)
                            mainHandler.removeCallbacks(rotateRunnable)
                            mainHandler.postDelayed(rotateRunnable, SEGMENT_DURATION_MS)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            failAndStop()
                        }
                    }, cameraHandler)
                } catch (_: Exception) {
                    failAndStop()
                }
            }
        }
    }

    private fun createRecorder(file: File): MediaRecorder {
        val profile = if (CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_720P)) {
            CamcorderProfile.get(CamcorderProfile.QUALITY_720P)
        } else {
            CamcorderProfile.get(CamcorderProfile.QUALITY_480P)
        }

        return MediaRecorder().apply {
            val canRecordAudio = ContextCompat.checkSelfPermission(
                this@BackgroundRecordingService,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (canRecordAudio) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(file.absolutePath)
            if (canRecordAudio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
            }
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setOrientationHint(getOrientationHintDegrees())
            setVideoEncodingBitRate(profile.videoBitRate)
            setVideoFrameRate(profile.videoFrameRate)
            setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight)
        }
    }

    private fun createLegacyRecorder(file: File, camera: Camera): MediaRecorder {
        val profile = if (CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_720P)) {
            CamcorderProfile.get(CamcorderProfile.QUALITY_720P)
        } else {
            CamcorderProfile.get(CamcorderProfile.QUALITY_480P)
        }
        legacySurfaceTexture?.release()
        legacySurface?.release()
        legacySurfaceTexture = SurfaceTexture(0).apply {
            setDefaultBufferSize(profile.videoFrameWidth, profile.videoFrameHeight)
        }
        legacySurface = Surface(legacySurfaceTexture)

        try {
            camera.setPreviewTexture(legacySurfaceTexture)
            camera.startPreview()
        } catch (_: Exception) {
        }
        camera.unlock()

        return MediaRecorder().apply {
            val canRecordAudio = ContextCompat.checkSelfPermission(
                this@BackgroundRecordingService,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            setCamera(camera)
            if (canRecordAudio) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setVideoSource(MediaRecorder.VideoSource.CAMERA)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(file.absolutePath)
            if (canRecordAudio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
            }
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setOrientationHint(90)
            setVideoEncodingBitRate(profile.videoBitRate)
            setVideoFrameRate(profile.videoFrameRate)
            setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight)
            setPreviewDisplay(legacySurface)
        }
    }

    private fun getOrientationHintDegrees(): Int {
        val cameraId = activeCameraId ?: return 90
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (360 - sensorOrientation) % 360
        } else {
            sensorOrientation
        }
    }

    private fun stopSegment(restart: Boolean) {
        mainHandler.removeCallbacks(rotateRunnable)
        val file = currentFile
        val startedAt = segmentStartMs

        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
        } catch (_: Exception) {
        }
        captureSession?.close()
        captureSession = null

        try {
            recorder?.stop()
        } catch (_: Exception) {
            file?.delete()
        }
        recorder?.reset()
        recorder?.release()
        recorder = null
        try {
            legacyCamera?.reconnect()
        } catch (_: Exception) {
        }
        currentFile = null

        if (file != null && file.exists() && file.length() > 0) {
            val endedAt = System.currentTimeMillis()
            val durationSeconds = ((endedAt - startedAt) / 1000).toInt().coerceAtLeast(1)
            broadcastState(restart && continueRecording, durationSeconds, file.name)
            scope.launch {
                DashcamDatabase.get(this@BackgroundRecordingService).videoDao().insert(
                    VideoEntity(
                        filename = file.name,
                        localPath = file.absolutePath,
                        startTime = startedAt,
                        endTime = endedAt,
                        durationSeconds = durationSeconds,
                        fileSizeBytes = file.length()
                    )
                )
                UploadWorker.enqueueNow(this@BackgroundRecordingService)
            }
        }

        if (restart && continueRecording && !stopAfterCurrentSegmentRequested) {
            if (legacyCamera != null) startLegacySegment() else startSegment()
        } else {
            finishService()
        }
    }

    private fun stopRecording() {
        continueRecording = false
        stopAfterCurrentSegmentRequested = false
        if (recorder != null) stopSegment(restart = false) else finishService()
    }

    private fun stopRecordingAndSaveCurrentSegment() {
        continueRecording = false
        stopAfterCurrentSegmentRequested = false
        if (recorder != null || currentFile != null) stopSegment(restart = false) else finishService()
    }

    private fun stopAfterCurrentSegment() {
        if (!continueRecording) {
            finishService()
            return
        }
        stopAfterCurrentSegmentRequested = true
        updateNotification("Stopping after current segment")
        broadcastState(true, currentElapsedSeconds(), currentFile?.name)
        if (recorder == null && currentFile == null) stopRecording()
    }

    private fun failAndStop(message: String? = null) {
        continueRecording = false
        stopAfterCurrentSegmentRequested = false
        currentFile?.delete()
        broadcastState(false, 0, null, message)
        finishService()
    }

    private fun finishService() {
        cleanupResources()
        stopSelf()
    }

    private fun cleanupResources() {
        mainHandler.removeCallbacks(rotateRunnable)
        mainHandler.removeCallbacks(statusRunnable)
        captureSession?.close()
        captureSession = null
        recorder?.release()
        recorder = null
        currentFile = null
        cameraDevice?.close()
        cameraDevice = null
        try {
            legacyCamera?.stopPreview()
        } catch (_: Exception) {
        }
        legacyCamera?.release()
        legacyCamera = null
        legacySurface?.release()
        legacySurface = null
        legacySurfaceTexture?.release()
        legacySurfaceTexture = null
        releaseWakeLock()
        stopForegroundCompat()
        PowerRecordingSettings.setBackgroundRecordingActive(this, false)
        broadcastState(false, 0, null)
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_dashcam)
        .setContentTitle("Dashcam background recording")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            pendingIntentFlags()
        ))
        .addAction(0, "Stop", PendingIntent.getService(
            this, 1, Intent(this, BackgroundRecordingService::class.java).setAction(ACTION_STOP),
            pendingIntentFlags()
        ))
        .build()

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dashcam background recording", NotificationManager.IMPORTANCE_LOW)
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

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:BackgroundRecording").apply {
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

    override fun onDestroy() {
        cleanupResources()
        scope.cancel()
        cameraThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.dashcam.background.START"
        const val ACTION_STOP = "com.example.dashcam.background.STOP"
        const val ACTION_STOP_AFTER_SEGMENT = "com.example.dashcam.background.STOP_AFTER_SEGMENT"
        const val ACTION_STATE = "com.example.dashcam.background.STATE"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_ELAPSED_SECONDS = "elapsed_seconds"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL_ID = "dashcam_background_recording"
        private const val NOTIFICATION_ID = 2001
        private const val SEGMENT_DURATION_MS = 3 * 60 * 1000L
        private const val TAG = "BackgroundRecordingService"
    }

    private fun currentElapsedSeconds(): Int =
        if (segmentStartMs > 0) ((System.currentTimeMillis() - segmentStartMs) / 1000).toInt().coerceAtLeast(0) else 0

    private fun broadcastState(active: Boolean, elapsedSeconds: Int, filename: String?, message: String? = null) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_ACTIVE, active)
            .putExtra(EXTRA_ELAPSED_SECONDS, elapsedSeconds)
            .putExtra(EXTRA_FILENAME, filename)
            .putExtra(EXTRA_MESSAGE, message))
    }
}
