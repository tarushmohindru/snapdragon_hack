package com.yourbusiness.formfusion.viewmodel

import android.content.Context
import com.yourbusiness.formfusion.network.PoseWebSocketClient
import com.yourbusiness.formfusion.network.SessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI

data class JoinLobbyUiState(
    val sessionId: String = SessionManager.sessionId,
    val connectedCount: Int = 0,
    val showConnectionLost: Boolean = false
)

sealed interface JoinLobbyEvent {
    data object NavigateToCamera : JoinLobbyEvent
}

/**
 * Owns the WebSocket client connection for the Join path. JoinLobbyScreen only reads
 * [uiState] and forwards the retry click to [onRetryClicked] — no socket/JSON code in the View.
 */
class JoinLobbyViewModel(private val appContext: Context) : BaseViewModel() {

    private var client: PoseWebSocketClient? = null
    private var connectionWatchJob: Job? = null
    private var everConnected = false

    private val _uiState = MutableStateFlow(JoinLobbyUiState())
    val uiState: StateFlow<JoinLobbyUiState> = _uiState.asStateFlow()

    private val _events = Channel<JoinLobbyEvent>(Channel.BUFFERED)
    val events: Flow<JoinLobbyEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            SessionManager.connectedCount.collect { count ->
                _uiState.update { it.copy(connectedCount = count) }
            }
        }
        viewModelScope.launch {
            SessionManager.sessionStarted.collect { started ->
                if (started) _events.send(JoinLobbyEvent.NavigateToCamera)
            }
        }
        connect()
    }

    private fun connect() {
        everConnected = false
        _uiState.update { it.copy(showConnectionLost = false) }

        client?.close()
        val newClient = PoseWebSocketClient(
            URI("ws://${SessionManager.hostIp}:${SessionManager.port}"),
            appContext
        )
        client = newClient

        connectionWatchJob?.cancel()
        connectionWatchJob = viewModelScope.launch {
            newClient.isConnected.collect { connected ->
                if (connected) everConnected = true
                _uiState.update { it.copy(showConnectionLost = everConnected && !connected) }
            }
        }

        newClient.connect()
    }

    fun onRetryClicked() {
        connect()
    }

    override fun dispose() {
        connectionWatchJob?.cancel()
        client?.close()
        super.dispose()
    }
}
