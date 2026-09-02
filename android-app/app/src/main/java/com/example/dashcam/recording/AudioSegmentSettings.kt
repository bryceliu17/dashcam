package com.example.dashcam.recording

import android.content.Context

object AudioSegmentSettings {
    const val DEFAULT_DURATION_MINUTES = 30
    const val UNLIMITED_DURATION_MINUTES = 0
    const val MIN_CUSTOM_DURATION_MINUTES = 1
    const val MAX_CUSTOM_DURATION_MINUTES = 24 * 60

    private const val PREFS = "dashcam_settings"
    private const val KEY_DURATION_MINUTES = "audio_segment_duration_minutes"

    fun durationMinutes(context: Context): Int {
        val saved = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_DURATION_MINUTES, DEFAULT_DURATION_MINUTES)
        return if (saved == UNLIMITED_DURATION_MINUTES) {
            UNLIMITED_DURATION_MINUTES
        } else {
            saved.coerceIn(MIN_CUSTOM_DURATION_MINUTES, MAX_CUSTOM_DURATION_MINUTES)
        }
    }

    fun durationMilliseconds(context: Context): Long? =
        durationMinutes(context)
            .takeIf { it != UNLIMITED_DURATION_MINUTES }
            ?.toLong()
            ?.times(60_000L)

    fun setDurationMinutes(context: Context, minutes: Int) {
        require(
            minutes == UNLIMITED_DURATION_MINUTES ||
                minutes in MIN_CUSTOM_DURATION_MINUTES..MAX_CUSTOM_DURATION_MINUTES
        )
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DURATION_MINUTES, minutes)
            .apply()
    }

    fun displayLabel(context: Context): String {
        val minutes = durationMinutes(context)
        return if (minutes == UNLIMITED_DURATION_MINUTES) "Unlimited" else "$minutes min"
    }
}
