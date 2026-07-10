package com.example.dashcam.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        val action = intent?.action ?: return
        Log.i(TAG, "Startup event received: $action")
        if (!PowerRecordingSettings.isPowerAutoBackgroundEnabled(appContext)) return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> startPowerMonitor(appContext)
        }
    }

    private fun startPowerMonitor(context: Context) {
        try {
            PowerMonitorService.start(context)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to start power monitor", error)
        }
    }

    private companion object {
        private const val TAG = "PowerConnectionReceiver"
    }
}
