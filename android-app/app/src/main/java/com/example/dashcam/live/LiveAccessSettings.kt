package com.example.dashcam.live

import android.content.Context

object LiveAccessSettings {
    private const val PREFS = "dashcam_settings"
    private const val KEY_ENABLED = "live_access_enabled"
    private const val KEY_STREAMING = "live_streaming"
    private const val KEY_ERROR = "live_error"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isStreaming(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STREAMING, false)

    fun setStreaming(context: Context, streaming: Boolean) {
        prefs(context).edit().putBoolean(KEY_STREAMING, streaming).apply()
    }

    fun error(context: Context): String =
        prefs(context).getString(KEY_ERROR, null).orEmpty()

    fun setError(context: Context, message: String?) {
        prefs(context).edit().putString(KEY_ERROR, message?.take(500)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
