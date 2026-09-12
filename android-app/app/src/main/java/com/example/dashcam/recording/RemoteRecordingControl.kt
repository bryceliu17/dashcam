package com.example.dashcam.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.dashcam.live.LiveAccessSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

object RemoteRecordingControl {
    // Only the phone UI may grant permission. Remote messages never change this setting.
    fun isEnabled(context: Context): Boolean = context.getSharedPreferences("dashcam_settings", Context.MODE_PRIVATE)
        .getBoolean("remote_control_enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("dashcam_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("remote_control_enabled", enabled).apply()
    }

    @Volatile var recorderStarted = false
    @Volatile var recordingError: String? = null
    @Volatile var audioRecorderStarted = false
    @Volatile var audioRecordingError: String? = null
    var releasePreview: (() -> Unit)? = null
    private val replies = LinkedHashMap<String, String>()

    // Called serially on the main dispatcher by LiveAccessService.
    suspend fun execute(context: Context, message: JSONObject): String = withContext(Dispatchers.Main) {
        val id = message.optString("requestId")
        replies[id]?.let { return@withContext it }
        var failureMessage: String? = null
        try {
            check(id.isNotBlank()) { "Missing request ID" }
            check(isEnabled(context)) { "Allow server control is off on this phone" }
            val remaining = message.optLong("expiresAt") - System.currentTimeMillis()
            check(remaining in 1..60_000L) { "Command expired. Check the phone clock and try again" }
            val action = message.optString("action")
            check(action in listOf("start", "stop", "configure", "start_audio", "stop_audio")) { "Unknown recording command" }
            val wasActive = PowerRecordingSettings.isBackgroundRecordingActive(context)
            if (action == "stop") {
                check(!PowerRecordingSettings.isVideoRecordingActive(context) || wasActive) {
                    "Stop foreground recording on the phone first"
                }
                if (wasActive) {
                    recordingError = null
                    PowerRecordingSettings.setPowerAutoStartSuppressed(context,
                        PowerRecordingSettings.isPowerAutoBackgroundEnabled(context) && PowerRecordingSettings.isDeviceCharging(context))
                    context.startService(Intent(context, BackgroundRecordingService::class.java)
                        .setAction(BackgroundRecordingService.ACTION_STOP))
                    check(withTimeoutOrNull(20_000L) {
                        while (PowerRecordingSettings.isBackgroundRecordingActive(context)) delay(100)
                        true
                    } == true) { "Still saving the recording. Check status before trying again" }
                    check(recordingError == null) { recordingError.orEmpty() }
                }
            } else if (action == "stop_audio") {
                if (PowerRecordingSettings.isAudioRecordingActive(context)) {
                    audioRecordingError = null
                    context.startService(Intent(context, AudioRecordingService::class.java)
                        .setAction(AudioRecordingService.ACTION_STOP))
                    check(withTimeoutOrNull(20_000L) {
                        while (PowerRecordingSettings.isAudioRecordingActive(context)) delay(100)
                        true
                    } == true) { "Still saving the audio recording. Check status before trying again" }
                    check(audioRecordingError == null) { audioRecordingError.orEmpty() }
                }
            } else if (action == "start_audio") {
                check(!PowerRecordingSettings.isAnyRecordingActive(context)) { "Stop the current recording before starting audio" }
                check(!LiveAccessSettings.isStreaming(context)) { "Close live camera before starting audio recording" }
                check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    "Grant microphone permission in the phone app first"
                }
                check(isEnabled(context)) { "Server control was disabled on the phone" }
                audioRecorderStarted = false
                audioRecordingError = null
                ContextCompat.startForegroundService(context,
                    Intent(context, AudioRecordingService::class.java)
                        .setAction(AudioRecordingService.ACTION_START)
                        .putExtra(EXTRA_REMOTE_EXPIRES_AT, message.optLong("expiresAt")))
                val started = withTimeoutOrNull(20_000L) {
                    while (!audioRecorderStarted && audioRecordingError == null && isEnabled(context)) delay(100)
                    audioRecorderStarted
                } == true
                if (!started) {
                    context.startService(Intent(context, AudioRecordingService::class.java)
                        .setAction(AudioRecordingService.ACTION_STOP))
                    kotlin.error(audioRecordingError ?: "Audio recording could not start. Check microphone permission and storage")
                }
            } else {
                check(!PowerRecordingSettings.isAnyRecordingActive(context)) { "Stop the current recording before changing settings or starting" }
                val quality = BackgroundVideoQuality.entries.firstOrNull { it.name == message.optString("quality") }
                    ?: error("Invalid video quality")
                val alert = RecordingStartAlertMode.entries.firstOrNull { it.name == message.optString("startAlert") }
                    ?: error("Invalid start alert")
                val minutes = message.optInt("segmentMinutes", -1)
                check(minutes in 0..VideoSegmentSettings.MAX_CUSTOM_DURATION_MINUTES) { "Invalid segment length" }
                if (action == "start") {
                    check(!LiveAccessSettings.isStreaming(context)) { "Close live camera before starting recording" }
                    check(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                    }) { "Grant camera and microphone permissions in the phone app first" }
                }
                check(isEnabled(context)) { "Server control was disabled on the phone" }
                BackgroundVideoQualitySettings.setQuality(context, quality)
                VideoSegmentSettings.setDurationMinutes(context, minutes)
                RecordingStartAlertSettings.setMode(context, alert)
                if (action == "start") {
                    releasePreview?.invoke()
                    recorderStarted = false
                    recordingError = null
                    PowerRecordingSettings.setPowerAutoStartSuppressed(context, false)
                    ContextCompat.startForegroundService(context,
                        Intent(context, BackgroundRecordingService::class.java)
                            .setAction(BackgroundRecordingService.ACTION_START)
                            .putExtra(EXTRA_REMOTE_EXPIRES_AT, message.optLong("expiresAt")))
                    val started = withTimeoutOrNull(20_000L) {
                        while (!recorderStarted && recordingError == null && isEnabled(context)) delay(100)
                        recorderStarted
                    } == true
                    if (!started) {
                        context.startService(Intent(context, BackgroundRecordingService::class.java)
                            .setAction(BackgroundRecordingService.ACTION_STOP))
                    kotlin.error(recordingError ?: "Recording could not start. Check camera permissions, storage and other camera apps")
                    }
                }
            }
        } catch (failure: Exception) {
            failureMessage = failure.message ?: "Recording command failed"
        }
        JSONObject().put("type", "recording_response").put("requestId", id)
            .put("success", failureMessage == null).put("error", failureMessage ?: JSONObject.NULL)
            .put("backgroundRecordingActive", PowerRecordingSettings.isBackgroundRecordingActive(context))
            .put("audioRecordingActive", PowerRecordingSettings.isAudioRecordingActive(context))
            .put("quality", BackgroundVideoQualitySettings.quality(context).name)
            .put("segmentMinutes", VideoSegmentSettings.durationMinutes(context))
            .put("startAlert", RecordingStartAlertSettings.mode(context).name)
            .toString().also {
                replies[id] = it
                while (replies.size > 32) replies.remove(replies.keys.first())
            }
    }

    const val EXTRA_REMOTE_EXPIRES_AT = "remote_expires_at"
}
