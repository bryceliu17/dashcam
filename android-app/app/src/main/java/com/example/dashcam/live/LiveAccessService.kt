package com.example.dashcam.live

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.OrientationEventListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.dashcam.MainActivity
import com.example.dashcam.R
import com.example.dashcam.network.DeviceStatusReporter
import com.example.dashcam.network.ServerClient
import com.example.dashcam.recording.PowerRecordingSettings
import com.example.dashcam.upload.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class LiveAccessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frameUploading = AtomicBoolean(false)
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private var monitorJob: Job? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var liveClientUrl = ""
    private var liveClient: ServerClient? = null
    private lateinit var orientationListener: OrientationEventListener
    @Volatile private var cameraStarting = false
    @Volatile private var streaming = false
    @Volatile private var deviceOrientation = 0
    private var sensorOrientation = 90

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!streaming) return
            val session = captureSession
            val reader = imageReader
            val device = cameraDevice
            if (session == null || reader == null || device == null) {
                stopStreaming("Live camera became unavailable")
                return
            }
            try {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientationDegrees(sensorOrientation))
                    set(CaptureRequest.JPEG_QUALITY, 65.toByte())
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }.build()
                session.capture(request, null, cameraHandler)
            } catch (error: Exception) {
                Log.w(TAG, "Unable to capture live frame", error)
            } finally {
                if (streaming) cameraHandler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        cameraThread = HandlerThread("dashcam-live-camera").apply { start() }
        cameraHandler = Handler(cameraThread.looper)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceOrientation = ((orientation + 45) / 90 * 90) % 360
            }
        }.also {
            if (it.canDetectOrientation()) it.enable()
        }
        LiveAccessSettings.setStreaming(this, false)
        LiveAccessSettings.setError(this, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> {
                LiveAccessSettings.setEnabled(this, false)
                stopStreaming()
                broadcastState()
                scope.launch { DeviceStatusReporter.reportNow(this@LiveAccessService) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                LiveAccessSettings.setEnabled(this, true)
                startForeground(NOTIFICATION_ID, buildNotification())
                startMonitoring()
                broadcastState()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive && LiveAccessSettings.isEnabled(this@LiveAccessService)) {
                val recording = PowerRecordingSettings.isAnyRecordingActive(this@LiveAccessService)
                if (recording && (streaming || cameraStarting)) {
                    stopStreaming("Live stopped because recording started")
                }
                val control = DeviceStatusReporter.reportNow(this@LiveAccessService)
                when {
                    control?.liveRequested == true && recording ->
                        setError("Phone is recording")
                    control?.liveRequested == true && !streaming && !cameraStarting ->
                        startStreaming()
                    control?.liveRequested == false && (streaming || cameraStarting) ->
                        stopStreaming()
                }
                delay(CONTROL_INTERVAL_MS)
            }
        }
    }

    private fun startStreaming() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setError("Camera permission is required")
            return
        }
        cameraStarting = true
        streaming = true
        LiveAccessSettings.setStreaming(this, true)
        setError(null)
        broadcastState()
        cameraHandler.postDelayed(cameraStart@{
            try {
                val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = manager.cameraIdList.firstOrNull { id ->
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                } ?: manager.cameraIdList.firstOrNull()
                if (cameraId == null) {
                    stopStreaming("No camera is available")
                    return@cameraStart
                }
                val characteristics = manager.getCameraCharacteristics(cameraId)
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val sizes = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.JPEG)
                    .orEmpty()
                val size = sizes
                    .filter { it.width <= 1280 && it.height <= 720 }
                    .maxByOrNull { it.width * it.height }
                    ?: sizes.minByOrNull { it.width * it.height }
                if (size == null) {
                    stopStreaming("Camera does not support JPEG live frames")
                    return@cameraStart
                }
                imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                    setOnImageAvailableListener({ reader -> handleImage(reader) }, cameraHandler)
                }
                manager.openCamera(cameraId, cameraStateCallback, cameraHandler)
            } catch (error: Exception) {
                stopStreaming("Unable to open live camera: ${error.message.orEmpty()}")
            }
        }, CAMERA_RELEASE_DELAY_MS)
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            val surface = imageReader?.surface ?: return stopStreaming("Live frame surface unavailable")
            try {
                camera.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (!cameraStarting) {
                                session.close()
                                return
                            }
                            captureSession = session
                            cameraStarting = false
                            streaming = true
                            LiveAccessSettings.setStreaming(this@LiveAccessService, true)
                            LiveAccessSettings.setError(this@LiveAccessService, null)
                            acquireStreamingLocks()
                            updateNotification()
                            broadcastState()
                            cameraHandler.post(captureRunnable)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            stopStreaming("Unable to configure live camera")
                        }
                    },
                    cameraHandler
                )
            } catch (error: Exception) {
                stopStreaming("Unable to start live camera: ${error.message.orEmpty()}")
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            stopStreaming("Live camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            stopStreaming("Live camera error $error")
        }
    }

    private fun handleImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also(buffer::get)
        } finally {
            image.close()
        }
        if (!streaming || !frameUploading.compareAndSet(false, true)) return
        scope.launch {
            try {
                currentClient().uploadLiveFrame(DeviceStatusReporter.deviceId(this@LiveAccessService), bytes)
            } catch (error: Exception) {
                Log.d(TAG, "Live frame upload deferred: ${error.message.orEmpty()}")
            } finally {
                frameUploading.set(false)
            }
        }
    }

    private fun stopStreaming(error: String? = null) {
        cameraStarting = false
        streaming = false
        LiveAccessSettings.setStreaming(this, false)
        LiveAccessSettings.setError(this, error)
        if (::cameraHandler.isInitialized) {
            cameraHandler.removeCallbacks(captureRunnable)
            cameraHandler.post {
                captureSession?.close()
                captureSession = null
                cameraDevice?.close()
                cameraDevice = null
                imageReader?.close()
                imageReader = null
            }
        }
        releaseStreamingLocks()
        updateNotification()
        broadcastState()
    }

    private fun setError(message: String?) {
        LiveAccessSettings.setError(this, message)
        updateNotification()
        broadcastState()
    }

    private fun currentClient(): ServerClient {
        val serverUrl = getSharedPreferences(UploadWorker.PREFS, Context.MODE_PRIVATE)
            .getString(UploadWorker.KEY_SERVER_URL, UploadWorker.DEFAULT_SERVER_URL)
            ?: UploadWorker.DEFAULT_SERVER_URL
        val existing = liveClient
        if (existing != null && liveClientUrl == serverUrl) return existing
        return ServerClient(serverUrl).also {
            liveClientUrl = serverUrl
            liveClient = it
        }
    }

    private fun jpegOrientationDegrees(cameraSensorOrientation: Int): Int =
        (cameraSensorOrientation + deviceOrientation + 360) % 360

    @Suppress("DEPRECATION")
    private fun acquireStreamingLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:stream").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        if (wifiLock?.isHeld != true) {
            wifiLock = (getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:stream").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseStreamingLocks() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        wakeLock = null
        wifiLock = null
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_dashcam)
        .setContentTitle("Dashcam Live Access")
        .setContentText(
            when {
                streaming -> "Live camera streaming"
                LiveAccessSettings.error(this).isNotBlank() -> LiveAccessSettings.error(this)
                else -> "Waiting for a server request"
            }
        )
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            0,
            "Disable",
            PendingIntent.getService(
                this, 1, Intent(this, LiveAccessService::class.java).setAction(ACTION_DISABLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun updateNotification() {
        if (!LiveAccessSettings.isEnabled(this)) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dashcam live access", NotificationManager.IMPORTANCE_LOW)
            )
    }

    private fun broadcastState() {
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_ENABLED, LiveAccessSettings.isEnabled(this))
                .putExtra(EXTRA_STREAMING, LiveAccessSettings.isStreaming(this))
                .putExtra(EXTRA_ERROR, LiveAccessSettings.error(this))
        )
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        if (::orientationListener.isInitialized) orientationListener.disable()
        stopStreaming()
        if (::cameraThread.isInitialized) cameraThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val ACTION_ENABLE = "com.example.dashcam.live.ENABLE"
        const val ACTION_DISABLE = "com.example.dashcam.live.DISABLE"
        const val ACTION_STATE = "com.example.dashcam.live.STATE"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_STREAMING = "streaming"
        const val EXTRA_ERROR = "error"
        private const val CHANNEL_ID = "dashcam_live_access"
        private const val NOTIFICATION_ID = 2004
        private const val CONTROL_INTERVAL_MS = 2_000L
        private const val FRAME_INTERVAL_MS = 125L
        private const val CAMERA_RELEASE_DELAY_MS = 300L
        private const val TAG = "LiveAccessService"

        fun enable(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, LiveAccessService::class.java).setAction(ACTION_ENABLE)
            )
        }

        fun disable(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, LiveAccessService::class.java).setAction(ACTION_DISABLE)
            )
        }
    }
}
