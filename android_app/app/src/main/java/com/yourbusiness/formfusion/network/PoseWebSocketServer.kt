package com.yourbusiness.formfusion.network

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * Runs on the host phone. Tracks connected followers via [SessionManager.connectedCount]
 * and broadcasts session lifecycle events (COUNT, START) to everyone connected.
 */
class PoseWebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

    init {
        isReuseAddr = true
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        SessionManager.connectedCount.value += 1
        broadcastCount()
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        SessionManager.connectedCount.value = (SessionManager.connectedCount.value - 1).coerceAtLeast(0)
        broadcastCount()
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Log.d("PoseWebSocketServer", "received: $message")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("PoseWebSocketServer", "server error", ex)
    }

    override fun onStart() {
        Log.d("PoseWebSocketServer", "server started on port $port")
    }

    private fun broadcastCount() {
        val payload = JSONObject().apply {
            put("type", "COUNT")
            put("count", SessionManager.connectedCount.value)
        }.toString()
        broadcast(payload)
    }

    fun broadcastStart() {
        val payload = JSONObject().apply {
            put("type", "START")
            put("sessionId", SessionManager.sessionId)
        }.toString()
        broadcast(payload)
    }
}
