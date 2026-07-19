package com.yourbusiness.formfusion.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MultipartBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CreatedSession(val sessionId: String, val joinCode: String)

data class SessionStatus(
    val sessionId: String,
    val deviceIds: List<String>,
    val calibrated: Boolean
)

data class LivePoseResult(
    val joints: Map<Int, Triple<Float, Float, Float>>,
    val primaryAngleDegrees: Float?,
    val repCount: Int,
    val movementState: String,
    val formQuality: String,
    val reprojectionError: Float?
)

data class CalibrationStatus(
    val completePairs: Int,
    val calibrated: Boolean,
    val reprojectionError: Float?
)

class BackendApi(private val backendUrl: String) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private suspend fun execute(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        val message = runCatching {
                            val json = JSONObject(body)
                            json.optJSONObject("error")?.optString("message")
                                ?: json.optString("detail")
                        }.getOrNull().orEmpty()
                        continuation.resumeWithException(
                            IOException(message.ifBlank { "Backend request failed (${it.code})" })
                        )
                    } else {
                        continuation.resume(JSONObject(body))
                    }
                }
            }
        })
    }

    suspend fun createSession(exercise: String): CreatedSession {
        val body = JSONObject().put("exercise", exercise).toString().toRequestBody(jsonType)
        val request = Request.Builder().url("${backendUrl.trimEnd('/')}/api/v1/sessions")
            .post(body).build()
        val result = execute(request)
        return CreatedSession(result.getString("session_id"), result.getString("join_code"))
    }

    suspend fun joinSession(
        sessionId: String,
        joinCode: String,
        deviceId: String,
        deviceName: String
    ) {
        val body = JSONObject()
            .put("join_code", joinCode)
            .put("device_id", deviceId)
            .put("device_name", deviceName)
            .toString().toRequestBody(jsonType)
        execute(
            Request.Builder()
                .url("${backendUrl.trimEnd('/')}/api/v1/sessions/$sessionId/join")
                .post(body)
                .build()
        )
    }

    suspend fun status(sessionId: String): SessionStatus {
        val result = execute(
            Request.Builder()
                .url("${backendUrl.trimEnd('/')}/api/v1/sessions/$sessionId")
                .get().build()
        )
        val ids = result.getJSONArray("device_ids")
        return SessionStatus(
            sessionId,
            List(ids.length()) { index -> ids.getString(index) },
            result.getBoolean("calibrated")
        )
    }

    suspend fun uploadCalibrationCapture(
        sessionId: String,
        deviceId: String,
        pairId: String,
        image: File
    ): CalibrationStatus {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("device_id", deviceId)
            .addFormDataPart("pair_id", pairId)
            .addFormDataPart("image", image.name, image.asRequestBody("image/jpeg".toMediaType()))
            .build()
        val result = execute(
            Request.Builder()
                .url("${backendUrl.trimEnd('/')}/api/v1/sessions/$sessionId/calibration/images")
                .post(multipart).build()
        )
        return calibrationStatus(result)
    }

    suspend fun calibrationStatus(sessionId: String): CalibrationStatus {
        val result = execute(
            Request.Builder()
                .url("${backendUrl.trimEnd('/')}/api/v1/sessions/$sessionId/calibration")
                .get().build()
        )
        return calibrationStatus(result)
    }

    suspend fun finalizeCalibration(
        sessionId: String,
        deviceA: String,
        deviceB: String
    ): CalibrationStatus {
        val body = JSONObject()
            .put("device_a", deviceA)
            .put("device_b", deviceB)
            .put("checkerboard_columns", 9)
            .put("checkerboard_rows", 6)
            .put("square_size", 2.5)
            .put("minimum_pairs", 10)
            .toString().toRequestBody(jsonType)
        val result = execute(
            Request.Builder()
                .url("${backendUrl.trimEnd('/')}/api/v1/sessions/$sessionId/calibration/finalize")
                .post(body).build()
        )
        return calibrationStatus(result)
    }

    private fun calibrationStatus(result: JSONObject) = CalibrationStatus(
        completePairs = result.getInt("complete_pairs"),
        calibrated = result.getBoolean("calibrated"),
        reprojectionError = if (result.isNull("reprojection_error")) null
        else result.getDouble("reprojection_error").toFloat()
    )

    suspend fun closeSession(sessionId: String) {
        execute(
            Request.Builder()
                .url("${backendUrl.trimEnd('/')}/api/v1/sessions/$sessionId/close")
                .post("{}".toRequestBody(jsonType)).build()
        )
    }

    suspend fun requestFeedback(sessionId: String): String {
        val result = execute(
            Request.Builder()
                .url("${backendUrl.trimEnd('/')}/api/v1/sessions/$sessionId/feedback")
                .post(JSONObject().put("language", "English").toString().toRequestBody(jsonType))
                .build()
        )
        return result.getString("text")
    }

    suspend fun generateSummary(sessionId: String): String? {
        val result = execute(
            Request.Builder()
                .url("${backendUrl.trimEnd('/')}/api/v1/sessions/$sessionId/summary")
                .post(JSONObject().put("language", "English").toString().toRequestBody(jsonType))
                .build()
        )
        return result.optString("ai_summary").ifBlank { null }
    }

    fun openPoseSocket(
        sessionId: String,
        deviceId: String,
        onOpen: () -> Unit,
        onResult: (LivePoseResult) -> Unit,
        onClosed: (Throwable?) -> Unit
    ): WebSocket {
        val wsUrl = backendUrl.trimEnd('/').replaceFirst("http", "ws") +
            "/api/v1/ws/sessions/$sessionId"
        return http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    JSONObject()
                        .put("schema_version", 1)
                        .put("type", "device.hello")
                        .put("session_id", sessionId)
                        .put("device_id", deviceId)
                        .put("role", "device")
                        .toString()
                )
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (json.optString("type") != "pose.result") return
                val rawJoints = json.getJSONObject("joints_3d")
                val joints = rawJoints.keys().asSequence().associate { id ->
                    val point = rawJoints.getJSONObject(id)
                    id.toInt() to Triple(
                        point.getDouble("x").toFloat(),
                        point.getDouble("y").toFloat(),
                        point.getDouble("z").toFloat()
                    )
                }
                val metadata = json.getJSONObject("metadata")
                onResult(
                    LivePoseResult(
                        joints = joints,
                        primaryAngleDegrees = if (json.isNull("primary_angle_degrees")) null
                        else json.getDouble("primary_angle_degrees").toFloat(),
                        repCount = json.getInt("rep_count"),
                        movementState = json.getString("movement_state"),
                        formQuality = json.getString("form_quality"),
                        reprojectionError = if (metadata.isNull("reprojection_error")) null
                        else metadata.getDouble("reprojection_error").toFloat()
                    )
                )
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                onClosed(error)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed(null)
            }
        })
    }
}
