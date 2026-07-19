package com.yourbusiness.formfusion.viewmodel

import android.content.Context
import android.os.Build
import com.yourbusiness.formfusion.network.SessionManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JoinLobbyUiState(
    val sessionId: String = SessionManager.sessionId,
    val connectedCount: Int = 0,
    val loading: Boolean = true,
    val showConnectionLost: Boolean = false,
    val error: String? = null
)

sealed interface JoinLobbyEvent { data object NavigateToCamera : JoinLobbyEvent }

class JoinLobbyViewModel(private val appContext: Context) : BaseViewModel() {
    private val _uiState = MutableStateFlow(JoinLobbyUiState())
    val uiState: StateFlow<JoinLobbyUiState> = _uiState.asStateFlow()
    private val _events = Channel<JoinLobbyEvent>(Channel.BUFFERED)
    val events: Flow<JoinLobbyEvent> = _events.receiveAsFlow()

    init { connect() }

    private fun connect() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null, showConnectionLost = false) }
            runCatching {
                val id = SessionManager.stableDeviceId(appContext)
                SessionManager.api().joinSession(
                    SessionManager.sessionId,
                    SessionManager.joinCode,
                    id,
                    Build.MODEL
                )
                val status = SessionManager.api().status(SessionManager.sessionId)
                SessionManager.connectedCount.value = status.deviceIds.size
                SessionManager.calibrated.value = status.calibrated
                SessionManager.connectSocket(appContext)
                _uiState.update {
                    it.copy(connectedCount = status.deviceIds.size, loading = false)
                }
                _events.send(JoinLobbyEvent.NavigateToCamera)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(loading = false, showConnectionLost = true, error = error.message)
                }
            }
        }
    }

    fun onRetryClicked() = connect()
}
