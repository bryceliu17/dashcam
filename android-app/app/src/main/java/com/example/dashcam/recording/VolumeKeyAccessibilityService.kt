package com.example.dashcam.recording

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat

class VolumeKeyAccessibilityService : AccessibilityService() {
    private var lastVolumeUpTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        val currentInfo = serviceInfo ?: return
        currentInfo.flags = currentInfo.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = currentInfo
        Log.i(TAG, "Accessibility service connected; flags=${currentInfo.flags} capabilities=${currentInfo.capabilities}")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!PowerRecordingSettings.isVolumeKeyStartEnabled(this)) return false

        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            handleVolumeUpDown(event)
        }
        return true
    }

    private fun handleVolumeUpDown(event: KeyEvent) {
        val eventTime = event.eventTime
        val isDoublePress = lastVolumeUpTime > 0L &&
            eventTime - lastVolumeUpTime in 1..DOUBLE_PRESS_WINDOW_MS
        Log.i(TAG, "Volume up pressed; doublePress=$isDoublePress")

        if (isDoublePress) {
            lastVolumeUpTime = 0L
            startBackgroundRecording()
        } else {
            lastVolumeUpTime = eventTime
        }
    }

    private fun startBackgroundRecording() {
        if (PowerRecordingSettings.isPowerAutoBackgroundEnabled(this)) return
        if (PowerRecordingSettings.isAnyRecordingActive(this)) return

        try {
            Log.i(TAG, "Volume up double-press detected; starting background recording")
            ContextCompat.startForegroundService(
                this,
                Intent(this, BackgroundRecordingService::class.java)
                    .setAction(BackgroundRecordingService.ACTION_START)
            )
            PowerRecordingSettings.setBackgroundRecordingActive(this, true)
            Toast.makeText(this, "Volume up double-press started background recording", Toast.LENGTH_SHORT).show()
        } catch (error: RuntimeException) {
            PowerRecordingSettings.setBackgroundRecordingActive(this, false)
            Toast.makeText(this, "Unable to start background recording", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val DOUBLE_PRESS_WINDOW_MS = 700L
        private const val TAG = "VolumeKeyStart"
    }
}
