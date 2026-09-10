package com.example.dashcam.recording

import android.content.Context

enum class BackgroundVideoQuality(val label: String) {
    Balanced("Balanced (720p)"),
    High("High (1080p)")
}

object BackgroundVideoQualitySettings {
    private const val PREFS = "dashcam_settings"
    private const val KEY_QUALITY = "background_video_quality"

    fun quality(context: Context): BackgroundVideoQuality {
        val saved = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_QUALITY, null)
        return BackgroundVideoQuality.entries.firstOrNull { it.name == saved }
            ?: BackgroundVideoQuality.Balanced
    }

    fun setQuality(context: Context, quality: BackgroundVideoQuality) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUALITY, quality.name)
            .apply()
    }
}
