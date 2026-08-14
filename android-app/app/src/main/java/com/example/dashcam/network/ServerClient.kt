package com.example.dashcam.network

import com.example.dashcam.data.AudioEntity
import com.example.dashcam.data.VideoEntity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class DeviceHeartbeat(
    val deviceId: String,
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val appVersion: String,
    val ipAddress: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val chargingSource: String,
    val powerSaveMode: Boolean,
    val videoRecordingActive: Boolean,
    val audioRecordingActive: Boolean,
    val liveAccessEnabled: Boolean,
    val liveStreaming: Boolean,
    val liveError: String
)

data class BatteryHistoryRequest(val requestId: String, val hours: Int)
data class DeviceControl(val liveRequested: Boolean, val batteryHistoryRequest: BatteryHistoryRequest? = null)

fun DeviceHeartbeat.toJson(): JSONObject = JSONObject()
    .put("deviceId", deviceId)
    .put("deviceName", deviceName)
    .put("manufacturer", manufacturer)
    .put("model", model)
    .put("androidVersion", androidVersion)
    .put("appVersion", appVersion)
    .put("ipAddress", ipAddress)
    .put("batteryLevel", batteryLevel)
    .put("isCharging", isCharging)
    .put("chargingSource", chargingSource)
    .put("powerSaveMode", powerSaveMode)
    .put("videoRecordingActive", videoRecordingActive)
    .put("audioRecordingActive", audioRecordingActive)
    .put("liveAccessEnabled", liveAccessEnabled)
    .put("liveStreaming", liveStreaming)
    .put("liveError", liveError)

class ServerClient(private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()
    private val liveClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val heartbeatClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun health(): Boolean = try {
        val request = Request.Builder().url("${cleanBase()}/api/health")
            .header("Connection", "close")
            .get().build()
        client.newCall(request).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    fun upload(video: VideoEntity, playbackRotationDegrees: Int): Long {
        val file = File(video.localPath)
        require(file.exists()) { "Local file is missing: ${video.filename}" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", video.filename, file.asRequestBody("video/mp4".toMediaType()))
            .addFormDataPart("filename", video.filename)
            .addFormDataPart("startTime", formatUtc(video.startTime))
            .addFormDataPart("endTime", formatUtc(video.endTime))
            .addFormDataPart("durationSeconds", video.durationSeconds.toString())
            .addFormDataPart("fileSizeBytes", file.length().toString())
            .addFormDataPart("playbackRotationDegrees", playbackRotationDegrees.toString())
            .build()
        val request = Request.Builder().url("${cleanBase()}/api/videos/upload")
            .header("Connection", "close")
            .post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Server returned ${response.code}: ${text.take(300)}")
            return JSONObject(text).getLong("id")
        }
    }

    fun uploadAudio(audio: AudioEntity): Long {
        val file = File(audio.localPath)
        require(file.exists()) { "Local file is missing: ${audio.filename}" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.filename, file.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("filename", audio.filename)
            .addFormDataPart("startTime", formatUtc(audio.startTime))
            .addFormDataPart("endTime", formatUtc(audio.endTime))
            .addFormDataPart("durationSeconds", audio.durationSeconds.toString())
            .addFormDataPart("fileSizeBytes", file.length().toString())
            .build()
        val request = Request.Builder().url("${cleanBase()}/api/audio/upload")
            .header("Connection", "close")
            .post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Server returned ${response.code}: ${text.take(300)}")
            return JSONObject(text).getLong("id")
        }
    }

    fun updatePlaybackRotation(serverVideoId: Long, playbackRotationDegrees: Int) {
        val json = JSONObject()
            .put("playbackRotationDegrees", playbackRotationDegrees)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("${cleanBase()}/api/videos/$serverVideoId/rotation")
            .header("Connection", "close")
            .patch(json).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Server returned ${response.code}: ${text.take(300)}")
        }
    }

    fun reportDeviceStatus(status: DeviceHeartbeat): DeviceControl {
        val json = status.toJson().toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("${cleanBase()}/api/devices/heartbeat")
            .header("Connection", "close")
            .post(json).build()
        heartbeatClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Server returned ${response.code}: ${text.take(300)}")
            }
            val json = JSONObject(text)
            val history = json.optJSONObject("batteryHistoryRequest")?.let { request ->
                val requestId = request.optString("requestId")
                if (requestId.isBlank()) null else BatteryHistoryRequest(
                    requestId,
                    request.optInt("hours", 24).coerceIn(1, 72)
                )
            }
            return DeviceControl(json.optBoolean("liveRequested", false), history)
        }
    }

    fun sendBatteryHistoryResponse(deviceId: String, payload: JSONObject) {
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${cleanBase()}/api/devices/$deviceId/battery-history-response")
            .header("Connection", "close")
            .post(body)
            .build()
        heartbeatClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                throw IllegalStateException("Server returned ${response.code}: ${text.take(300)}")
            }
        }
    }

    fun uploadLiveFrame(deviceId: String, jpeg: ByteArray) {
        val body = jpeg.toRequestBody("image/jpeg".toMediaType())
        val request = Request.Builder()
            .url("${cleanBase()}/api/devices/$deviceId/live/frame")
            .post(body)
            .build()
        liveClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                throw IllegalStateException("Server returned ${response.code}: ${text.take(300)}")
            }
        }
    }

    private fun cleanBase() = baseUrl.trim().trimEnd('/').also {
        require(it.startsWith("http://") || it.startsWith("https://")) { "Invalid server URL" }
    }

    private fun formatUtc(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMillis))
}
