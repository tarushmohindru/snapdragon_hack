package com.yourbusiness.formfusion.network

import android.content.Context
import android.provider.Settings
import com.yourbusiness.formfusion.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import java.util.UUID

enum class Role { NONE, HOST, FOLLOWER }

object SessionManager {
    var backendUrl: String = BuildConfig.BACKEND_URL
    var sessionId: String = ""
    var joinCode: String = ""
    var deviceId: String = ""
    var role: Role = Role.NONE
    var exercise: String = "bicep_curls"

    val connectedCount = MutableStateFlow(0)
    val calibrated = MutableStateFlow(false)
    val socketConnected = MutableStateFlow(false)
    val liveResult = MutableStateFlow<LivePoseResult?>(null)
    val connectionError = MutableStateFlow<String?>(null)
    val aiFeedback = MutableStateFlow<String?>(null)
    val aiSummary = MutableStateFlow<String?>(null)

    private var socket: WebSocket? = null
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socketGeneration = 0
    private var reconnectAttempt = 0
    private var allowReconnect = false

    fun api() = BackendApi(backendUrl)

    fun stableDeviceId(context: Context): String {
        if (deviceId.isNotBlank()) return deviceId
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        val fallback = context.getSharedPreferences("formfusion", Context.MODE_PRIVATE)
            .getString("device_id", null)
            ?: UUID.randomUUID().toString().also {
                context.getSharedPreferences("formfusion", Context.MODE_PRIVATE)
                    .edit().putString("device_id", it).apply()
            }
        deviceId = "android-${androidId.ifBlank { fallback }.take(64)}"
        return deviceId
    }

    fun connectSocket(context: Context) {
        if (sessionId.isBlank()) return
        val id = stableDeviceId(context)
        allowReconnect = true
        val generation = ++socketGeneration
        socket?.cancel()
        connectionError.value = null
        aiFeedback.value = null
        aiSummary.value = null
        socket = api().openPoseSocket(
            sessionId = sessionId,
            deviceId = id,
            onOpen = {
                if (generation == socketGeneration) {
                    reconnectAttempt = 0
                    socketConnected.value = true
                }
            },
            onResult = { liveResult.value = it },
            onClosed = { error ->
                if (generation == socketGeneration) {
                    socketConnected.value = false
                    connectionError.value = error?.message
                    socket = null
                    if (allowReconnect && sessionId.isNotBlank()) {
                        val delayMs = (1_000L shl reconnectAttempt.coerceAtMost(4))
                            .coerceAtMost(15_000L)
                        reconnectAttempt += 1
                        networkScope.launch {
                            delay(delayMs)
                            if (allowReconnect && generation == socketGeneration) {
                                connectSocket(context.applicationContext)
                            }
                        }
                    }
                }
            }
        )
    }

    fun sendPoseFrame(payload: String): Boolean = socket?.send(payload) == true

    fun disconnectSocket() {
        allowReconnect = false
        socketGeneration += 1
        socket?.close(1000, "session complete")
        socket = null
        socketConnected.value = false
    }

    fun reset() {
        disconnectSocket()
        sessionId = ""
        joinCode = ""
        role = Role.NONE
        connectedCount.value = 0
        calibrated.value = false
        liveResult.value = null
        connectionError.value = null
    }
}
