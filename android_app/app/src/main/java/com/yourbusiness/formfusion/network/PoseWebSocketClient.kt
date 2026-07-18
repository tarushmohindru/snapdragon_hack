package com.yourbusiness.formfusion.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/**
 * Runs on a follower phone. Connects to the host's [PoseWebSocketServer], joins the
 * session, and observes session lifecycle events (COUNT, START).
 */
class PoseWebSocketClient(
    uri: URI,
    private val context: Context
) : WebSocketClient(uri) {

    val isConnected = MutableStateFlow(false)

    override fun onOpen(handshakedata: ServerHandshake?) {
        isConnected.value = true
        val payload = JSONObject().apply {
            put("type", "JOIN")
            put("token", SessionManager.token)
            put("phoneId", getOrCreatePhoneId(context))
        }.toString()
        send(payload)
    }

    override fun onMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                "START" -> SessionManager.sessionStarted.value = true
                "COUNT" -> SessionManager.connectedCount.value = json.optInt("count", 0)
            }
        } catch (e: Exception) {
            Log.e("PoseWebSocketClient", "malformed message: $message", e)
        }
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        isConnected.value = false
    }

    override fun onError(ex: Exception) {
        isConnected.value = false
        Log.e("PoseWebSocketClient", "client error", ex)
    }

    /** Not used yet — wired up now for the landmark-streaming feature. */
    fun sendLandmarks(json: String) {
        if (isOpen) send(json)
    }

    companion object {
        private const val PREFS_NAME = "formfusion_prefs"
        private const val KEY_PHONE_ID = "phone_id"

        fun getOrCreatePhoneId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var id = prefs.getString(KEY_PHONE_ID, null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_PHONE_ID, id).apply()
            }
            return id
        }
    }
}
