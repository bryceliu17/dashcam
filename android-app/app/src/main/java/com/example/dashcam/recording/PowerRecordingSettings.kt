package com.example.dashcam.recording

import android.os.BatteryManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

object PowerRecordingSettings {
    private const val PREFS = "dashcam_settings"
    private const val KEY_POWER_AUTO_BACKGROUND = "power_auto_background"
    private const val KEY_VOLUME_KEY_START = "volume_key_start"
    private const val KEY_FOREGROUND_RECORDING_ACTIVE = "foreground_recording_active"
    private const val KEY_BACKGROUND_RECORDING_ACTIVE = "background_recording_active"
    private const val KEY_LEGACY_RECORDING_ACTIVE = "recording_active"

    fun isPowerAutoBackgroundEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POWER_AUTO_BACKGROUND, false)

    fun setPowerAutoBackgroundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().apply {
            putBoolean(KEY_POWER_AUTO_BACKGROUND, enabled)
            if (enabled) putBoolean(KEY_VOLUME_KEY_START, false)
        }.apply()
    }

    fun isVolumeKeyStartEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOLUME_KEY_START, false)

    fun setVolumeKeyStartEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().apply {
            putBoolean(KEY_VOLUME_KEY_START, enabled)
            if (enabled) putBoolean(KEY_POWER_AUTO_BACKGROUND, false)
        }.apply()
    }

    fun isAnyRecordingActive(context: Context): Boolean {
        val preferences = prefs(context)
        return preferences.getBoolean(KEY_FOREGROUND_RECORDING_ACTIVE, false) ||
            preferences.getBoolean(KEY_BACKGROUND_RECORDING_ACTIVE, false)
    }

    fun isBackgroundRecordingActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BACKGROUND_RECORDING_ACTIVE, false)

    fun isDeviceCharging(context: Context): Boolean {
        val battery = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun setForegroundRecordingActive(context: Context, active: Boolean) {
        val preferences = prefs(context)
        val backgroundActive = preferences.getBoolean(KEY_BACKGROUND_RECORDING_ACTIVE, false)
        preferences.edit()
            .putBoolean(KEY_FOREGROUND_RECORDING_ACTIVE, active)
            .putBoolean(KEY_LEGACY_RECORDING_ACTIVE, active || backgroundActive)
            .apply()
    }

    fun setBackgroundRecordingActive(context: Context, active: Boolean) {
        val preferences = prefs(context)
        val foregroundActive = preferences.getBoolean(KEY_FOREGROUND_RECORDING_ACTIVE, false)
        preferences.edit()
            .putBoolean(KEY_BACKGROUND_RECORDING_ACTIVE, active)
            .putBoolean(KEY_LEGACY_RECORDING_ACTIVE, active || foregroundActive)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
