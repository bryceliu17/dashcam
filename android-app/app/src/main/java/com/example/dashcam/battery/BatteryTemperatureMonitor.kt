package com.example.dashcam.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.example.dashcam.data.BatteryTemperatureSample
import com.example.dashcam.data.DashcamDatabase
import com.example.dashcam.recording.PowerRecordingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object BatteryTemperatureMonitor {
    const val SAMPLE_INTERVAL_MS = 5 * 60_000L
    const val RETENTION_MS = 72 * 60 * 60_000L
    private const val TAG = "BatteryTempMonitor"
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            while (isActive) {
                recordNow(appContext)
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    suspend fun recordNow(context: Context): BatteryTemperatureSample? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (temperature !in -500..1000) {
            Log.d(TAG, "Battery temperature unavailable: $temperature")
            return null
        }
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val now = System.currentTimeMillis()
        val sample = BatteryTemperatureSample(
            recordedAt = now,
            temperatureTenthsC = temperature,
            batteryLevel = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            videoRecordingActive = PowerRecordingSettings.isVideoRecordingActive(context),
            audioRecordingActive = PowerRecordingSettings.isAudioRecordingActive(context)
        )
        val dao = DashcamDatabase.get(context).batteryTemperatureDao()
        dao.insert(sample)
        dao.deleteOlderThan(now - RETENTION_MS)
        return sample
    }
}
