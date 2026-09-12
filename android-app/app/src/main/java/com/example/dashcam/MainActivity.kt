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
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.dashcam.data.AudioEntity
import com.example.dashcam.data.BatteryTemperatureSample
import com.example.dashcam.data.DashcamDatabase
import com.example.dashcam.data.UploadStatus
import com.example.dashcam.data.VideoEntity
import com.example.dashcam.live.LiveAccessService
import com.example.dashcam.live.LiveAccessSettings
import com.example.dashcam.network.DeviceStatusReporter
import com.example.dashcam.network.ServerClient
import com.example.dashcam.recording.BackgroundRecordingService
import com.example.dashcam.recording.BackgroundVideoQuality
import com.example.dashcam.recording.BackgroundVideoQualitySettings
import com.example.dashcam.recording.AudioRecordingService
import com.example.dashcam.recording.AudioSegmentSettings
import com.example.dashcam.recording.AudioStoragePolicy
import com.example.dashcam.recording.PowerMonitorService
import com.example.dashcam.recording.PowerRecordingSettings
import com.example.dashcam.recording.RecordingService
import com.example.dashcam.recording.RecordingStartAlert
import com.example.dashcam.recording.RecordingStartAlertMode
import com.example.dashcam.recording.RecordingStartAlertSettings
import com.example.dashcam.recording.StoragePolicy
import com.example.dashcam.recording.StorageLimitSettings
import com.example.dashcam.recording.VideoSegmentSettings
import com.example.dashcam.recording.VolumeKeyAccessibilityService
import com.example.dashcam.upload.UploadWorker
import com.example.dashcam.battery.BatteryTemperatureChartView
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
import kotlin.math.abs

