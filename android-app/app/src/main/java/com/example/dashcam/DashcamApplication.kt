package com.example.dashcam

import android.app.Application
import com.example.dashcam.upload.UploadWorker

class DashcamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UploadWorker.schedulePeriodic(this)
        UploadWorker.enqueueNow(this)
    }
}
