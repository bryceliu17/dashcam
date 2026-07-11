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
    private var volumeUpDown = false
    private var volumeDownDown = false
    private var volumeUpTime = 0L
    private var volumeDownTime = 0L
    private var triggeredForCurrentPress = false

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

        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolumeKey) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(TAG, "Volume key down: ${KeyEvent.keyCodeToString(event.keyCode)}")
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> handleVolumeDown(event)
            KeyEvent.ACTION_UP -> handleVolumeUp(event)
        }
        return true
    }

    private fun handleVolumeDown(event: KeyEvent) {
        if (event.repeatCount > 0) return

        val eventTime = event.eventTime
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpDown = true
            volumeUpTime = eventTime
        } else {
            volumeDownDown = true
            volumeDownTime = eventTime
        }

        val pressedTogether = volumeUpTime > 0L &&
            volumeDownTime > 0L &&
            kotlin.math.abs(volumeUpTime - volumeDownTime) <= COMBO_WINDOW_MS
        if (pressedTogether && !triggeredForCurrentPress) {
            triggeredForCurrentPress = true
            startBackgroundRecording()
        }
    }

    private fun handleVolumeUp(event: KeyEvent) {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpDown = false
        } else {
            volumeDownDown = false
        }
        if (!volumeUpDown && !volumeDownDown) triggeredForCurrentPress = false
    }

    private fun startBackgroundRecording() {
        if (PowerRecordingSettings.isPowerAutoBackgroundEnabled(this)) return
        if (PowerRecordingSettings.isAnyRecordingActive(this)) return

        try {
            Log.i(TAG, "Volume key combo detected; starting background recording")
            ContextCompat.startForegroundService(
                this,
                Intent(this, BackgroundRecordingService::class.java)
                    .setAction(BackgroundRecordingService.ACTION_START)
            )
            PowerRecordingSettings.setBackgroundRecordingActive(this, true)
            Toast.makeText(this, "Volume keys started background recording", Toast.LENGTH_SHORT).show()
        } catch (error: RuntimeException) {
            PowerRecordingSettings.setBackgroundRecordingActive(this, false)
            Toast.makeText(this, "Unable to start background recording", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val COMBO_WINDOW_MS = 700L
        private const val TAG = "VolumeKeyStart"
    }
}
