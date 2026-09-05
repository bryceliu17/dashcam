package com.example.dashcam.upload

import android.content.Context

object UploadPolicySettings {
    private const val KEY_MOBILE_UPLOADS_ALLOWED = "server_mobile_uploads_allowed"

    fun update(context: Context, allowed: Boolean) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(UploadWorker.PREFS, Context.MODE_PRIVATE)
        val wasAllowed = preferences.getBoolean(KEY_MOBILE_UPLOADS_ALLOWED, true)
        preferences.edit().putBoolean(KEY_MOBILE_UPLOADS_ALLOWED, allowed).apply()
        if (allowed && !wasAllowed && UploadWorker.isAutomaticUploadEnabled(appContext)) {
            UploadWorker.enqueueNow(appContext)
        }
    }
}
