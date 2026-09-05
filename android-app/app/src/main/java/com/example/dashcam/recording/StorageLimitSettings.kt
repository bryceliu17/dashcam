package com.example.dashcam.recording

import android.content.Context

object StorageLimitSettings {
    const val BYTES_PER_GIB = 1024L * 1024 * 1024

    private const val PREFS = "dashcam_settings"
    private const val KEY_VIDEO_LIMIT_BYTES = "video_storage_limit_bytes"
    private const val KEY_AUDIO_LIMIT_BYTES = "audio_storage_limit_bytes"

    fun videoLimitBytes(context: Context, defaultBytes: Long): Long =
        limitBytes(context, KEY_VIDEO_LIMIT_BYTES, defaultBytes)

    fun audioLimitBytes(context: Context, defaultBytes: Long): Long =
        limitBytes(context, KEY_AUDIO_LIMIT_BYTES, defaultBytes)

    fun setLimitsGiB(context: Context, videoGiB: Double, audioGiB: Double) {
        require(videoGiB.isFinite() && videoGiB > 0.0)
        require(audioGiB.isFinite() && audioGiB > 0.0)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_VIDEO_LIMIT_BYTES, gibToBytes(videoGiB))
            .putLong(KEY_AUDIO_LIMIT_BYTES, gibToBytes(audioGiB))
            .apply()
    }

    private fun limitBytes(context: Context, key: String, defaultBytes: Long): Long =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(key, defaultBytes)
            .takeIf { it > 0L }
            ?: defaultBytes

    private fun gibToBytes(value: Double): Long =
        (value * BYTES_PER_GIB.toDouble())
            .coerceAtMost(Long.MAX_VALUE.toDouble())
            .toLong()
            .coerceAtLeast(1L)
}
