package com.example.dashcam.battery

import android.content.Context
import com.example.dashcam.data.DashcamDatabase
import org.json.JSONArray
import org.json.JSONObject

object BatteryHistoryPayload {
    suspend fun create(context: Context, requestId: String, requestedHours: Int): JSONObject {
        val hours = requestedHours.coerceIn(1, 72)
        val samples = DashcamDatabase.get(context).batteryTemperatureDao()
            .samplesSince(System.currentTimeMillis() - hours * 60L * 60_000L)
            .takeLast(1_000)
        val items = JSONArray()
        samples.forEach { sample ->
            items.put(JSONObject()
                .put("recordedAt", sample.recordedAt)
                .put("temperatureTenthsC", sample.temperatureTenthsC)
                .put("batteryLevel", sample.batteryLevel)
                .put("isCharging", sample.isCharging)
                .put("videoRecordingActive", sample.videoRecordingActive)
                .put("audioRecordingActive", sample.audioRecordingActive))
        }
        return JSONObject()
            .put("type", "battery_history_response")
            .put("requestId", requestId)
            .put("generatedAt", System.currentTimeMillis())
            .put("items", items)
    }
}
