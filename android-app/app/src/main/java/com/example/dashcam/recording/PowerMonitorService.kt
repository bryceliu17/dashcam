package com.example.dashcam.recording

import android.app.NotificationChannel
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.dashcam.MainActivity
import com.example.dashcam.R

class PowerMonitorService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastChargingState: Boolean? = null
    private var monitoring = false
    private val batteryPollRunnable = object : Runnable {
        override fun run() {
            pollPowerState()
            if (monitoring) mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!PowerRecordingSettings.isPowerAutoBackgroundEnabled(this@PowerMonitorService)) return
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> handlePowerConnected()
                Intent.ACTION_POWER_DISCONNECTED -> handlePowerDisconnected()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ContextCompat.registerReceiver(this, powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !PowerRecordingSettings.isPowerAutoBackgroundEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        if (!isBackgroundRecordingServiceRunning()) {
            PowerRecordingSettings.setBackgroundRecordingActive(this, false)
        }
        startMonitoring()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handlePowerConnected() {
        lastChargingState = true
        if (!PowerRecordingSettings.isDeviceCharging(this)) return
        if (PowerRecordingSettings.isAnyRecordingActive(this)) return
        try {
            Log.i(TAG, "Power connected while monitoring; starting background recording")
            ContextCompat.startForegroundService(
                this,
                Intent(this, BackgroundRecordingService::class.java)
                    .setAction(BackgroundRecordingService.ACTION_START)
            )
            PowerRecordingSettings.setBackgroundRecordingActive(this, true)
        } catch (error: RuntimeException) {
            PowerRecordingSettings.setBackgroundRecordingActive(this, false)
            Log.e(TAG, "Unable to start background recording from power monitor", error)
        }
    }

    private fun handlePowerDisconnected() {
        lastChargingState = false
        if (PowerRecordingSettings.isDeviceCharging(this)) return
        if (!PowerRecordingSettings.isBackgroundRecordingActive(this)) return
        try {
            Log.i(TAG, "Power disconnected while monitoring; stopping after current segment")
            ContextCompat.startForegroundService(
                this,
                Intent(this, BackgroundRecordingService::class.java)
                    .setAction(BackgroundRecordingService.ACTION_STOP_AFTER_SEGMENT)
            )
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to request background stop after current segment", error)
        }
    }

    private fun startMonitoring() {
        if (monitoring) return
        lastChargingState = PowerRecordingSettings.isDeviceCharging(this)
        monitoring = true
        mainHandler.post(batteryPollRunnable)
        Log.i(TAG, "Power monitor polling started, charging=$lastChargingState")
    }

    private fun pollPowerState() {
        val charging = PowerRecordingSettings.isDeviceCharging(this)
        val previous = lastChargingState
        if (previous == null) {
            lastChargingState = charging
            return
        }

        if (!previous && charging) {
            Log.i(TAG, "Power connected detected by polling")
            handlePowerConnected()
        } else if (previous && !charging) {
            Log.i(TAG, "Power disconnected detected by polling")
            handlePowerDisconnected()
        }
        lastChargingState = charging
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_dashcam)
        .setContentTitle("Dashcam power monitor")
        .setContentText("Waiting for power connection")
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dashcam power monitor", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun isBackgroundRecordingServiceRunning(): Boolean {
        val manager = getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == BackgroundRecordingService::class.java.name
        }
    }

    override fun onDestroy() {
        monitoring = false
        mainHandler.removeCallbacks(batteryPollRunnable)
        try { unregisterReceiver(powerReceiver) } catch (_: IllegalArgumentException) { }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.dashcam.power_monitor.START"
        const val ACTION_STOP = "com.example.dashcam.power_monitor.STOP"
        private const val CHANNEL_ID = "dashcam_power_monitor"
        private const val NOTIFICATION_ID = 2002
        private const val POLL_INTERVAL_MS = 3_000L
        private const val TAG = "PowerMonitorService"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, PowerMonitorService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, PowerMonitorService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
