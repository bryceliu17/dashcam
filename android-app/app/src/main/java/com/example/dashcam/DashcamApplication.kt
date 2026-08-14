package com.example.dashcam

import android.app.Application
import com.example.dashcam.battery.BatteryTemperatureMonitor
import com.example.dashcam.network.DeviceStatusReporter
import com.example.dashcam.upload.UploadWorker

class DashcamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UploadWorker.schedulePeriodic(this)
        UploadWorker.enqueueNow(this)
        DeviceStatusReporter.start(this)
        BatteryTemperatureMonitor.start(this)
    }
}