private class ScrollFriendlySpinner(context: Context) : Spinner(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var verticalDrag = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                verticalDrag = false
            }
            MotionEvent.ACTION_MOVE -> {
                val verticalDistance = abs(event.y - downY)
                val horizontalDistance = abs(event.x - downX)
                if (verticalDistance > touchSlop && verticalDistance > horizontalDistance) {
                    verticalDrag = true
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_UP -> if (verticalDrag) {
                val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.onTouchEvent(cancelEvent)
                cancelEvent.recycle()
                verticalDrag = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> verticalDrag = false
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = if (verticalDrag) false else super.performClick()
}

class MainActivity : ComponentActivity() {
    private lateinit var recordingStatus: TextView
    private lateinit var backgroundStatus: TextView
    private lateinit var audioStatus: TextView
    private lateinit var chargingStatus: TextView
    private lateinit var serverStatus: TextView
    private lateinit var storageStatus: TextView
    private lateinit var audioStorageStatus: TextView
    private lateinit var serverUrl: EditText
    private lateinit var serverUrlDisplay: TextView
    private lateinit var previewRecordButton: Button
    private lateinit var backgroundRecordButton: Button
    private lateinit var audioRecordButton: Button
    private lateinit var liveAccessButton: Button
    private lateinit var recordingModeSpinner: Spinner
    private lateinit var startAlertSpinner: Spinner
    private lateinit var backgroundVideoQualitySpinner: Spinner
    private lateinit var segmentDurationSpinner: Spinner
    private lateinit var audioSegmentDurationSpinner: Spinner
    private var suppressSegmentDurationSelection = false
    private var suppressAudioSegmentDurationSelection = false
    private lateinit var previewView: PreviewView
    private lateinit var previewContainer: FrameLayout
    private lateinit var previewFullscreenStatus: TextView
    private lateinit var compactStatusBar: TextView
    private lateinit var homeScroll: ScrollView
    private lateinit var homeRoot: LinearLayout
    private var previewAvailable = false
    private var previewFullscreen = false
    private val homeChildVisibility = mutableMapOf<View, Int>()
    private lateinit var videoList: ListView
    private lateinit var adapter: ArrayAdapter<VideoEntity>
    private var videos: List<VideoEntity> = emptyList()
    private var audioRecords: List<AudioEntity> = emptyList()
    private val recordedOrientationCache = mutableMapOf<String, String>()
    private var showingVideoManager = false
    private var showingVideoList = false
    private var showingAudioList = false
    private var showingBatteryHistory = false
    private var audioListLoadGeneration = 0
    private var returnToVideoListAfterManager = false
    private var restoreVideoListScroll = false
    private var videoListFirstVisiblePosition = 0
    private var videoListTopOffset = 0
    private var exitVideoFullscreen: (() -> Boolean)? = null
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
    private var pendingAudioStartAfterPermission = false
    private var editingServerUrl = false
    private var backgroundRecordingActive = false
    private var backgroundElapsedSeconds = 0
    private var backgroundFilename: String? = null
    private var audioRecordingActive = false
    private var audioElapsedSeconds = 0
    private var audioFilename: String? = null
    private var liveAccessEnabled = false
    private var liveStreaming = false
    private var liveError = ""
    private var audioPlayer: MediaPlayer? = null
    private var playingAudioPath: String? = null
    private lateinit var audioPlaybackTitle: TextView
    private lateinit var audioPlaybackTime: TextView
    private lateinit var audioPlaybackSeekBar: SeekBar
    private lateinit var audioPlaybackButton: ImageButton
    private var audioPlaybackSeeking = false
    private var stopAfterCurrentSegment = false
    private var foregroundStartAlertPending = false
    private var settingsExpanded = false
    private var serverOnline: Boolean? = null
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

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val startAfterPermission = pendingAudioStartAfterPermission
        pendingAudioStartAfterPermission = false
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            if (startAfterPermission) {
                startAudioRecording()
            } else if (PowerRecordingSettings.isVolumeKeyAudioStartEnabled(this) &&
                !isVolumeKeyAccessibilityEnabled()
            ) {
                toast("Enable Dashcam Volume Up Double-Press in Accessibility settings")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } else {
            toast("Microphone permission is required")
        }
    }
    private val livePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enableLiveAccess() else toast("Camera permission is required for Live Access")
    }

    private val audioPlaybackRunnable = object : Runnable {
        override fun run() {
            updateAudioPlaybackControls()
            if (showingAudioList && audioPlayer != null) mainHandler.postDelayed(this, 500)
        }
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
                    if (!liveStreaming &&
                        recording == null && !continueRecording && !backgroundRecordingActive &&
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
            if (backgroundRecordingActive && !wasActive) {
                cameraProvider?.unbindAll()
            } else if (!backgroundRecordingActive && wasActive) {
                updatePreviewAvailability()
            }
            updateBackgroundRecordButton()
            updateRecordingStatus()
        }
    }

    private val audioStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            audioRecordingActive = intent?.getBooleanExtra(AudioRecordingService.EXTRA_ACTIVE, false) == true
            audioElapsedSeconds = intent?.getIntExtra(AudioRecordingService.EXTRA_ELAPSED_SECONDS, 0) ?: 0
            audioFilename = intent?.getStringExtra(AudioRecordingService.EXTRA_FILENAME)
            updateRecordingStatus()
            intent?.getStringExtra(AudioRecordingService.EXTRA_MESSAGE)?.let(::toast)
        }
    }

    private val liveStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val wasStreaming = liveStreaming
            val previousError = liveError
            liveAccessEnabled = intent?.getBooleanExtra(LiveAccessService.EXTRA_ENABLED, false) == true
            liveStreaming = intent?.getBooleanExtra(LiveAccessService.EXTRA_STREAMING, false) == true
            liveError = intent?.getStringExtra(LiveAccessService.EXTRA_ERROR).orEmpty()
            if (liveStreaming && !wasStreaming) {
                RecordingService.previewSurfaceProvider = null
                cameraProvider?.unbindAll()
            } else if (!liveStreaming && wasStreaming) {
                updatePreviewAvailability()
            }
            if (liveError.isNotBlank() && liveError != previousError) toast(liveError)
            updateRecordingStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backgroundRecordingActive = PowerRecordingSettings.isBackgroundRecordingActive(this)
        audioRecordingActive = PowerRecordingSettings.isAudioRecordingActive(this)
        liveAccessEnabled = LiveAccessSettings.isEnabled(this)
        liveStreaming = LiveAccessSettings.isStreaming(this)
        liveError = LiveAccessSettings.error(this)
        val prefs = getSharedPreferences(UploadWorker.PREFS, MODE_PRIVATE)
        buildUi()
        if (liveAccessEnabled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            LiveAccessService.enable(this)
        }
        serverUrl.setText(prefs.getString(UploadWorker.KEY_SERVER_URL, UploadWorker.DEFAULT_SERVER_URL))
        setRecordingPreference(false)
        if (PowerRecordingSettings.isPowerAutoBackgroundEnabled(this)) PowerMonitorService.start(this)
        renderRecording(false)
        observeVideos()
        observeAudioRecords()
        syncExistingAudioFiles()
        checkServer()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (exitPreviewFullscreen()) return
                if (exitVideoFullscreen?.invoke() == true) return
                if (showingVideoManager) {
                    showingVideoManager = false
                    if (returnToVideoListAfterManager) showLocalVideos() else buildUi()
                } else if (showingVideoList) {
                    showingVideoList = false
                    buildUi()
                } else if (showingAudioList) {
                    showingAudioList = false
                    stopAudioPlayback()
                    buildUi()
                } else if (showingBatteryHistory) {
                    showingBatteryHistory = false
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
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(RecordingService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, backgroundStateReceiver, IntentFilter(BackgroundRecordingService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, audioStateReceiver, IntentFilter(AudioRecordingService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, liveStateReceiver, IntentFilter(LiveAccessService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        if (!showingVideoList && !showingVideoManager && !showingAudioList && !showingBatteryHistory) {
            refreshHomeStatus()
        } else {
            updateModeButtons()
            updatePreviewAvailability()
        }
    }

    override fun onStop() {
        try { unregisterReceiver(stateReceiver) } catch (_: IllegalArgumentException) { }
        try { unregisterReceiver(backgroundStateReceiver) } catch (_: IllegalArgumentException) { }
        try { unregisterReceiver(audioStateReceiver) } catch (_: IllegalArgumentException) { }
        try { unregisterReceiver(liveStateReceiver) } catch (_: IllegalArgumentException) { }
        try { unregisterReceiver(batteryReceiver) } catch (_: IllegalArgumentException) { }
        try { unregisterReceiver(powerReceiver) } catch (_: IllegalArgumentException) { }
        RecordingService.previewSurfaceProvider = null
        stopAudioPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        if (recording != null || continueRecording) stopDashcam("Activity closed")
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildUi() {
        previewFullscreen = false
        previewAvailable = false
        homeChildVisibility.clear()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        showingVideoList = false
        showingAudioList = false
        showingBatteryHistory = false
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
        backgroundStatus = statusRow("Background")
        audioStatus = statusRow("Audio")
        chargingStatus = statusRow("Power")
        serverStatus = statusRow("Home Server")
        storageStatus = statusRow("Local Videos")
        audioStorageStatus = statusRow("Local Audio")
        listOf(
            recordingStatus,
            backgroundStatus,
            audioStatus,
            chargingStatus,
            serverStatus
        ).forEach(root::addView)
        updateStorageStatus()
        updateAudioStorageStatus()

        compactStatusBar = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(31, 41, 55))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxLines = 2
        }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        previewFullscreenStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        previewContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            addView(
                previewFullscreenStatus,
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                    leftMargin = dp(12)
                    topMargin = dp(12)
                }
            )
            setOnClickListener { togglePreviewFullscreen() }
        }
        previewView.setOnClickListener { togglePreviewFullscreen() }
        root.addView(previewContainer, normalPreviewLayoutParams())
        homeScroll = scroll
        homeRoot = root
        updatePreviewAvailability()

        root.addView(actionButton("Storage Limits") {
            showStorageLimitDialog()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        root.addView(storageStatus)
        root.addView(audioStorageStatus)

        val settingsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        }

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
        settingsContainer.addView(serverUrlRow, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })

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
        audioRecordButton = actionButton(if (audioRecordingActive) "Stop Audio" else "Start Audio") {
            if (audioRecordingActive) stopAudioRecording() else requestAudioStart()
        }
        root.addView(audioRecordButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        liveAccessButton = actionButton("Live Access") {
            toggleLiveAccess()
        }
        root.addView(liveAccessButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        updateLiveAccessButton()
        root.addView(TextView(this).apply {
            text = "Recording Mode"
            textSize = 12f
            setTextColor(Color.rgb(75, 85, 99))
            setPadding(0, dp(14), 0, dp(5))
        })
        recordingModeSpinner = ScrollFriendlySpinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                RecordingMode.entries.map { it.label }
            )
            setSelection(currentRecordingMode().ordinal, false)
            setBackgroundColor(Color.WHITE)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedMode = RecordingMode.entries.getOrNull(position) ?: return
                    if (selectedMode != currentRecordingMode()) {
                        keepHomeScrollPosition { setRecordingMode(selectedMode) }
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(recordingModeSpinner, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(TextView(this).apply {
            text = "Start Alert"
            textSize = 12f
            setTextColor(Color.rgb(75, 85, 99))
            setPadding(0, dp(14), 0, dp(5))
        })
        startAlertSpinner = ScrollFriendlySpinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                RecordingStartAlertMode.entries.map { it.label }
            )
            setSelection(RecordingStartAlertSettings.mode(this@MainActivity).ordinal, false)
            setBackgroundColor(Color.WHITE)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedMode = RecordingStartAlertMode.entries.getOrNull(position) ?: return
                    if (selectedMode != RecordingStartAlertSettings.mode(this@MainActivity)) {
                        keepHomeScrollPosition {
                            RecordingStartAlertSettings.setMode(this@MainActivity, selectedMode)
                            toast("Start alert: ${selectedMode.label}")
                        }
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(startAlertSpinner, LinearLayout.LayoutParams(-1, dp(52)))
        settingsContainer.addView(TextView(this).apply {
            text = "Background Video Quality"
            textSize = 12f
            setTextColor(Color.rgb(75, 85, 99))
            setPadding(0, dp(14), 0, dp(5))
        })
        backgroundVideoQualitySpinner = ScrollFriendlySpinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                BackgroundVideoQuality.entries.map { it.label }
            )
            setSelection(BackgroundVideoQualitySettings.quality(this@MainActivity).ordinal, false)
            setBackgroundColor(Color.WHITE)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedQuality = BackgroundVideoQuality.entries.getOrNull(position) ?: return
                    if (selectedQuality != BackgroundVideoQualitySettings.quality(this@MainActivity)) {
                        keepHomeScrollPosition {
                            BackgroundVideoQualitySettings.setQuality(this@MainActivity, selectedQuality)
                            toast("Background video quality: ${selectedQuality.label}")
                        }
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        settingsContainer.addView(backgroundVideoQualitySpinner, LinearLayout.LayoutParams(-1, dp(52)))
        settingsContainer.addView(TextView(this).apply {
            text = "Video Segment Length"
            textSize = 12f
            setTextColor(Color.rgb(75, 85, 99))
            setPadding(0, dp(14), 0, dp(5))
        })
        segmentDurationSpinner = ScrollFriendlySpinner(this).apply {
            setBackgroundColor(Color.WHITE)
            configureSegmentDurationSpinner(this)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (suppressSegmentDurationSelection) return
                    keepHomeScrollPosition { selectSegmentDuration(position) }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        settingsContainer.addView(segmentDurationSpinner, LinearLayout.LayoutParams(-1, dp(52)))
        settingsContainer.addView(TextView(this).apply {
            text = "Audio Segment Length"
            textSize = 12f
            setTextColor(Color.rgb(75, 85, 99))
            setPadding(0, dp(14), 0, dp(5))
        })
        audioSegmentDurationSpinner = ScrollFriendlySpinner(this).apply {
            setBackgroundColor(Color.WHITE)
            configureAudioSegmentDurationSpinner(this)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (suppressAudioSegmentDurationSelection) return
                    keepHomeScrollPosition { selectAudioSegmentDuration(position) }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        settingsContainer.addView(audioSegmentDurationSpinner, LinearLayout.LayoutParams(-1, dp(52)))
        val secondaryControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        secondaryControls.addView(actionButton("Local Audio") {
            showLocalAudio()
        }, weighted())
        secondaryControls.addView(actionButton("Local Videos") {
            showLocalVideos()
        }, weighted().apply { marginStart = dp(8) })
        root.addView(secondaryControls, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        val settingsToggle = actionButton(
            if (settingsExpanded) "Hide Settings" else "Show Settings"
        ) {
            settingsExpanded = !settingsExpanded
            settingsContainer.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
            (it as Button).text = if (settingsExpanded) "Hide Settings" else "Show Settings"
        }
        root.addView(settingsToggle, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        root.addView(settingsContainer, LinearLayout.LayoutParams(-1, -2))
        val autoUploadControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        autoUploadControls.addView(actionButton(
            if (UploadWorker.isAutomaticUploadEnabled(this)) "Auto Upload: On" else "Auto Upload: Off"
        ) {
            keepHomeScrollPosition {
                val enabled = !UploadWorker.isAutomaticUploadEnabled(this)
                UploadWorker.setAutomaticUploadEnabled(this, enabled)
                toast("Automatic upload ${if (enabled) "enabled" else "disabled"}")
                buildUi()
            }
        }, LinearLayout.LayoutParams(-1, -1))
        settingsContainer.addView(autoUploadControls, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        settingsContainer.addView(actionButton("Upload Now") {
            startManualUpload()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        val uploadTypeControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        uploadTypeControls.addView(actionButton("Upload Audio Only") {
            startManualAudioUpload()
        }, weighted())
        uploadTypeControls.addView(actionButton("Upload Video Only") {
            startManualVideoUpload()
        }, weighted().apply { marginStart = dp(8) })
        settingsContainer.addView(uploadTypeControls, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        root.addView(actionButton("Battery Temperature") {
            showBatteryTemperatureHistory(24)
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        scroll.addView(root)
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 244, 240))
            addView(compactStatusBar, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(screen)
        refreshHomeStatus()
    }

    private fun showBatteryTemperatureHistory(hours: Int) {
        showingBatteryHistory = true
        showingVideoList = false
        showingAudioList = false
        showingVideoManager = false
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(244, 244, 240)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Battery temperature"
            textSize = 27f
            setTextColor(Color.rgb(17, 24, 39))
        })
        root.addView(TextView(this).apply {
            text = "Kept on this phone for 3 days. Tap or drag left/right across the chart to inspect a time."
            textSize = 13f
            setTextColor(Color.rgb(75, 85, 99))
            setPadding(0, dp(4), 0, dp(12))
        })
        val rangeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(8, 24, 72).forEach { range ->
            rangeRow.addView(actionButton(if (range == 72) "3 days" else "$range hours") {
                showBatteryTemperatureHistory(range)
            }, weighted().apply { if (range != 8) marginStart = dp(6) })
        }
        root.addView(rangeRow, LinearLayout.LayoutParams(-1, dp(46)))
        val summary = TextView(this).apply {
            text = "Loading…"
            textSize = 15f
            setTextColor(Color.rgb(31, 41, 55))
            setPadding(0, dp(14), 0, dp(10))
        }
        root.addView(summary)
        val chart = BatteryTemperatureChartView(this)
        root.addView(chart, LinearLayout.LayoutParams(-1, dp(300)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(actionButton("Back") {
            showingBatteryHistory = false
            buildUi()
        }, weighted())
        controls.addView(actionButton("Refresh") {
            showBatteryTemperatureHistory(hours)
        }, weighted().apply { marginStart = dp(8) })
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        scroll.addView(root)
        setContentView(scroll)

        lifecycleScope.launch {
            val samples = withContext(Dispatchers.IO) {
                DashcamDatabase.get(this@MainActivity).batteryTemperatureDao()
                    .samplesSince(System.currentTimeMillis() - hours * 60L * 60_000L)
            }
            if (!showingBatteryHistory) return@launch
            chart.setSamples(samples, hours)
            summary.text = batteryTemperatureSummary(samples)
        }
    }

    private fun batteryTemperatureSummary(samples: List<BatteryTemperatureSample>): String {
        if (samples.isEmpty()) return "No samples yet. The first sample is recorded when the app starts."
        val values = samples.map { it.temperatureTenthsC / 10.0 }
        val current = values.last()
        val latest = samples.last()
        val electrical = mutableListOf<String>()
        electrical += if (latest.isCharging) latest.chargingSource else "On battery"
        latest.currentNowMicroamps?.let {
            electrical += String.format(Locale.getDefault(), "%+.0f mA", it / 1_000.0)
        }
        latest.estimatedBatteryPowerMilliwatts?.let {
            electrical += String.format(Locale.getDefault(), "%+.2f W", it / 1_000.0)
        }
        latest.voltageMillivolts?.let {
            electrical += String.format(Locale.getDefault(), "%.2f V", it / 1_000.0)
        }
        return String.format(
            Locale.getDefault(),
            "Current %.1f°C   Minimum %.1f°C   Maximum %.1f°C   Average %.1f°C\nBattery %d%%   %s\n%d samples",
            current,
            values.minOrNull() ?: current,
            values.maxOrNull() ?: current,
            values.average(),
            latest.batteryLevel,
            electrical.joinToString("   "),
            values.size
        )
    }

    private fun showLocalVideos() {
        if (showingVideoList && ::videoList.isInitialized) rememberVideoListScroll()
        showingVideoList = true
        showingVideoManager = false
        returnToVideoListAfterManager = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(244, 244, 240))
        }

        val videoTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        videoTitleRow.addView(TextView(this).apply {
            text = "Local Videos"
            textSize = 24f
            setTextColor(Color.rgb(17, 24, 39))
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(videoTitleRow, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(3) })
        root.addView(TextView(this).apply {
            text = "${videos.size} videos - ${formatBytes(videos.sumOf { it.fileSizeBytes })} / ${formatBytes(StoragePolicy.maxVideoBytes(this@MainActivity))}"
            textSize = 14f
            setTextColor(Color.rgb(55, 65, 81))
            setPadding(0, 0, 0, dp(10))
        })

        adapter = createVideoListAdapter()
        videoList = ListView(this).apply {
            adapter = this@MainActivity.adapter
            dividerHeight = 1
            setOnItemClickListener { _, _, position, _ ->
                rememberVideoListScroll()
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
        adapter.addAll(videos)
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
        restoreVideoListScrollIfNeeded()
    }

    private fun showLocalAudio() {
        stopAudioPlayback()
        showingAudioList = true
        showingVideoList = false
        showingVideoManager = false
        val generation = ++audioListLoadGeneration
        showAudioLoading()
        val recordsByPath = audioRecords.associateBy { it.localPath }
        lifecycleScope.launch {
            val audioFiles = withContext(Dispatchers.IO) { loadAudioFiles(recordsByPath) }
            if (!showingAudioList || generation != audioListLoadGeneration) return@launch
            renderLocalAudioList(audioFiles)
        }
    }

    private fun showAudioLoading() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(244, 244, 240))
        }
        root.addView(TextView(this).apply {
            text = "Local Audio"
            textSize = 24f
            setTextColor(Color.rgb(17, 24, 39))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            topMargin = dp(24)
        })
        root.addView(TextView(this).apply {
            text = "Loading recordings..."
            textSize = 14f
            setTextColor(Color.rgb(75, 85, 99))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(actionButton("Back") {
            showingAudioList = false
            buildUi()
        }, LinearLayout.LayoutParams(-1, dp(52)))
        setContentView(root)
    }

    private fun renderLocalAudioList(audioFiles: List<AudioFileInfo>) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(244, 244, 240))
        }
        val audioTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        audioTitleRow.addView(TextView(this).apply {
            text = "Local Audio"
            textSize = 24f
            setTextColor(Color.rgb(17, 24, 39))
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(audioTitleRow, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(3) })
        root.addView(TextView(this).apply {
            text = "${audioFiles.size} recordings - ${formatBytes(audioFiles.sumOf { it.file.length() })} / ${formatBytes(AudioStoragePolicy.maxAudioBytes(this@MainActivity))}"
            textSize = 14f
            setTextColor(Color.rgb(55, 65, 81))
            setPadding(0, 0, 0, dp(10))
        })

        val audioList = ListView(this).apply {
            adapter = createAudioListAdapter(audioFiles)
            dividerHeight = 1
            setOnItemClickListener { _, _, position, _ ->
                toggleAudioPlayback(audioFiles[position].file)
            }
            setOnItemLongClickListener { _, _, position, _ ->
                showAudioActions(audioFiles[position])
                true
            }
        }
        root.addView(audioList, LinearLayout.LayoutParams(-1, 0, 1f))

        audioPlaybackTitle = TextView(this).apply {
            text = "No audio selected"
            textSize = 13f
            setTextColor(Color.rgb(31, 41, 55))
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        root.addView(audioPlaybackTitle, LinearLayout.LayoutParams(-1, dp(28)))
        val playbackBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        audioPlaybackButton = playbackIconButton(
            android.R.drawable.ic_media_play,
            "Play audio"
        ) {
            toggleCurrentAudioPlayback()
        }.apply { isEnabled = false }
        playbackBar.addView(audioPlaybackButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        playbackBar.addView(playbackIconButton(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop audio"
        ) {
            stopAudioPlayback()
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        audioPlaybackSeekBar = SeekBar(this).apply {
            max = 0
            progress = 0
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    try { audioPlayer?.seekTo(progress) } catch (_: IllegalStateException) { }
                    updateAudioPlaybackTime(progress, seekBar?.max ?: 0)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    audioPlaybackSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    audioPlaybackSeeking = false
                    updateAudioPlaybackControls()
                }
            })
        }
        root.addView(audioPlaybackSeekBar, LinearLayout.LayoutParams(-1, dp(36)))
        playbackBar.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        audioPlaybackTime = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 11f
            setTextColor(Color.rgb(75, 85, 99))
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setSingleLine(true)
        }
        playbackBar.addView(audioPlaybackTime, LinearLayout.LayoutParams(dp(104), dp(40)))
        root.addView(playbackBar, LinearLayout.LayoutParams(-1, dp(42)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(actionButton("Back") {
            stopAudioPlayback()
            showingAudioList = false
            buildUi()
        }, weighted())
        controls.addView(actionButton("Delete All") {
            confirmDeleteAllAudio(audioFiles)
        }, weighted().apply { marginStart = dp(8) })
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        setContentView(root)
    }

    private fun loadAudioFiles(recordsByPath: Map<String, AudioEntity>): List<AudioFileInfo> {
        val directory = audioDirectory()
        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
            .map { file ->
                val record = recordsByPath[file.absolutePath]
                val durationSeconds = record?.durationSeconds ?: readAudioDuration(file)
                AudioFileInfo(file, durationSeconds, record?.startTime ?: audioStartTime(file), record)
            }
            .sortedByDescending { it.startedAt }
    }

    private fun readAudioDuration(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L)
                .toInt()
        } catch (_: Exception) {
            0
        } finally {
            retriever.release()
        }
    }

    private fun audioDirectory(): File {
        val musicRoot = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
        return File(musicRoot, AudioRecordingService.AUDIO_DIRECTORY).apply { mkdirs() }
    }

    private fun audioStartTime(file: File): Long = try {
        SimpleDateFormat("'audio_'yyyyMMdd_HHmmss_SSS'.m4a'", Locale.US).parse(file.name)?.time
            ?: file.lastModified()
    } catch (_: Exception) {
        file.lastModified()
    }

    private fun formatAudioFile(audio: AudioFileInfo): String {
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.getDefault())
            .format(Date(audio.startedAt))
        val status = audio.record?.uploadStatus ?: UploadStatus.Pending
        val lock = if (audio.record?.locked == true) "LOCKED" else "NORMAL"
        val error = audio.record?.errorMessage?.let { "\n$it" }.orEmpty()
        return "$date  ${audio.file.name}\n${formatDurationSeconds(audio.durationSeconds)} - ${formatBytes(audio.file.length())} - $status - $lock$error"
    }

    private fun createAudioListAdapter(items: List<AudioFileInfo>) =
        object : ArrayAdapter<AudioFileInfo>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val audio = getItem(position) ?: return view
                view.text = formatAudioFile(audio)
                view.textSize = 14f
                view.setTextColor(Color.rgb(17, 24, 39))
                view.setPadding(dp(12), dp(10), dp(12), dp(10))
                view.setBackgroundColor(uploadStatusBackground(audio.record?.uploadStatus ?: UploadStatus.Pending))
                return view
            }
        }

    private fun showAudioActions(audio: AudioFileInfo) {
        val record = audio.record
        val lockAction = if (record?.locked == true) "Unlock" else "Lock"
        AlertDialog.Builder(this)
            .setTitle(audio.file.name)
            .setItems(arrayOf(lockAction, "Delete")) { _, which ->
                if (which == 0 && record != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        DashcamDatabase.get(this@MainActivity).audioDao().toggleLock(record.id)
                        withContext(Dispatchers.Main) { showLocalAudio() }
                    }
                } else if (which == 0) {
                    toast("Audio record is still being indexed")
                } else {
                    confirmDeleteAudio(audio.file)
                }
            }
            .show()
    }

    private fun toggleAudioPlayback(file: File) {
        val currentPlayer = audioPlayer
        if (playingAudioPath == file.absolutePath && currentPlayer != null) {
            try {
                if (currentPlayer.isPlaying) {
                    currentPlayer.pause()
                } else {
                    currentPlayer.start()
                }
                updateAudioPlaybackControls()
            } catch (_: IllegalStateException) {
                stopAudioPlayback()
                toast("Audio is still loading")
            }
            return
        }

        stopAudioPlayback()
        try {
            val player = MediaPlayer()
            audioPlayer = player
            playingAudioPath = file.absolutePath
            audioPlaybackTitle.text = file.name
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                if (audioPlayer === it) {
                    audioPlaybackSeekBar.max = it.duration.coerceAtLeast(0)
                    audioPlaybackSeekBar.isEnabled = true
                    audioPlaybackButton.isEnabled = true
                    it.start()
                    startAudioPlaybackUpdates()
                }
            }
            player.setOnCompletionListener {
                if (audioPlayer === it) stopAudioPlayback()
            }
            player.setOnErrorListener { _, _, _ ->
                stopAudioPlayback()
                toast("Unable to play ${file.name}")
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            stopAudioPlayback()
            toast("Unable to play ${file.name}")
        }
    }

    private fun stopAudioPlayback() {
        mainHandler.removeCallbacks(audioPlaybackRunnable)
        val player = audioPlayer
        audioPlayer = null
        playingAudioPath = null
        audioPlaybackSeeking = false
        try { player?.stop() } catch (_: IllegalStateException) { }
        player?.release()
        if (showingAudioList && ::audioPlaybackSeekBar.isInitialized) {
            audioPlaybackSeekBar.progress = 0
            audioPlaybackSeekBar.max = 0
            audioPlaybackSeekBar.isEnabled = false
            audioPlaybackTitle.text = "No audio selected"
            audioPlaybackTime.text = "00:00 / 00:00"
            audioPlaybackButton.setImageResource(android.R.drawable.ic_media_play)
            audioPlaybackButton.contentDescription = "Play audio"
            audioPlaybackButton.isEnabled = false
        }
    }

    private fun toggleCurrentAudioPlayback() {
        val player = audioPlayer ?: run {
            toast("Select an audio recording first")
            return
        }
        try {
            if (player.isPlaying) player.pause() else player.start()
            startAudioPlaybackUpdates()
        } catch (_: IllegalStateException) {
            toast("Audio is still loading")
        }
    }

    private fun startAudioPlaybackUpdates() {
        mainHandler.removeCallbacks(audioPlaybackRunnable)
        mainHandler.post(audioPlaybackRunnable)
    }

    private fun updateAudioPlaybackControls() {
        if (!showingAudioList || !::audioPlaybackSeekBar.isInitialized) return
        val player = audioPlayer ?: return
        try {
            val duration = player.duration.coerceAtLeast(0)
            val position = player.currentPosition.coerceIn(0, duration)
            audioPlaybackSeekBar.max = duration
            if (!audioPlaybackSeeking) audioPlaybackSeekBar.progress = position
            updateAudioPlaybackTime(position, duration)
            val playing = player.isPlaying
            audioPlaybackButton.setImageResource(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            audioPlaybackButton.contentDescription = if (playing) "Pause audio" else "Play audio"
        } catch (_: IllegalStateException) { }
    }

    private fun updateAudioPlaybackTime(positionMs: Int, durationMs: Int) {
        if (!::audioPlaybackTime.isInitialized) return
        audioPlaybackTime.text = "${formatDuration(positionMs.toLong())} / ${formatDuration(durationMs.toLong())}"
    }

    private fun confirmDeleteAudio(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete audio recording?")
            .setMessage(file.name)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (playingAudioPath == file.absolutePath) stopAudioPlayback()
                lifecycleScope.launch(Dispatchers.IO) {
                    val deleted = file.delete()
                    if (deleted) DashcamDatabase.get(this@MainActivity).audioDao().deleteByLocalPath(file.absolutePath)
                    withContext(Dispatchers.Main) {
                        toast(if (deleted) "Deleted ${file.name}" else "Unable to delete ${file.name}")
                        showLocalAudio()
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteAllAudio(audioFiles: List<AudioFileInfo>) {
        AlertDialog.Builder(this)
            .setTitle("Delete all audio recordings?")
            .setMessage("This will permanently delete ${audioFiles.size} saved audio recordings.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete All") { _, _ ->
                stopAudioPlayback()
                lifecycleScope.launch(Dispatchers.IO) {
                    val dao = DashcamDatabase.get(this@MainActivity).audioDao()
                    var deleted = 0
                    audioFiles.forEach {
                        if (it.file.delete()) {
                            dao.deleteByLocalPath(it.file.absolutePath)
                            deleted += 1
                        }
                    }
                    withContext(Dispatchers.Main) {
                        toast("Deleted $deleted audio recordings")
                        showLocalAudio()
                    }
                }
            }
            .show()
    }

    private fun createVideoListAdapter() =
        object : ArrayAdapter<VideoEntity>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val video = getItem(position) ?: return view
                view.text = formatVideo(video)
                view.textSize = 14f
                view.setTextColor(Color.rgb(17, 24, 39))
                view.setPadding(dp(12), dp(10), dp(12), dp(10))
                view.setBackgroundColor(uploadStatusBackground(video.uploadStatus))
                return view
            }
        }

    private fun rememberVideoListScroll() {
        if (!::videoList.isInitialized) return
        videoListFirstVisiblePosition = videoList.firstVisiblePosition
        videoListTopOffset = videoList.getChildAt(0)?.top ?: 0
        restoreVideoListScroll = true
    }

    private fun keepHomeScrollPosition(action: () -> Unit) {
        val scrollY = if (::homeScroll.isInitialized) homeScroll.scrollY else 0
        action()
        if (::homeScroll.isInitialized) {
            homeScroll.post { homeScroll.scrollTo(0, scrollY) }
        }
    }

    private fun restoreVideoListScrollIfNeeded() {
        if (!restoreVideoListScroll) return
        restoreVideoListScroll = false
        videoList.post {
            val position = videoListFirstVisiblePosition.coerceIn(0, videos.lastIndex.coerceAtLeast(0))
            videoList.setSelectionFromTop(position, videoListTopOffset)
        }
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

        val title = TextView(this).apply {
            text = video.filename
            textSize = 18f
            setTextColor(Color.rgb(17, 24, 39))
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(title)

        var currentRotation = effectivePlaybackRotation(video)
        val player = RotatableVideoPlayer(this).apply {
            setVideoPath(file.absolutePath)
            setRotationDegrees(currentRotation)
            start()
        }
        root.addView(player, LinearLayout.LayoutParams(-1, 0, 1f))

        val details = TextView(this).apply {
            val lock = if (video.locked) "LOCKED" else "NORMAL"
            text = "${recordingSource(video)} - ${formatDurationSeconds(video.durationSeconds)} - ${formatBytes(video.fileSizeBytes)} - Playback ${currentRotation}° - ${video.uploadStatus} - $lock\n${file.absolutePath}"
            textSize = 12f
            setTextColor(Color.rgb(55, 65, 81))
            setPadding(0, dp(10), 0, dp(10))
        }
        root.addView(details)

        player.setOnRotateClickListener {
            currentRotation = (currentRotation + 90) % 360
            player.setRotationDegrees(currentRotation)
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
        player.setOnFullscreenToggleListener { fullscreen ->
            title.visibility = if (fullscreen) View.GONE else View.VISIBLE
            details.visibility = if (fullscreen) View.GONE else View.VISIBLE
            controls.visibility = if (fullscreen) View.GONE else View.VISIBLE
            root.setPadding(if (fullscreen) 0 else dp(16), if (fullscreen) 0 else dp(16), if (fullscreen) 0 else dp(16), if (fullscreen) 0 else dp(16))
            window.decorView.systemUiVisibility = if (fullscreen) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
            exitVideoFullscreen = if (fullscreen) ({ player.exitFullscreen() }) else null
        }
        setContentView(root)
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

    private fun confirmDelete(video: VideoEntity, player: RotatableVideoPlayer) {
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
        if (liveStreaming) {
            toast("Stop Live streaming first")
            return
        }
        if (audioRecordingActive || PowerRecordingSettings.isAudioRecordingActive(this)) {
            toast("Stop audio recording first")
            return
        }
        if (PowerRecordingSettings.isPowerAutoBackgroundEnabled(this)) {
            toast("Turn off Power Auto Background before preview recording")
            return
        }
        if (PowerRecordingSettings.isVolumeKeyStartEnabled(this)) {
            toast("Select Frontend Recording before preview recording")
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
        if (liveStreaming) {
            toast("Stop Live streaming first")
            return
        }
        if (audioRecordingActive || PowerRecordingSettings.isAudioRecordingActive(this)) {
            toast("Stop audio recording first")
            return
        }
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
        if (audioRecordingActive || PowerRecordingSettings.isAudioRecordingActive(this)) {
            toast("Stop audio recording first")
            return
        }
        if (recording != null || continueRecording) {
            toast("Stop dashcam recording first")
            return
        }
        PowerRecordingSettings.setPowerAutoStartSuppressed(this, false)
        cameraProvider?.unbindAll()
        ContextCompat.startForegroundService(
            this, Intent(this, BackgroundRecordingService::class.java).setAction(BackgroundRecordingService.ACTION_START)
        )
        backgroundRecordingActive = true
        PowerRecordingSettings.setBackgroundRecordingActive(this, true)
        updateBackgroundRecordButton()
        toast("Background recording starting")
    }

    private fun stopBackgroundDashcam() {
        val suppressUntilPowerCycle =
            PowerRecordingSettings.isPowerAutoBackgroundEnabled(this) &&
                PowerRecordingSettings.isDeviceCharging(this)
        PowerRecordingSettings.setPowerAutoStartSuppressed(this, suppressUntilPowerCycle)
        startService(Intent(this, BackgroundRecordingService::class.java).setAction(BackgroundRecordingService.ACTION_STOP))
        updateBackgroundRecordButton()
        toast("Stopping background recording")
    }

    private fun requestAudioStart() {
        if (recording != null || continueRecording || backgroundRecordingActive ||
            PowerRecordingSettings.isBackgroundRecordingActive(this)
        ) {
            toast("Stop video recording first")
            return
        }
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startAudioRecording()
        } else {
            pendingAudioStartAfterPermission = true
            audioPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startAudioRecording() {
        if (liveStreaming) {
            toast("Stop Live streaming first")
            return
        }
        if (recording != null || continueRecording || backgroundRecordingActive ||
            PowerRecordingSettings.isBackgroundRecordingActive(this)
        ) {
            toast("Stop video recording first")
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, AudioRecordingService::class.java).setAction(AudioRecordingService.ACTION_START)
        )
        audioRecordingActive = true
        audioElapsedSeconds = 0
        audioFilename = null
        updateRecordingStatus()
        toast("Audio recording starting")
    }

    private fun stopAudioRecording() {
        startService(Intent(this, AudioRecordingService::class.java).setAction(AudioRecordingService.ACTION_STOP))
        toast("Stopping and saving current audio segment")
    }

    private fun requestStopAfterCurrentSegment() {
        var handled = false
        val unlimitedSegment = VideoSegmentSettings.durationMilliseconds(this) == null
        if (recording != null || continueRecording) {
            if (unlimitedSegment) {
                stopDashcam("Power disconnected")
            } else {
                stopAfterCurrentSegment = true
                updateRecordingStatus()
            }
            handled = true
        }
        if (backgroundRecordingActive) {
            startService(
                Intent(this, BackgroundRecordingService::class.java)
                    .setAction(BackgroundRecordingService.ACTION_STOP_AFTER_SEGMENT)
            )
            handled = true
        }
        if (handled) {
            toast(
                if (unlimitedSegment) "Power disconnected; stopping and saving current video"
                else "Power disconnected; stopping after current segment"
            )
        }
    }

    private fun setRecordingMode(mode: RecordingMode) {
        when (mode) {
            RecordingMode.Frontend -> {
                PowerRecordingSettings.setPowerAutoBackgroundEnabled(this, false)
                PowerRecordingSettings.setVolumeKeyStartEnabled(this, false)
                PowerRecordingSettings.setVolumeKeyAudioStartEnabled(this, false)
                PowerMonitorService.stop(this)
            }
            RecordingMode.PowerAuto -> {
                PowerRecordingSettings.setVolumeKeyStartEnabled(this, false)
                PowerRecordingSettings.setVolumeKeyAudioStartEnabled(this, false)
                PowerRecordingSettings.setPowerAutoBackgroundEnabled(this, true)
                PowerMonitorService.start(this)
            }
            RecordingMode.VolumeVideoDoublePress -> {
                PowerRecordingSettings.setPowerAutoBackgroundEnabled(this, false)
                PowerRecordingSettings.setVolumeKeyAudioStartEnabled(this, false)
                PowerRecordingSettings.setVolumeKeyStartEnabled(this, true)
                PowerMonitorService.stop(this)
            }
            RecordingMode.VolumeAudioDoublePress -> {
                PowerRecordingSettings.setPowerAutoBackgroundEnabled(this, false)
                PowerRecordingSettings.setVolumeKeyStartEnabled(this, false)
                PowerRecordingSettings.setVolumeKeyAudioStartEnabled(this, true)
                PowerMonitorService.stop(this)
            }
        }
        updateModeButtons()
        updatePreviewAvailability()
        updateRecordingStatus()
        if (mode == RecordingMode.VolumeAudioDoublePress &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAudioStartAfterPermission = false
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
            audioPermissionLauncher.launch(permissions.toTypedArray())
            return
        }
        if ((mode == RecordingMode.VolumeVideoDoublePress || mode == RecordingMode.VolumeAudioDoublePress) &&
            !isVolumeKeyAccessibilityEnabled()
        ) {
            toast("Enable Dashcam Volume Up Double-Press in Accessibility settings")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            toast("Recording mode: ${mode.label}")
        }
    }

    private fun updateModeButtons() {
        if (::recordingModeSpinner.isInitialized) {
            recordingModeSpinner.isEnabled = !liveStreaming
            val position = currentRecordingMode().ordinal
            if (recordingModeSpinner.selectedItemPosition != position) {
                recordingModeSpinner.setSelection(position, false)
            }
        }
        if (::startAlertSpinner.isInitialized) {
            val alertPosition = RecordingStartAlertSettings.mode(this).ordinal
            if (startAlertSpinner.selectedItemPosition != alertPosition) {
                startAlertSpinner.setSelection(alertPosition, false)
            }
        }
        if (::backgroundVideoQualitySpinner.isInitialized) {
            backgroundVideoQualitySpinner.isEnabled = !liveStreaming && !backgroundRecordingActive
            val qualityPosition = BackgroundVideoQualitySettings.quality(this).ordinal
            if (backgroundVideoQualitySpinner.selectedItemPosition != qualityPosition) {
                backgroundVideoQualitySpinner.setSelection(qualityPosition, false)
            }
        }
        if (::segmentDurationSpinner.isInitialized) {
            segmentDurationSpinner.isEnabled =
                !liveStreaming && recording == null && !continueRecording && !backgroundRecordingActive
            val position = segmentDurationPosition(VideoSegmentSettings.durationMinutes(this))
            if (segmentDurationSpinner.selectedItemPosition != position) {
                suppressSegmentDurationSelection = true
                segmentDurationSpinner.setSelection(position, false)
                segmentDurationSpinner.post { suppressSegmentDurationSelection = false }
            }
        }
        if (::audioSegmentDurationSpinner.isInitialized) {
            audioSegmentDurationSpinner.isEnabled = !liveStreaming && !audioRecordingActive
            val position = audioSegmentDurationPosition(AudioSegmentSettings.durationMinutes(this))
            if (audioSegmentDurationSpinner.selectedItemPosition != position) {
                suppressAudioSegmentDurationSelection = true
                audioSegmentDurationSpinner.setSelection(position, false)
                audioSegmentDurationSpinner.post { suppressAudioSegmentDurationSelection = false }
            }
        }
    }

    private fun configureSegmentDurationSpinner(spinner: Spinner) {
        suppressSegmentDurationSelection = true
        val minutes = VideoSegmentSettings.durationMinutes(this)
        val labels = SEGMENT_DURATION_CHOICES.map { choice ->
            if (choice.minutes == null && segmentDurationPosition(minutes) == CUSTOM_DURATION_POSITION) {
                "Custom ($minutes min)"
            } else {
                choice.label
            }
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(segmentDurationPosition(minutes), false)
        spinner.post { suppressSegmentDurationSelection = false }
    }

    private fun selectSegmentDuration(position: Int) {
        if (recording != null || continueRecording || backgroundRecordingActive) {
            configureSegmentDurationSpinner(segmentDurationSpinner)
            toast("Stop video recording before changing segment length")
            return
        }
        val choice = SEGMENT_DURATION_CHOICES.getOrNull(position) ?: return
        val minutes = choice.minutes
        if (minutes == null) {
            showCustomSegmentDurationDialog()
            return
        }
        if (minutes != VideoSegmentSettings.durationMinutes(this)) {
            VideoSegmentSettings.setDurationMinutes(this, minutes)
            toast("Video segment length: ${VideoSegmentSettings.displayLabel(this)}")
        }
    }

    private fun showCustomSegmentDurationDialog() {
        val current = VideoSegmentSettings.durationMinutes(this)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(if (current > 0) current.toString() else "15")
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Custom video segment length")
            .setMessage("Enter whole minutes from 1 to ${VideoSegmentSettings.MAX_CUSTOM_DURATION_MINUTES}.")
            .setView(input)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { _, _ -> configureSegmentDurationSpinner(segmentDurationSpinner) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = input.text.toString().toIntOrNull()
                if (minutes == null || minutes !in
                    VideoSegmentSettings.MIN_CUSTOM_DURATION_MINUTES..VideoSegmentSettings.MAX_CUSTOM_DURATION_MINUTES
                ) {
                    input.error = "Enter 1-${VideoSegmentSettings.MAX_CUSTOM_DURATION_MINUTES} minutes"
                    return@setOnClickListener
                }
                VideoSegmentSettings.setDurationMinutes(this, minutes)
                configureSegmentDurationSpinner(segmentDurationSpinner)
                toast("Video segment length: ${VideoSegmentSettings.displayLabel(this)}")
                dialog.dismiss()
            }
        }
        dialog.setOnCancelListener { configureSegmentDurationSpinner(segmentDurationSpinner) }
        dialog.show()
    }

    private fun segmentDurationPosition(minutes: Int): Int {
        val preset = SEGMENT_DURATION_CHOICES.indexOfFirst { it.minutes == minutes }
        return if (preset >= 0) preset else CUSTOM_DURATION_POSITION
    }

    private fun configureAudioSegmentDurationSpinner(spinner: Spinner) {
        suppressAudioSegmentDurationSelection = true
        val minutes = AudioSegmentSettings.durationMinutes(this)
        val labels = AUDIO_SEGMENT_DURATION_CHOICES.map { choice ->
            if (choice.minutes == null && audioSegmentDurationPosition(minutes) == CUSTOM_AUDIO_DURATION_POSITION) {
                "Custom ($minutes min)"
            } else {
                choice.label
            }
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(audioSegmentDurationPosition(minutes), false)
        spinner.post { suppressAudioSegmentDurationSelection = false }
    }

    private fun selectAudioSegmentDuration(position: Int) {
        if (audioRecordingActive) {
            configureAudioSegmentDurationSpinner(audioSegmentDurationSpinner)
            toast("Stop audio recording before changing segment length")
            return
        }
        val choice = AUDIO_SEGMENT_DURATION_CHOICES.getOrNull(position) ?: return
        val minutes = choice.minutes
        if (minutes == null) {
            showCustomAudioSegmentDurationDialog()
            return
        }
        if (minutes != AudioSegmentSettings.durationMinutes(this)) {
            AudioSegmentSettings.setDurationMinutes(this, minutes)
            toast("Audio segment length: ${AudioSegmentSettings.displayLabel(this)}")
        }
    }

    private fun showCustomAudioSegmentDurationDialog() {
        val current = AudioSegmentSettings.durationMinutes(this)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(if (current > 0) current.toString() else "30")
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Custom audio segment length")
            .setMessage("Enter whole minutes from 1 to ${AudioSegmentSettings.MAX_CUSTOM_DURATION_MINUTES}.")
            .setView(input)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { _, _ -> configureAudioSegmentDurationSpinner(audioSegmentDurationSpinner) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = input.text.toString().toIntOrNull()
                if (minutes == null || minutes !in
                    AudioSegmentSettings.MIN_CUSTOM_DURATION_MINUTES..AudioSegmentSettings.MAX_CUSTOM_DURATION_MINUTES
                ) {
                    input.error = "Enter 1-${AudioSegmentSettings.MAX_CUSTOM_DURATION_MINUTES} minutes"
                    return@setOnClickListener
                }
                AudioSegmentSettings.setDurationMinutes(this, minutes)
                configureAudioSegmentDurationSpinner(audioSegmentDurationSpinner)
                toast("Audio segment length: ${AudioSegmentSettings.displayLabel(this)}")
                dialog.dismiss()
            }
        }
        dialog.setOnCancelListener { configureAudioSegmentDurationSpinner(audioSegmentDurationSpinner) }
        dialog.show()
    }

    private fun showStorageLimitDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        val videoInput = storageLimitInput(StoragePolicy.maxVideoBytes(this))
        val audioInput = storageLimitInput(AudioStoragePolicy.maxAudioBytes(this))
        content.addView(TextView(this).apply { text = "Video storage limit (GiB)" })
        content.addView(videoInput, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(TextView(this).apply {
            text = "Audio storage limit (GiB)"
            setPadding(0, dp(12), 0, 0)
        })
        content.addView(audioInput, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(TextView(this).apply {
            val recommendedBytes = recommendedCombinedStorageBytes()
            text = "Recommended combined maximum: ${formatLimitGiB(recommendedBytes)} GiB\n" +
                "Video + audio limits should add up to no more than this value. Keeps 1 GiB free."
            textSize = 13f
            setTextColor(Color.rgb(75, 85, 99))
            setPadding(0, dp(14), 0, dp(4))
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Local Storage Limits")
            .setMessage(
                "Changing these limits does not delete or clean up existing files now.\n\n" +
                    "Video: checked before each segment starts.\n" +
                    "Audio: checked when audio recording starts and after each segment is saved."
            )
            .setView(content)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val videoGiB = parseGiB(videoInput)
                val audioGiB = parseGiB(audioInput)
                if (videoGiB == null) {
                    videoInput.error = "Enter a number greater than 0"
                    return@setOnClickListener
                }
                if (audioGiB == null) {
                    audioInput.error = "Enter a number greater than 0"
                    return@setOnClickListener
                }
                StorageLimitSettings.setLimitsGiB(this, videoGiB, audioGiB)
                updateStorageStatus()
                updateAudioStorageStatus()
                toast("Storage limits saved")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun storageLimitInput(bytes: Long) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine(true)
        setText(formatLimitGiB(bytes))
        setSelection(text.length)
    }

    private fun parseGiB(input: EditText): Double? =
        input.text.toString().trim().replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }

    private fun formatLimitGiB(bytes: Long): String {
        val value = bytes.toDouble() / StorageLimitSettings.BYTES_PER_GIB.toDouble()
        return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }

    private fun recommendedCombinedStorageBytes(): Long {
        val videoDirectory = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "dashcam")
        val audioDirectory = audioDirectory()
        val currentMediaBytes = directoryBytes(videoDirectory) + directoryBytes(audioDirectory)
        val storageRoot = getExternalFilesDir(null) ?: filesDir
        val additionalBytes = (storageRoot.usableSpace - StorageLimitSettings.BYTES_PER_GIB).coerceAtLeast(0L)
        return currentMediaBytes + additionalBytes.coerceAtMost(Long.MAX_VALUE - currentMediaBytes)
    }

    private fun directoryBytes(directory: File): Long =
        directory.listFiles().orEmpty()
            .filter(File::isFile)
            .sumOf(File::length)

    private fun audioSegmentDurationPosition(minutes: Int): Int {
        val preset = AUDIO_SEGMENT_DURATION_CHOICES.indexOfFirst { it.minutes == minutes }
        return if (preset >= 0) preset else CUSTOM_AUDIO_DURATION_POSITION
    }

    private fun currentRecordingMode(): RecordingMode = when {
        PowerRecordingSettings.isPowerAutoBackgroundEnabled(this) -> RecordingMode.PowerAuto
        PowerRecordingSettings.isVolumeKeyStartEnabled(this) -> RecordingMode.VolumeVideoDoublePress
        PowerRecordingSettings.isVolumeKeyAudioStartEnabled(this) -> RecordingMode.VolumeAudioDoublePress
        else -> RecordingMode.Frontend
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
            backgroundRecordButton.isEnabled = !liveStreaming && (
                backgroundRecordingActive ||
                    (recording == null && !continueRecording && !audioRecordingActive)
                )
        }
    }

    private fun toggleLiveAccess() {
        if (LiveAccessSettings.isEnabled(this)) {
            LiveAccessService.disable(this)
            liveAccessEnabled = false
            liveStreaming = false
            liveError = ""
            updateLiveAccessButton()
            updateRecordingStatus()
            toast("Live Access disabled")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            enableLiveAccess()
        } else {
            livePermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun enableLiveAccess() {
        LiveAccessSettings.setEnabled(this, true)
        liveAccessEnabled = true
        liveError = ""
        LiveAccessService.enable(this)
        updateLiveAccessButton()
        toast("Live Access enabled")
    }

    private fun updateLiveAccessButton() {
        if (!::liveAccessButton.isInitialized) return
        liveAccessEnabled = LiveAccessSettings.isEnabled(this)
        liveStreaming = LiveAccessSettings.isStreaming(this)
        liveError = LiveAccessSettings.error(this)
        liveAccessButton.text = when {
            liveStreaming -> "Live: Streaming"
            liveAccessEnabled && liveError.isNotBlank() -> "Live Access: On (Unavailable)"
            liveAccessEnabled -> "Live Access: On"
            else -> "Live Access: Off"
        }
    }

    private fun startDashcam() {
        if (audioRecordingActive || PowerRecordingSettings.isAudioRecordingActive(this)) {
            toast("Stop audio recording first")
            return
        }
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
        foregroundStartAlertPending = true
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
        val volumeKeyStartEnabled = PowerRecordingSettings.isVolumeKeyStartEnabled(this)
        if (powerAutoEnabled || volumeKeyStartEnabled || liveStreaming) {
            previewAvailable = false
            exitPreviewFullscreen()
            RecordingService.previewSurfaceProvider = null
            previewView.isEnabled = false
            previewView.alpha = 0.35f
            if (recording == null && !continueRecording) cameraProvider?.unbindAll()
            return
        }

        previewAvailable = true
        previewView.isEnabled = true
        previewView.alpha = 1f
        RecordingService.previewSurfaceProvider = previewView.surfaceProvider
        startPreviewOnly()
    }

    private fun normalPreviewLayoutParams() =
        LinearLayout.LayoutParams(-1, previewHeight()).apply { topMargin = dp(14) }

    private fun togglePreviewFullscreen() {
        if (previewFullscreen) {
            exitPreviewFullscreen()
        } else {
            enterPreviewFullscreen()
        }
    }

    private fun enterPreviewFullscreen() {
        if (!previewAvailable || !::homeRoot.isInitialized || !::previewContainer.isInitialized) return
        previewFullscreen = true
        if (::compactStatusBar.isInitialized) compactStatusBar.visibility = View.GONE
        homeChildVisibility.clear()
        for (index in 0 until homeRoot.childCount) {
            val child = homeRoot.getChildAt(index)
            if (child !== previewContainer) {
                homeChildVisibility[child] = child.visibility
                child.visibility = View.GONE
            }
        }
        homeRoot.setPadding(0, 0, 0, 0)
        previewContainer.layoutParams = LinearLayout.LayoutParams(-1, resources.displayMetrics.heightPixels)
        homeScroll.isFillViewport = true
        homeScroll.scrollTo(0, 0)
        previewFullscreenStatus.visibility = View.VISIBLE
        updatePreviewFullscreenStatus()
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun exitPreviewFullscreen(): Boolean {
        if (!previewFullscreen || !::homeRoot.isInitialized || !::previewContainer.isInitialized) return false
        previewFullscreen = false
        homeChildVisibility.forEach { (child, visibility) -> child.visibility = visibility }
        homeChildVisibility.clear()
        homeRoot.setPadding(dp(20), dp(18), dp(20), dp(18))
        previewContainer.layoutParams = normalPreviewLayoutParams()
        previewFullscreenStatus.visibility = View.GONE
        homeScroll.isFillViewport = false
        if (::compactStatusBar.isInitialized) compactStatusBar.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        return true
    }

    private fun updatePreviewFullscreenStatus() {
        if (!previewFullscreen || !::previewFullscreenStatus.isInitialized) return
        val active = recording != null || continueRecording
        previewFullscreenStatus.text = if (active) {
            "● REC ${formatDurationSeconds(segmentDurationSeconds)}"
        } else {
            "FULL SCREEN PREVIEW"
        }
        previewFullscreenStatus.setTextColor(if (active) Color.rgb(255, 92, 92) else Color.WHITE)
    }

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
                    if (foregroundStartAlertPending) {
                        foregroundStartAlertPending = false
                        RecordingStartAlert.show(this)
                    }
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
        val durationMs = VideoSegmentSettings.durationMilliseconds(this) ?: return
        mainHandler.postDelayed({
            if (continueRecording) recording?.stop()
        }, durationMs)
    }

    private fun afterSegmentFinalized() {
        if (continueRecording) {
            startSegment()
        } else {
            stopAfterCurrentSegment = false
            cameraProvider?.unbindAll()
            setRecordingPreference(false)
            UploadWorker.enqueueNow(this)
            renderRecording(false)
            mainHandler.removeCallbacks(timerRunnable)
            updatePreviewAvailability()
        }
    }

    private fun stopDashcam(reason: String) {
        continueRecording = false
        foregroundStartAlertPending = false
        stopAfterCurrentSegment = false
        mainHandler.removeCallbacksAndMessages(null)
        val current = recording
        if (current != null) {
            toast("Stopping and saving current segment")
            current.stop()
        } else {
            cameraProvider?.unbindAll()
            setRecordingPreference(false)
            UploadWorker.enqueueNow(this)
            renderRecording(false)
            mainHandler.removeCallbacks(timerRunnable)
            updatePreviewAvailability()
            toast(reason)
        }
    }

    private fun failRecording(message: String) {
        continueRecording = false
        foregroundStartAlertPending = false
        stopAfterCurrentSegment = false
        mainHandler.removeCallbacks(timerRunnable)
        recording?.stop()
        cameraProvider?.unbindAll()
        setRecordingPreference(false)
        UploadWorker.enqueueNow(this)
        renderRecording(false)
        updatePreviewAvailability()
        toast(message)
    }

    private fun observeVideos() {
        lifecycleScope.launch {
            DashcamDatabase.get(this@MainActivity).videoDao().observeAll().collectLatest { items ->
                videos = items
                if (::adapter.isInitialized && showingVideoList) {
                    rememberVideoListScroll()
                    adapter.clear()
                    adapter.addAll(items)
                    restoreVideoListScrollIfNeeded()
                }
                if (::storageStatus.isInitialized) {
                    updateStorageStatus()
                }
                updateCompactStatusBar()
            }
        }
    }

    private fun updateStorageStatus() {
        if (!::storageStatus.isInitialized) return
        storageStatus.text = "Local Videos: ${videos.size} videos - ${formatBytes(videos.sumOf { it.fileSizeBytes })} / ${formatBytes(StoragePolicy.maxVideoBytes(this))}"
    }

    private fun updateAudioStorageStatus() {
        if (!::audioStorageStatus.isInitialized) return
        audioStorageStatus.text =
            "Local Audio: ${audioRecords.size} recordings - ${formatBytes(audioRecords.sumOf { it.fileSizeBytes })} / ${formatBytes(AudioStoragePolicy.maxAudioBytes(this))}"
    }

    private fun observeAudioRecords() {
        lifecycleScope.launch {
            DashcamDatabase.get(this@MainActivity).audioDao().observeAll().collectLatest { items ->
                audioRecords = items
                updateAudioStorageStatus()
                updateCompactStatusBar()
            }
        }
    }

    private fun syncExistingAudioFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = DashcamDatabase.get(this@MainActivity).audioDao()
            var added = false
            audioDirectory().listFiles().orEmpty()
                .filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
                .forEach { file ->
                    if (dao.findByLocalPath(file.absolutePath) != null) return@forEach
                    val retriever = MediaMetadataRetriever()
                    val durationSeconds = try {
                        retriever.setDataSource(file.absolutePath)
                        ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt()
                    } catch (_: Exception) {
                        0
                    } finally {
                        retriever.release()
                    }
                    val startedAt = audioStartTime(file)
                    dao.insert(
                        AudioEntity(
                            filename = file.name,
                            localPath = file.absolutePath,
                            startTime = startedAt,
                            endTime = startedAt + durationSeconds * 1000L,
                            durationSeconds = durationSeconds,
                            fileSizeBytes = file.length()
                        )
                    )
                    added = true
                }
            if (added) UploadWorker.enqueueNow(this@MainActivity)
        }
    }

    private fun checkServer(showResult: Boolean = false) {
        saveServerUrl()
        serverStatus.text = "Home Server: Checking..."
        serverOnline = null
        updateCompactStatusBar()
        lifecycleScope.launch {
            val online = withContext(Dispatchers.IO) {
                DeviceStatusReporter.reportNow(this@MainActivity) != null
            }
            serverOnline = online
            serverStatus.text = "Home Server: ${if (online) "Online" else "Offline"}"
            updateCompactStatusBar()
            if (showResult) toast(if (online) "Upload queued (Wi-Fi only)" else "Server unreachable; videos kept for retry")
        }
    }

    private fun startManualUpload() {
        saveServerUrl()
        val workId = UploadWorker.enqueueManual(this)
        observeManualUpload(workId, "Upload started (Wi-Fi only)")
    }

    private fun startManualAudioUpload() {
        saveServerUrl()
        val workId = UploadWorker.enqueueManualAudio(this)
        observeManualUpload(workId, "Audio upload started (Wi-Fi only)")
    }

    private fun startManualVideoUpload() {
        saveServerUrl()
        val workId = UploadWorker.enqueueManualVideo(this)
        observeManualUpload(workId, "Video upload started (Wi-Fi only)")
    }

    private fun observeManualUpload(workId: java.util.UUID, startedMessage: String) {
        toast(startedMessage)
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(workId).observe(this) { info ->
            when (info?.state) {
                WorkInfo.State.SUCCEEDED -> toast(info.outputData.getString(UploadWorker.KEY_MESSAGE) ?: "Upload complete")
                WorkInfo.State.FAILED -> toast(info.outputData.getString(UploadWorker.KEY_ERROR) ?: "Upload failed")
                WorkInfo.State.CANCELLED -> toast("Upload cancelled")
                else -> Unit
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

    private fun refreshHomeStatus() {
        if (!::recordingStatus.isInitialized) return
        backgroundRecordingActive = PowerRecordingSettings.isBackgroundRecordingActive(this)
        if (!backgroundRecordingActive) {
            backgroundElapsedSeconds = 0
            backgroundFilename = null
        }
        audioRecordingActive = PowerRecordingSettings.isAudioRecordingActive(this)
        if (!audioRecordingActive) {
            audioElapsedSeconds = 0
            audioFilename = null
        }
        updateStorageStatus()
        updateAudioStorageStatus()
        renderCharging(currentBatteryIntent())
        updateRecordingStatus()
        renderAudioStatus()
        if (::previewView.isInitialized) updatePreviewAvailability()
        queryBackgroundRecordingState()
        queryAudioRecordingState()
        if (::serverStatus.isInitialized && ::serverUrl.isInitialized) checkServer()
    }

    private fun queryBackgroundRecordingState() {
        startService(
            Intent(this, BackgroundRecordingService::class.java)
                .setAction(BackgroundRecordingService.ACTION_QUERY_STATE)
        )
    }

    private fun queryAudioRecordingState() {
        startService(
            Intent(this, AudioRecordingService::class.java)
                .setAction(AudioRecordingService.ACTION_QUERY_STATE)
        )
    }

    private fun renderAudioStatus() {
        if (::audioStatus.isInitialized) {
            audioStatus.text = if (audioRecordingActive) {
                val currentFile = audioFilename?.let { "\nCurrent: $it" }.orEmpty()
                "Audio: Recording - ${formatDurationSeconds(audioElapsedSeconds)}$currentFile"
            } else {
                "Audio: Stopped"
            }
        }
        if (::audioRecordButton.isInitialized) {
            audioRecordButton.text = if (audioRecordingActive) "Stop Audio" else "Start Audio"
            audioRecordButton.isEnabled = !liveStreaming && (
                audioRecordingActive ||
                    (recording == null && !continueRecording && !backgroundRecordingActive)
                )
        }
        updateBackgroundRecordButton()
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
        recordingStatus.text = if (active) {
            val details = buildList {
                add("Completed: $completedSegmentsSinceManualStart")
                segmentFile?.name?.let { add("Current: $it") }
            }.joinToString(" - ")
            "Recording: $status - $elapsed\n$details"
        } else {
            "Recording: Stopped"
        }
        if (::backgroundStatus.isInitialized) {
            backgroundStatus.text = if (backgroundRecordingActive) {
                val currentFile = backgroundFilename?.let { "\nCurrent: $it" }.orEmpty()
                "Background: Recording - ${formatDurationSeconds(backgroundElapsedSeconds)}$currentFile"
            } else {
                "Background: Stopped"
            }
        }
        if (::previewRecordButton.isInitialized) {
            previewRecordButton.text = if (active) "Stop Dashcam" else "Start Dashcam"
            previewRecordButton.isEnabled = !liveStreaming && (
                active ||
                    (!backgroundRecordingActive &&
                        !audioRecordingActive &&
                        !PowerRecordingSettings.isPowerAutoBackgroundEnabled(this) &&
                        !PowerRecordingSettings.isVolumeKeyStartEnabled(this))
                )
        }
        updatePreviewFullscreenStatus()
        updateBackgroundRecordButton()
        renderAudioStatus()
        updateLiveAccessButton()
        updateModeButtons()
        updateCompactStatusBar()
    }

    private fun renderCharging(intent: Intent?) {
        if (!::chargingStatus.isInitialized) return
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        chargingStatus.text = "Power: ${if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) "Charging" else "Not Charging"}"
        updateCompactStatusBar(intent)
    }

    private fun updateCompactStatusBar(batteryIntent: Intent? = currentBatteryIntent()) {
        if (!::compactStatusBar.isInitialized) return
        val video = when {
            recording != null || continueRecording -> "Video: REC"
            backgroundRecordingActive -> "Video: BG REC"
            else -> "Video: Idle"
        }
        val audio = if (audioRecordingActive) "Audio: REC" else "Audio: Idle"
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
        val power = if (charging) "Charging" else "Battery"
        val server = when (serverOnline) {
            true -> "Online"
            false -> "Offline"
            null -> "Checking"
        }
        val pending = videos.count { it.uploadStatus != UploadStatus.Uploaded } +
            audioRecords.count { it.uploadStatus != UploadStatus.Uploaded }
        compactStatusBar.text = "$video   •   $audio\n$power${level?.let { " $it%" }.orEmpty()}   •   Server: $server   •   Pending: $pending"
    }
    private fun currentBatteryIntent(): Intent? =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun isCharging(): Boolean {
        val intent = currentBatteryIntent()
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun formatVideo(video: VideoEntity): String {
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.getDefault()).format(Date(video.startTime))
        val lock = if (video.locked) "LOCKED" else "NORMAL"
        val error = video.errorMessage?.let { "\n$it" }.orEmpty()
        return "$date  ${video.filename}\n${recordingSource(video)} - ${formatDurationSeconds(video.durationSeconds)} - ${formatBytes(video.fileSizeBytes)} - Recorded ${recordedOrientation(video)} - Playback ${effectivePlaybackRotation(video)}° - ${video.uploadStatus} - $lock$error"
    }

    private fun uploadStatusBackground(status: UploadStatus): Int =
        when (status) {
            UploadStatus.Pending -> Color.rgb(254, 243, 199)
            UploadStatus.Uploading -> Color.rgb(219, 234, 254)
            UploadStatus.Uploaded -> Color.rgb(220, 252, 231)
            UploadStatus.Failed -> Color.rgb(254, 226, 226)
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
    private fun playbackIconButton(icon: Int, description: String, action: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = description
            tooltipText = description
            setColorFilter(Color.rgb(31, 41, 55))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { action() }
        }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val SEGMENT_DURATION_CHOICES = listOf(
            SegmentDurationChoice("1 minute", 1),
            SegmentDurationChoice("3 minutes", 3),
            SegmentDurationChoice("5 minutes", 5),
            SegmentDurationChoice("10 minutes", 10),
            SegmentDurationChoice("Unlimited", VideoSegmentSettings.UNLIMITED_DURATION_MINUTES),
            SegmentDurationChoice("Custom...", null)
        )
        private const val CUSTOM_DURATION_POSITION = 5
        private val AUDIO_SEGMENT_DURATION_CHOICES = listOf(
            SegmentDurationChoice("5 minutes", 5),
            SegmentDurationChoice("10 minutes", 10),
            SegmentDurationChoice("15 minutes", 15),
            SegmentDurationChoice("30 minutes", 30),
            SegmentDurationChoice("60 minutes", 60),
            SegmentDurationChoice("Unlimited", AudioSegmentSettings.UNLIMITED_DURATION_MINUTES),
            SegmentDurationChoice("Custom...", null)
        )
        private const val CUSTOM_AUDIO_DURATION_POSITION = 6
    }

    private data class SegmentDurationChoice(val label: String, val minutes: Int?)

    private enum class RecordingMode(val label: String) {
        Frontend("Frontend Recording"),
        PowerAuto("Power Auto Background"),
        VolumeVideoDoublePress("Volume Up Double-Press Video"),
        VolumeAudioDoublePress("Volume Up Double-Press Audio")
    }

    private data class AudioFileInfo(
        val file: File,
        val durationSeconds: Int,
        val startedAt: Long,
        val record: AudioEntity?
    )
}
