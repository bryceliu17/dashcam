package com.example.dashcam.network

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.example.dashcam.BuildConfig
import com.example.dashcam.live.LiveAccessSettings
import com.example.dashcam.recording.PowerRecordingSettings
import com.example.dashcam.upload.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object DeviceStatusReporter {
    private const val TAG = "DeviceStatusReporter"
    private const val REPORT_INTERVAL_MS = 60_000L
    private val started = AtomicBoolean(false)
    private val serverReachable = AtomicReference<Boolean?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            while (isActive) {
                reportNow(appContext)
                delay(REPORT_INTERVAL_MS)
            }
        }
    }

    fun reportNow(context: Context): DeviceControl? {
        val appContext = context.applicationContext
        return try {
            val serverUrl = appContext.getSharedPreferences(UploadWorker.PREFS, Context.MODE_PRIVATE)
                .getString(UploadWorker.KEY_SERVER_URL, UploadWorker.DEFAULT_SERVER_URL)
                ?: UploadWorker.DEFAULT_SERVER_URL
            ServerClient(serverUrl).reportDeviceStatus(readStatus(appContext)).also {
                updateServerReachability(appContext, true)
            }
        } catch (error: Exception) {
            updateServerReachability(appContext, false)
            Log.d(TAG, "Device status heartbeat deferred: ${error.message.orEmpty()}")
            null
        }
    }

    private fun updateServerReachability(context: Context, reachable: Boolean) {
        val previous = serverReachable.getAndSet(reachable)
        if (reachable && previous == false && UploadWorker.isAutomaticUploadEnabled(context)) {
            Log.i(TAG, "Server connection restored; queuing automatic upload")
            UploadWorker.enqueueNow(context)
        }
    }

    fun deviceId(context: Context): String {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty()
            .ifBlank { "${manufacturer.lowercase(Locale.US)}-${model.lowercase(Locale.US)}" }
    }

    private fun readStatus(context: Context): DeviceHeartbeat {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryLevel = if (level >= 0 && scale > 0) {
            (level * 100 / scale).coerceIn(0, 100)
        } else {
            0
        }
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val deviceName = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")
        }.ifBlank { "Android device" }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        return DeviceHeartbeat(
            deviceId = deviceId(context),
            deviceName = deviceName,
            manufacturer = manufacturer,
            model = model,
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appVersion = BuildConfig.VERSION_NAME,
            ipAddress = wifiIpv4Address(context),
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            chargingSource = when {
                !isCharging -> "None"
                plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC"
                plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
                plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "Wireless"
                else -> "Unknown"
            },
            powerSaveMode = powerManager.isPowerSaveMode,
            videoRecordingActive = PowerRecordingSettings.isVideoRecordingActive(context),
            audioRecordingActive = PowerRecordingSettings.isAudioRecordingActive(context),
            liveAccessEnabled = LiveAccessSettings.isEnabled(context),
            liveStreaming = LiveAccessSettings.isStreaming(context),
            liveError = LiveAccessSettings.error(context)
        )
    }

    private fun wifiIpv4Address(context: Context): String {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return ""
        val address = wifiManager.connectionInfo?.ipAddress ?: return ""
        if (address == 0) return ""
        return listOf(
            address and 0xff,
            address shr 8 and 0xff,
            address shr 16 and 0xff,
            address shr 24 and 0xff
        ).joinToString(".")
    }
}
