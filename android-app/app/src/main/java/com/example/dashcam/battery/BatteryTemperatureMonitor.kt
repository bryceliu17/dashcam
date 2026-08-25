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
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val voltageMillivolts = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            .takeIf { it in 2_000..6_000 }
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val currentNowMicroamps = batteryManager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != 0 && it != Int.MIN_VALUE && kotlin.math.abs(it.toLong()) <= 20_000_000L }
        val estimatedPowerMilliwatts = if (currentNowMicroamps != null && voltageMillivolts != null) {
            (currentNowMicroamps.toLong() * voltageMillivolts / 1_000_000L)
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                .toInt()
        } else {
            null
        }
        val now = System.currentTimeMillis()
        val sample = BatteryTemperatureSample(
            recordedAt = now,
            temperatureTenthsC = temperature,
            batteryLevel = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            videoRecordingActive = PowerRecordingSettings.isVideoRecordingActive(context),
            audioRecordingActive = PowerRecordingSettings.isAudioRecordingActive(context),
            voltageMillivolts = voltageMillivolts,
            currentNowMicroamps = currentNowMicroamps,
            estimatedBatteryPowerMilliwatts = estimatedPowerMilliwatts,
            chargingSource = chargingSource(plugged)
        )
        val dao = DashcamDatabase.get(context).batteryTemperatureDao()
        dao.insert(sample)
        dao.deleteOlderThan(now - RETENTION_MS)
        return sample
    }

    private fun chargingSource(plugged: Int): String = when {
        plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC"
        plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
        plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "Wireless"
        plugged and 8 != 0 -> "Dock"
        plugged == 0 -> "None"
        else -> "Unknown"
    }
}
