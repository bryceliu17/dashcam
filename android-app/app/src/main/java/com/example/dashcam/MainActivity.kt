package com.example.dashcam

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.dashcam.data.DashcamDatabase
import com.example.dashcam.data.VideoEntity
import com.example.dashcam.network.ServerClient
import com.example.dashcam.recording.BackgroundRecordingService
import com.example.dashcam.recording.PowerMonitorService
import com.example.dashcam.recording.PowerRecordingSettings
import com.example.dashcam.recording.RecordingService
import com.example.dashcam.recording.StoragePolicy
import com.example.dashcam.recording.VolumeKeyAccessibilityService
import com.example.dashcam.upload.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var recordingStatus: TextView
    private lateinit var chargingStatus: TextView
    private lateinit var serverStatus: TextView
    private lateinit var storageStatus: TextView
    private lateinit var serverUrl: EditText
    private lateinit var serverUrlDisplay: TextView
    private lateinit var previewRecordButton: Button
    private lateinit var backgroundRecordButton: Button
    private lateinit var powerAutoButton: Button
    private lateinit var volumeKeyButton: Button
    private lateinit var previewView: PreviewView
    private lateinit var videoList: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var videos: List<VideoEntity> = emptyList()
    private val recordedOrientationCache = mutableMapOf<String, String>()
    private var showingVideoManager = false
    private var showingVideoList = false
    private var returnToVideoListAfterManager = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private var continueRecording = false
    private var segmentStart = 0L
    private var segmentFile: File? = null
    private var segmentDurationSeconds = 0
    private var manualStartTime: Long? = null
    private var completedSegmentsSinceManualStart = 0
    private var overwrittenVideosSinceManualStart = 0
    private var pendingBackgroundStart = false
    private var editingServerUrl = false
    private var backgroundRecordingActive = false
    private var backgroundElapsedSeconds = 0
    private var backgroundFilename: String? = null
    private var stopAfterCurrentSegment = false
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateRecordingStatus()
            if (recording != null || continueRecording) mainHandler.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            if (pendingBackgroundStart) startBackgroundDashcam() else startDashcam()
        } else {
            toast("Camera permission is required")
        }
        if (permissions[Manifest.permission.RECORD_AUDIO] == false) {
            val mode = if (pendingBackgroundStart) "background" else "preview"
            toast("Microphone permission denied; $mode recording will be silent")
        }
        pendingBackgroundStart = false
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderRecording(intent?.getBooleanExtra(RecordingService.EXTRA_ACTIVE, false) == true)
            intent?.getStringExtra(RecordingService.EXTRA_MESSAGE)?.let(::toast)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { renderCharging(intent) }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!PowerRecordingSettings.isPowerAutoBackgroundEnabled(this@MainActivity)) return
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    if (recording == null && !continueRecording && !backgroundRecordingActive &&
                        !PowerRecordingSettings.isAnyRecordingActive(this@MainActivity)
                    ) {
                        startBackgroundDashcam()
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (recording != null || continueRecording) requestStopAfterCurrentSegment()
                }
            }
        }
    }

    private val backgroundStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val wasActive = backgroundRecordingActive
            backgroundRecordingActive = intent?.getBooleanExtra(BackgroundRecordingService.EXTRA_ACTIVE, false) == true
            backgroundElapsedSeconds = intent?.getIntExtra(BackgroundRecordingService.EXTRA_ELAPSED_SECONDS, 0) ?: 0
            backgroundFilename = intent?.getStringExtra(BackgroundRecordingService.EXTRA_FILENAME)
            intent?.getStringExtra(BackgroundRecordingService.EXTRA_MESSAGE)?.takeIf { it.isNotBlank() }?.let(::toast)
            if (backgroundRecordingActive != wasActive) updatePreviewAvailability()
            updateBackgroundRecordButton()
            updateRecordingStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backgroundRecordingActive = PowerRecordingSettings.isBackgroundRecordingActive(this)
        val prefs = getSharedPreferences(UploadWorker.PREFS, MODE_PRIVATE)
        buildUi()
        serverUrl.setText(prefs.getString(UploadWorker.KEY_SERVER_URL, UploadWorker.DEFAULT_SERVER_URL))
        setRecordingPreference(false)
        if (PowerRecordingSettings.isPowerAutoBackgroundEnabled(this)) PowerMonitorService.start(this)
        renderRecording(false)
        observeVideos()
        checkServer()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingVideoManager) {
                    showingVideoManager = false
                    if (returnToVideoListAfterManager) showLocalVideos() else buildUi()
                } else if (showingVideoList) {
                    showingVideoList = false
                    buildUi()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (::previewView.isInitialized) updatePreviewAvailability()
        updateModeButtons()
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(RecordingService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, backgroundStateReceiver, IntentFilter(BackgroundRecordingService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        updateModeButtons()
        updatePreviewAvailability()
    }

    override fun onStop() {
        try { unregisterReceiver(stateReceiver) } catch (_: IllegalArgumentException) { }
        try { unregisterReceiver(backgroundStateReceiver) } catch (_: IllegalArgumentException) { }
        try { unregisterReceiver(batteryReceiver) } catch (_: IllegalArgumentException) { }
        try { unregisterReceiver(powerReceiver) } catch (_: IllegalArgumentException) { }
        RecordingService.previewSurfaceProvider = null
        super.onStop()
    }

    override fun onDestroy() {
        if (recording != null || continueRecording) stopDashcam("Activity closed")
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildUi() {
        showingVideoList = false
        returnToVideoListAfterManager = false
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(244, 244, 240))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(Color.rgb(244, 244, 240))
        }
        root.addView(TextView(this).apply {
            text = "LOCAL DASHCAM"; textSize = 11f; letterSpacing = .18f; setTextColor(Color.rgb(77, 124, 15))
        })
        root.addView(TextView(this).apply {
            text = "Dashcam"; textSize = 30f; setTextColor(Color.rgb(17, 24, 39)); setPadding(0, dp(3), 0, dp(18))
        })

        recordingStatus = statusRow("Recording")
        chargingStatus = statusRow("Power")
        serverStatus = statusRow("Home Server")
        storageStatus = statusRow("Local Videos")
        listOf(recordingStatus, chargingStatus, serverStatus, storageStatus).forEach(root::addView)
        updateStorageStatus()

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        root.addView(previewView, LinearLayout.LayoutParams(-1, previewHeight()).apply { topMargin = dp(14) })
        updatePreviewAvailability()

        val savedServerUrl = getSharedPreferences(UploadWorker.PREFS, MODE_PRIVATE)
            .getString(UploadWorker.KEY_SERVER_URL, UploadWorker.DEFAULT_SERVER_URL)
            ?: UploadWorker.DEFAULT_SERVER_URL
        serverUrl = EditText(this).apply {
            hint = "http://192.168.1.50:5000"; textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true); setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(savedServerUrl)
        }
        serverUrlDisplay = TextView(this).apply {
            text = savedServerUrl
            textSize = 14f
            setTextColor(Color.rgb(31, 41, 55))
            setSingleLine(true)
            setPadding(dp(12), dp(14), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val serverUrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        serverUrlRow.addView(
            if (editingServerUrl) serverUrl else serverUrlDisplay,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        serverUrlRow.addView(actionButton(if (editingServerUrl) "Save" else "Edit") {
            if (editingServerUrl) {
                saveServerUrl()
                editingServerUrl = false
                buildUi()
                checkServer()
            } else {
                editingServerUrl = true
                buildUi()
            }
        }, LinearLayout.LayoutParams(dp(86), dp(52)).apply { marginStart = dp(8) })
        root.addView(serverUrlRow, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(18) })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        previewRecordButton = actionButton(if (recording != null || continueRecording) "Stop Dashcam" else "Start Dashcam") {
            if (recording != null || continueRecording) {
                stopDashcam("Stopped by user")
            } else if (!backgroundRecordingActive) {
                requestStart()
            }
        }
        controls.addView(previewRecordButton, LinearLayout.LayoutParams(-1, -1))
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        val backgroundControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        backgroundRecordButton = actionButton(if (backgroundRecordingActive) "Stop Background" else "Start Background") {
            if (backgroundRecordingActive) {
                stopBackgroundDashcam()
            } else if (recording == null && !continueRecording) {
                requestBackgroundStart()
            }
        }
        backgroundControls.addView(backgroundRecordButton, LinearLayout.LayoutParams(-1, -1))
        root.addView(backgroundControls, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        powerAutoButton = actionButton(powerAutoButtonLabel()) {
            togglePowerAutoBackground()
        }
        root.addView(powerAutoButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        volumeKeyButton = actionButton(volumeKeyButtonLabel()) {
            toggleVolumeKeyStart()
        }
        root.addView(volumeKeyButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        val secondaryControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        secondaryControls.addView(actionButton("Upload Now") {
            startManualUpload()
        }, weighted())
        secondaryControls.addView(actionButton("Local Videos") {
            showLocalVideos()
        }, weighted().apply { marginStart = dp(8) })
        root.addView(secondaryControls, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        scroll.addView(root)
        setContentView(scroll)
        updateModeButtons()
        updateRecordingStatus()
    }

    private fun showLocalVideos() {
        showingVideoList = true
        showingVideoManager = false
        returnToVideoListAfterManager = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(244, 244, 240))
        }

        root.addView(TextView(this).apply {
            text = "Local Videos"
            textSize = 24f
            setTextColor(Color.rgb(17, 24, 39))
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "${videos.size} videos - ${formatBytes(videos.sumOf { it.fileSizeBytes })}"
            textSize = 14f
            setTextColor(Color.rgb(55, 65, 81))
            setPadding(0, 0, 0, dp(10))
        })

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        videoList = ListView(this).apply {
            adapter = this@MainActivity.adapter
            dividerHeight = 1
            setOnItemClickListener { _, _, position, _ ->
                returnToVideoListAfterManager = true
                showVideoManager(videos[position])
            }
            setOnItemLongClickListener { _, _, position, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    DashcamDatabase.get(this@MainActivity).videoDao().toggleLock(videos[position].id)
                }
                true
            }
        }
        adapter.addAll(videos.map(::formatVideo))
        root.addView(videoList, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(actionButton("Set All Playback Rotation") {
            showSetAllPlaybackRotationDialog()
        }.apply {
            isEnabled = videos.isNotEmpty()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(actionButton("Back") {
            showingVideoList = false
            buildUi()
        }, weighted())
        controls.addView(actionButton("Delete All") {
            confirmDeleteAllVideos()
        }.apply {
            isEnabled = videos.isNotEmpty()
        }, weighted().apply { marginStart = dp(8) })
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        setContentView(root)
    }

    private fun showVideoManager(video: VideoEntity) {
        val file = File(video.localPath)
        if (!file.exists()) {
            toast("Video file is missing")
            return
        }

        showingVideoManager = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(244, 244, 240))
        }

        root.addView(TextView(this).apply {
            text = video.filename
            textSize = 18f
            setTextColor(Color.rgb(17, 24, 39))
            setPadding(0, 0, 0, dp(10))
        })

        var currentRotation = effectivePlaybackRotation(video)
        val playerContainer = FrameLayout(this)
        val player = VideoView(this).apply {
            setVideoPath(file.absolutePath)
            setMediaController(MediaController(this@MainActivity).also { it.setAnchorView(this) })
            setOnPreparedListener { it.isLooping = false; start() }
            setOnErrorListener { _, _, _ ->
                toast("Unable to play this video")
                true
            }
        }
        playerContainer.addView(player)
        root.addView(playerContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        applyPlayerRotation(player, playerContainer, currentRotation)

        val details = TextView(this).apply {
            val lock = if (video.locked) "LOCKED" else "NORMAL"
            text = "${recordingSource(video)} - ${formatDurationSeconds(video.durationSeconds)} - ${formatBytes(video.fileSizeBytes)} - Playback ${currentRotation}° - ${video.uploadStatus} - $lock\n${file.absolutePath}"
            textSize = 12f
            setTextColor(Color.rgb(55, 65, 81))
            setPadding(0, dp(10), 0, dp(10))
        }
        root.addView(details)

        val rotateButton = actionButton("Rotate Playback 90°") {
            currentRotation = (currentRotation + 90) % 360
            applyPlayerRotation(player, playerContainer, currentRotation)
            val lock = if (video.locked) "LOCKED" else "NORMAL"
            details.text = "${recordingSource(video)} - ${formatDurationSeconds(video.durationSeconds)} - ${formatBytes(video.fileSizeBytes)} - Playback ${currentRotation}° - ${video.uploadStatus} - $lock\n${file.absolutePath}"
            lifecycleScope.launch(Dispatchers.IO) {
                DashcamDatabase.get(this@MainActivity).videoDao().setPlaybackRotation(video.id, currentRotation)
                val synced = syncPlaybackRotation(video, currentRotation)
                if (!synced) withContext(Dispatchers.Main) {
                    toast("Rotation saved on phone; server sync failed")
                }
            }
        }
        root.addView(rotateButton, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(actionButton("Back") {
            player.stopPlayback()
            showingVideoManager = false
            if (returnToVideoListAfterManager) showLocalVideos() else buildUi()
        }, weighted())
        controls.addView(actionButton(if (video.locked) "Unlock" else "Lock") {
            lifecycleScope.launch(Dispatchers.IO) {
                DashcamDatabase.get(this@MainActivity).videoDao().toggleLock(video.id)
                withContext(Dispatchers.Main) {
                    player.stopPlayback()
                    showingVideoManager = false
                    if (returnToVideoListAfterManager) showLocalVideos() else buildUi()
                }
            }
        }, weighted().apply { marginStart = dp(8) })
        controls.addView(actionButton("Delete") {
            confirmDelete(video, player)
        }, weighted().apply { marginStart = dp(8) })
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(52)))
        setContentView(root)
    }

    private fun applyPlayerRotation(player: VideoView, container: FrameLayout, degrees: Int) {
        container.post {
            val quarterTurn = degrees == 90 || degrees == 270
            player.layoutParams = FrameLayout.LayoutParams(
                if (quarterTurn) container.height else FrameLayout.LayoutParams.MATCH_PARENT,
                if (quarterTurn) container.width else FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            player.rotation = degrees.toFloat()
        }
    }

    private fun showSetAllPlaybackRotationDialog() {
        val rotations = intArrayOf(0, 90, 180, 270)
        val labels = rotations.map { "$it°" }.toTypedArray()
        val current = defaultPlaybackRotation()
        AlertDialog.Builder(this)
            .setTitle("Set playback rotation for all videos")
            .setSingleChoiceItems(labels, rotations.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                val degrees = rotations[which]
                getSharedPreferences(UploadWorker.PREFS, MODE_PRIVATE).edit()
                    .putInt(UploadWorker.KEY_DEFAULT_PLAYBACK_ROTATION, degrees)
                    .apply()
                val videosToSync = videos.toList()
                lifecycleScope.launch(Dispatchers.IO) {
                    DashcamDatabase.get(this@MainActivity).videoDao().setAllPlaybackRotations(degrees)
                    val failedSyncs = videosToSync.count { !syncPlaybackRotation(it, degrees) }
                    withContext(Dispatchers.Main) {
                        videos = videos.map { it.copy(playbackRotationDegrees = degrees) }
                        val suffix = if (failedSyncs > 0) "; $failedSyncs server sync(s) failed" else ""
                        toast("Playback rotation set to $degrees°$suffix")
                        showLocalVideos()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun effectivePlaybackRotation(video: VideoEntity): Int =
        video.playbackRotationDegrees ?: defaultPlaybackRotation()

    private fun defaultPlaybackRotation(): Int =
        getSharedPreferences(UploadWorker.PREFS, MODE_PRIVATE)
            .getInt(UploadWorker.KEY_DEFAULT_PLAYBACK_ROTATION, 0)

    private fun syncPlaybackRotation(video: VideoEntity, degrees: Int): Boolean {
        val serverId = video.serverVideoId ?: return true
        return try {
            val serverUrl = getSharedPreferences(UploadWorker.PREFS, MODE_PRIVATE)
                .getString(UploadWorker.KEY_SERVER_URL, UploadWorker.DEFAULT_SERVER_URL)
                ?: UploadWorker.DEFAULT_SERVER_URL
            ServerClient(serverUrl).updatePlaybackRotation(serverId, degrees)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun confirmDelete(video: VideoEntity, player: VideoView) {
        AlertDialog.Builder(this)
            .setTitle("Delete video?")
            .setMessage(video.filename)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val file = File(video.localPath)
                    val deleted = !file.exists() || file.delete()
                    if (deleted) DashcamDatabase.get(this@MainActivity).videoDao().delete(video)
                    withContext(Dispatchers.Main) {
                        if (!deleted) {
                            toast("Unable to delete video file")
                        } else {
                            player.stopPlayback()
                            showingVideoManager = false
                            if (returnToVideoListAfterManager) showLocalVideos() else buildUi()
                        }
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteAllVideos() {
        if (videos.isEmpty()) {
            toast("No videos to delete")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete all videos?")
            .setMessage("This will delete ${videos.size} local videos from this app.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete All") { _, _ ->
                val videosToDelete = videos.toList()
                lifecycleScope.launch(Dispatchers.IO) {
                    var deletedCount = 0
                    var failedCount = 0
                    val dao = DashcamDatabase.get(this@MainActivity).videoDao()

                    videosToDelete.forEach { video ->
                        val file = File(video.localPath)
                        val deleted = !file.exists() || file.delete()
                        if (deleted) {
                            dao.delete(video)
                            deletedCount++
                        } else {
                            failedCount++
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (failedCount > 0) {
                            toast("Deleted $deletedCount videos; $failedCount failed")
                        } else {
                            toast("Deleted $deletedCount videos")
                        }
                        showLocalVideos()
                    }
                }
            }
            .show()
    }

    private fun statusRow(label: String) = TextView(this).apply {
        text = "$label: --"; textSize = 14f; setTextColor(Color.rgb(55, 65, 81));
        setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(Color.WHITE)
    }

    private fun actionButton(label: String, action: (View) -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener(action)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, -1, 1f)

    private fun requestStart() {
        if (PowerRecordingSettings.isPowerAutoBackgroundEnabled(this)) {
            toast("Turn off Power Auto Background before preview recording")
            return
        }
        if (PowerRecordingSettings.isVolumeKeyStartEnabled(this)) {
            toast("Turn off Volume Up Double-Press before preview recording")
            return
        }
        if (backgroundRecordingActive) {
            toast("Stop background recording first")
            return
        }
        pendingBackgroundStart = false
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startDashcam()
        else permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestBackgroundStart() {
        if (recording != null || continueRecording) {
            toast("Stop dashcam recording first")
            return
        }
        pendingBackgroundStart = true
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            pendingBackgroundStart = false
            startBackgroundDashcam()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startBackgroundDashcam() {
        if (recording != null || continueRecording) {
            toast("Stop dashcam recording first")
            return
        }
        backgroundRecordingActive = true
        PowerRecordingSettings.setBackgroundRecordingActive(this, true)
        updatePreviewAvailability()
        updateBackgroundRecordButton()
        updateRecordingStatus()
        mainHandler.postDelayed({
            if (!backgroundRecordingActive || recording != null || continueRecording) return@postDelayed
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, BackgroundRecordingService::class.java)
                        .setAction(BackgroundRecordingService.ACTION_START)
                )
            } catch (error: RuntimeException) {
                backgroundRecordingActive = false
                PowerRecordingSettings.setBackgroundRecordingActive(this, false)
                updatePreviewAvailability()
                updateBackgroundRecordButton()
                updateRecordingStatus()
                toast("Unable to start background recording")
            }
        }, backgroundCameraReleaseDelayMs())
        toast("Background recording starting")
    }

    private fun stopBackgroundDashcam() {
        startService(Intent(this, BackgroundRecordingService::class.java).setAction(BackgroundRecordingService.ACTION_STOP))
        backgroundRecordingActive = false
        PowerRecordingSettings.setBackgroundRecordingActive(this, false)
        updatePreviewAvailability()
        updateBackgroundRecordButton()
        updateRecordingStatus()
        toast("Stopping background recording")
    }

    private fun requestStopAfterCurrentSegment() {
        var handled = false
        if (recording != null || continueRecording) {
            stopAfterCurrentSegment = true
            updateRecordingStatus()
            handled = true
        }
        if (backgroundRecordingActive) {
            startService(
                Intent(this, BackgroundRecordingService::class.java)
                    .setAction(BackgroundRecordingService.ACTION_STOP_AFTER_SEGMENT)
            )
            handled = true
        }
        if (handled) toast("Power disconnected; stopping after current segment")
    }

    private fun togglePowerAutoBackground() {
        val enabled = !PowerRecordingSettings.isPowerAutoBackgroundEnabled(this)
        if (enabled) PowerRecordingSettings.setVolumeKeyStartEnabled(this, false)
        PowerRecordingSettings.setPowerAutoBackgroundEnabled(this, enabled)
        if (enabled) PowerMonitorService.start(this) else PowerMonitorService.stop(this)
        updateModeButtons()
        updatePreviewAvailability()
        updateRecordingStatus()
        toast(if (enabled) "Power auto background enabled" else "Power auto background disabled")
    }

    private fun toggleVolumeKeyStart() {
        val enabled = !PowerRecordingSettings.isVolumeKeyStartEnabled(this)
        if (enabled) {
            PowerRecordingSettings.setPowerAutoBackgroundEnabled(this, false)
            PowerMonitorService.stop(this)
        }
        PowerRecordingSettings.setVolumeKeyStartEnabled(this, enabled)
        updateModeButtons()
        updatePreviewAvailability()
        updateRecordingStatus()
        if (enabled && !isVolumeKeyAccessibilityEnabled()) {
            toast("Enable Dashcam Volume Up Double-Press in Accessibility settings")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            toast(if (enabled) "Volume up double-press enabled" else "Volume up double-press disabled")
        }
    }

    private fun updateModeButtons() {
        val powerEnabled = PowerRecordingSettings.isPowerAutoBackgroundEnabled(this)
        val volumeEnabled = PowerRecordingSettings.isVolumeKeyStartEnabled(this)
        if (::powerAutoButton.isInitialized) {
            powerAutoButton.text = powerAutoButtonLabel()
            powerAutoButton.isEnabled = powerEnabled || !volumeEnabled
        }
        if (::volumeKeyButton.isInitialized) {
            volumeKeyButton.text = volumeKeyButtonLabel()
            volumeKeyButton.isEnabled = volumeEnabled || !powerEnabled
        }
    }

    private fun powerAutoButtonLabel(): String =
        if (PowerRecordingSettings.isPowerAutoBackgroundEnabled(this)) {
            "Power Auto Background: ON"
        } else {
            "Power Auto Background: OFF"
        }

    private fun volumeKeyButtonLabel(): String =
        if (PowerRecordingSettings.isVolumeKeyStartEnabled(this)) {
            "Volume Up Double-Press: ON"
        } else {
            "Volume Up Double-Press: OFF"
        }

    private fun isVolumeKeyAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, VolumeKeyAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun updateBackgroundRecordButton() {
        if (::backgroundRecordButton.isInitialized) {
            backgroundRecordButton.text = if (backgroundRecordingActive) "Stop Background" else "Start Background"
            backgroundRecordButton.isEnabled = backgroundRecordingActive || (recording == null && !continueRecording)
        }
    }

    private fun startDashcam() {
        if (backgroundRecordingActive) {
            toast("Stop background recording first")
            return
        }
        // Monitoring-camera mode: start and stop are manual, so recording is no
        // longer blocked when the device is not charging.
        // if (!isCharging()) { toast("Connect power before starting the dashcam"); return }
        if (recording != null || continueRecording) return
        manualStartTime = System.currentTimeMillis()
        completedSegmentsSinceManualStart = 0
        overwrittenVideosSinceManualStart = 0
        stopAfterCurrentSegment = false
        continueRecording = true
        setRecordingPreference(true)
        renderRecording(true)
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.post(timerRunnable)
        startCameraAndSegment()
    }

    private fun startCameraAndSegment() {
        ensureCameraProvider {
            try {
                startSegment()
            } catch (error: Exception) {
                failRecording("Camera unavailable: ${error.message}")
            }
        }
    }

    private fun startPreviewOnly() {
        if (!::previewView.isInitialized ||
            recording != null ||
            continueRecording ||
            backgroundRecordingActive ||
            PowerRecordingSettings.isPowerAutoBackgroundEnabled(this) ||
            PowerRecordingSettings.isVolumeKeyStartEnabled(this) ||
            PowerRecordingSettings.isBackgroundRecordingActive(this)
        ) return
        ensureCameraProvider {
            if (recording != null ||
                continueRecording ||
                backgroundRecordingActive ||
                PowerRecordingSettings.isPowerAutoBackgroundEnabled(this) ||
                PowerRecordingSettings.isVolumeKeyStartEnabled(this) ||
                PowerRecordingSettings.isBackgroundRecordingActive(this)
            ) return@ensureCameraProvider
            try {
                bindCameraForPreview()
            } catch (_: Exception) {
            }
        }
    }

    private fun updatePreviewAvailability() {
        if (!::previewView.isInitialized) return
        val powerAutoEnabled = PowerRecordingSettings.isPowerAutoBackgroundEnabled(this)
        val backgroundActive = backgroundRecordingActive || PowerRecordingSettings.isBackgroundRecordingActive(this)
        val volumeKeyStartEnabled = PowerRecordingSettings.isVolumeKeyStartEnabled(this)
        if (powerAutoEnabled || volumeKeyStartEnabled || backgroundActive) {
            RecordingService.previewSurfaceProvider = null
            previewView.isEnabled = false
            previewView.alpha = 0.35f
            if (recording == null && !continueRecording) cameraProvider?.unbindAll()
            return
        }

        previewView.isEnabled = true
        previewView.alpha = 1f
        RecordingService.previewSurfaceProvider = previewView.surfaceProvider
        startPreviewOnly()
    }

    private fun backgroundCameraReleaseDelayMs(): Long =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) 900L else 200L

    private fun ensureCameraProvider(action: () -> Unit) {
        val existingProvider = cameraProvider
        if (existingProvider != null) {
            action()
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            action()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startSegment() {
        if (!continueRecording || recording != null) return
        val directory = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "dashcam").apply { mkdirs() }
        lifecycleScope.launch {
            val storage = withContext(Dispatchers.IO) { StoragePolicy.prepareForRecordingWithResult(this@MainActivity, directory) }
            overwrittenVideosSinceManualStart += storage.deletedCount
            updateRecordingStatus()
            if (!storage.canRecord) {
                failRecording("Storage full, recording stopped.")
                return@launch
            }

            try {
                val capture = bindCameraForRecording()
                segmentStart = System.currentTimeMillis()
                segmentDurationSeconds = 0
                updateRecordingStatus()
                val filename = SimpleDateFormat("'dashcam_'yyyyMMdd_HHmmss'.mp4'", Locale.US).format(Date(segmentStart))
                segmentFile = File(directory, filename)
                val options = FileOutputOptions.Builder(segmentFile!!).build()
                val pendingRecording = capture.output.prepareRecording(this@MainActivity, options)
                recording = if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    pendingRecording.withAudioEnabled()
                } else {
                    pendingRecording
                }
                    .start(cameraExecutor) { event -> handleVideoEvent(event) }
            } catch (error: Exception) {
                failRecording("Unable to start recording: ${error.message}")
            }
        }
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
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, preview, capture)
                    toast("Camera ready: $cameraName $qualityName")
                    return capture
                } catch (error: Exception) {
                    lastError = error
                }
            }
        }

        throw IllegalStateException("No supported camera recording combination: ${lastError?.message.orEmpty()}")
    }

    private fun bindCameraForPreview() {
        val provider = cameraProvider ?: return
        val cameras = listOf(
            "back" to CameraSelector.DEFAULT_BACK_CAMERA,
            "front" to CameraSelector.DEFAULT_FRONT_CAMERA
        )
        var lastError: Exception? = null

        for ((_, selector) in cameras) {
            if (!provider.hasCamera(selector)) continue
            try {
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview)
                return
            } catch (error: Exception) {
                lastError = error
            }
        }

        throw IllegalStateException("No supported preview camera: ${lastError?.message.orEmpty()}")
    }

    private fun handleVideoEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                updateSegmentDuration(event)
                runOnUiThread {
                    renderRecording(true)
                    toast("Recording segment started")
                }
                scheduleSegmentRotation()
            }
            is VideoRecordEvent.Status -> {
                updateSegmentDuration(event)
                runOnUiThread { updateRecordingStatus() }
            }
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
                        DashcamDatabase.get(this@MainActivity).videoDao().insert(
                            VideoEntity(
                                filename = finishedFile.name,
                                localPath = finishedFile.absolutePath,
                                startTime = startedAt,
                                endTime = endedAt,
                                durationSeconds = durationSeconds,
                                fileSizeBytes = finishedFile.length()
                            )
                        )
                        UploadWorker.enqueueNow(this@MainActivity)
                        withContext(Dispatchers.Main) {
                            if (continueRecording) completedSegmentsSinceManualStart += 1
                            if (stopAfterCurrentSegment) continueRecording = false
                            updateRecordingStatus()
                            toast("Saved ${finishedFile.name}")
                            afterSegmentFinalized()
                        }
                    }
                } else {
                    finishedFile?.delete()
                    val message = if (!continueRecording) {
                        "Stopped"
                    } else if (event.hasError()) {
                        "Recording failed: ${event.error} ${event.cause?.message.orEmpty()}".trim()
                    } else {
                        "Recording did not produce a playable video"
                    }
                    runOnUiThread {
                        if (stopAfterCurrentSegment) continueRecording = false
                        toast(message)
                        afterSegmentFinalized()
                    }
                }
            }
        }
    }

    private fun updateSegmentDuration(event: VideoRecordEvent) {
        segmentDurationSeconds = (event.recordingStats.recordedDurationNanos / 1_000_000_000L)
            .toInt()
            .coerceAtLeast(segmentDurationSeconds)
    }

    private fun scheduleSegmentRotation() {
        mainHandler.postDelayed({
            if (continueRecording) recording?.stop()
        }, SEGMENT_DURATION_MS)
    }

    private fun afterSegmentFinalized() {
        if (continueRecording) {
            startSegment()
        } else {
            stopAfterCurrentSegment = false
            cameraProvider?.unbindAll()
            setRecordingPreference(false)
            renderRecording(false)
            mainHandler.removeCallbacks(timerRunnable)
            updatePreviewAvailability()
        }
    }

    private fun stopDashcam(reason: String) {
        continueRecording = false
        stopAfterCurrentSegment = false
        mainHandler.removeCallbacksAndMessages(null)
        val current = recording
        if (current != null) {
            toast("Stopping and saving current segment")
            current.stop()
        } else {
            cameraProvider?.unbindAll()
            setRecordingPreference(false)
            renderRecording(false)
            mainHandler.removeCallbacks(timerRunnable)
            updatePreviewAvailability()
            toast(reason)
        }
    }

    private fun failRecording(message: String) {
        continueRecording = false
        stopAfterCurrentSegment = false
        mainHandler.removeCallbacks(timerRunnable)
        recording?.stop()
        cameraProvider?.unbindAll()
        setRecordingPreference(false)
        renderRecording(false)
        updatePreviewAvailability()
        toast(message)
    }

    private fun observeVideos() {
        lifecycleScope.launch {
            DashcamDatabase.get(this@MainActivity).videoDao().observeAll().collectLatest { items ->
                videos = items
                if (::adapter.isInitialized && showingVideoList) {
                    adapter.clear()
                    adapter.addAll(items.map(::formatVideo))
                }
                if (::storageStatus.isInitialized) {
                    updateStorageStatus()
                }
            }
        }
    }

    private fun updateStorageStatus() {
        if (!::storageStatus.isInitialized) return
        storageStatus.text = "Local Videos: ${videos.size} videos - ${formatBytes(videos.sumOf { it.fileSizeBytes })}"
    }

    private fun checkServer(showResult: Boolean = false) {
        saveServerUrl()
        serverStatus.text = "Home Server: Checking..."
        lifecycleScope.launch {
            val online = withContext(Dispatchers.IO) { ServerClient(serverUrl.text.toString()).health() }
            serverStatus.text = "Home Server: ${if (online) "Online" else "Offline"}"
            if (showResult) toast(if (online) "Upload queued (Wi-Fi only)" else "Server unreachable; videos kept for retry")
        }
    }

    private fun startManualUpload() {
        saveServerUrl()
        toast("Uploading...")
        lifecycleScope.launch {
            try {
                toast(UploadWorker.uploadManually(this@MainActivity))
            } catch (error: Exception) {
                toast(error.message ?: "Upload failed")
            }
        }
    }

    private fun saveServerUrl() {
        getSharedPreferences(UploadWorker.PREFS, MODE_PRIVATE).edit()
            .putString(UploadWorker.KEY_SERVER_URL, serverUrl.text.toString().trim()).apply()
    }

    private fun setRecordingPreference(active: Boolean) {
        PowerRecordingSettings.setForegroundRecordingActive(this, active)
    }

    private fun renderRecording(active: Boolean) {
        updateRecordingStatus(active)
    }

    private fun updateRecordingStatus(activeOverride: Boolean? = null) {
        if (!::recordingStatus.isInitialized) return
        val active = activeOverride ?: (recording != null || continueRecording)
        val status = if (active && stopAfterCurrentSegment) "Stopping after segment" else if (active) "Recording" else "Stopped"
        val elapsed = if ((recording != null || continueRecording) && segmentStart > 0) {
            formatDurationSeconds(segmentDurationSeconds)
        } else {
            "00:00"
        }
        val started = manualStartTime?.let {
            DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(it))
        } ?: "--"
        val backgroundStatus = if (backgroundRecordingActive) {
            val name = backgroundFilename?.let { " - $it" }.orEmpty()
            "\nBackground: Recording - ${formatDurationSeconds(backgroundElapsedSeconds)}$name"
        } else {
            "\nBackground: Stopped"
        }
        recordingStatus.text = "Recording: $status - Current segment: $elapsed\nStarted at: $started - Auto-generated clips: ${completedSegmentsSinceManualStart} - Overwritten clips: ${overwrittenVideosSinceManualStart}$backgroundStatus"
        if (::previewRecordButton.isInitialized) {
            previewRecordButton.text = if (active) "Stop Dashcam" else "Start Dashcam"
            previewRecordButton.isEnabled = active ||
                (!backgroundRecordingActive &&
                    !PowerRecordingSettings.isPowerAutoBackgroundEnabled(this) &&
                    !PowerRecordingSettings.isVolumeKeyStartEnabled(this))
        }
        updateBackgroundRecordButton()
        updateModeButtons()
    }

    private fun renderCharging(intent: Intent?) {
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        chargingStatus.text = "Power: ${if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) "Charging" else "Not Charging"}"
    }
    private fun isCharging(): Boolean {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun formatVideo(video: VideoEntity): String {
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.getDefault()).format(Date(video.startTime))
        val lock = if (video.locked) "LOCKED" else "NORMAL"
        val error = video.errorMessage?.let { "\n$it" }.orEmpty()
        return "$date  ${video.filename}\n${recordingSource(video)} - ${formatDurationSeconds(video.durationSeconds)} - ${formatBytes(video.fileSizeBytes)} - Recorded ${recordedOrientation(video)} - Playback ${effectivePlaybackRotation(video)}° - ${video.uploadStatus} - $lock$error"
    }

    private fun recordedOrientation(video: VideoEntity): String =
        recordedOrientationCache.getOrPut(video.localPath) {
            val file = File(video.localPath)
            if (!file.exists()) return@getOrPut "Unknown"
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    ?: return@getOrPut "Unknown"
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    ?: return@getOrPut "Unknown"
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val displayWidth = if (rotation == 90 || rotation == 270) height else width
                val displayHeight = if (rotation == 90 || rotation == 270) width else height
                if (displayWidth >= displayHeight) "Landscape" else "Portrait"
            } catch (_: Exception) {
                "Unknown"
            } finally {
                retriever.release()
            }
        }
    private fun recordingSource(video: VideoEntity): String =
        if (video.filename.startsWith("dashcam_bg_")) "Background" else "Foreground"
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        else -> "${bytes / 1024} KB"
    }
    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
        return formatDurationSeconds(totalSeconds.toInt())
    }
    private fun formatDurationSeconds(secondsValue: Int): String {
        val totalSeconds = secondsValue.coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun previewHeight(): Int {
        val horizontalPadding = dp(40)
        val availableWidth = (resources.displayMetrics.widthPixels - horizontalPadding).coerceAtLeast(dp(160))
        return (availableWidth * 9f / 16f).toInt()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SEGMENT_DURATION_MS = 3 * 60 * 1000L
    }
}
