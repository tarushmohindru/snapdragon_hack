package com.yourbusiness.formfusion.viewmodel

import android.graphics.Bitmap
import com.yourbusiness.formfusion.network.PoseWebSocketServer
import com.yourbusiness.formfusion.network.QrCodeGenerator
import com.yourbusiness.formfusion.network.Role
import com.yourbusiness.formfusion.network.SessionManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class HostUiState(
    val sessionId: String = "",
    val qrBitmap: Bitmap? = null,
    val connectedCount: Int = 0,
    val canStartSession: Boolean = false
)

sealed interface HostEvent {
    data object NavigateToCamera : HostEvent
}

/**
 * Owns the session lifecycle for the Host path: generates the session, starts the
 * WebSocket server, builds the QR payload, and tracks connected followers.
 * HostScreen only ever reads [uiState] and forwards clicks to [onStartSessionClicked].
 */
class HostViewModel : BaseViewModel() {

    private val server: PoseWebSocketServer

    private val _uiState = MutableStateFlow(HostUiState())
    val uiState: StateFlow<HostUiState> = _uiState.asStateFlow()

    private val _events = Channel<HostEvent>(Channel.BUFFERED)
    val events: Flow<HostEvent> = _events.receiveAsFlow()

    init {
        SessionManager.reset()
        SessionManager.role = Role.HOST
        SessionManager.generateSession()
        SessionManager.hostIp = SessionManager.getLocalIpAddress()

        server = PoseWebSocketServer(SessionManager.port)

        val qrPayload = JSONObject().apply {
            put("sessionId", SessionManager.sessionId)
            put("token", SessionManager.token)
            put("host", SessionManager.hostIp)
            put("port", SessionManager.port)
        }.toString()

        _uiState.value = HostUiState(
            sessionId = SessionManager.sessionId,
            qrBitmap = QrCodeGenerator.generate(qrPayload)
        )

        viewModelScope.launch {
            SessionManager.connectedCount.collect { count ->
                _uiState.update { it.copy(connectedCount = count, canStartSession = count >= 1) }
            }
        }

        server.start()
    }

    fun onStartSessionClicked() {
        server.broadcastStart()
        viewModelScope.launch { _events.send(HostEvent.NavigateToCamera) }
    }

    override fun dispose() {
        try {
            server.stop()
        } catch (e: Exception) {
            // best-effort shutdown
        }
        super.dispose()
    }
}
