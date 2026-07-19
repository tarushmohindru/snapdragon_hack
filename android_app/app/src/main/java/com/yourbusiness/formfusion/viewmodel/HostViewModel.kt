package com.yourbusiness.formfusion.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.yourbusiness.formfusion.network.QrCodeGenerator
import com.yourbusiness.formfusion.network.Role
import com.yourbusiness.formfusion.network.SessionManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class HostUiState(
    val sessionId: String = "",
    val joinCode: String = "",
    val qrBitmap: Bitmap? = null,
    val connectedCount: Int = 0,
    val canStartSession: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null
)

sealed interface HostEvent { data object NavigateToCamera : HostEvent }

class HostViewModel(private val context: Context) : BaseViewModel() {
    private val _uiState = MutableStateFlow(HostUiState())
    val uiState: StateFlow<HostUiState> = _uiState.asStateFlow()
    private val _events = Channel<HostEvent>(Channel.BUFFERED)
    val events: Flow<HostEvent> = _events.receiveAsFlow()

    init { createSession() }

    fun createSession() {
        viewModelScope.launch {
            _uiState.value = HostUiState(loading = true)
            runCatching {
                SessionManager.reset()
                SessionManager.role = Role.HOST
                val created = SessionManager.api().createSession(SessionManager.exercise)
                SessionManager.sessionId = created.sessionId
                SessionManager.joinCode = created.joinCode
                val deviceId = SessionManager.stableDeviceId(context)
                SessionManager.api().joinSession(
                    created.sessionId,
                    created.joinCode,
                    deviceId,
                    Build.MODEL
                )
                val qr = JSONObject()
                    .put("backendUrl", SessionManager.backendUrl)
                    .put("sessionId", created.sessionId)
                    .put("joinCode", created.joinCode)
                    .toString()
                _uiState.value = HostUiState(
                    sessionId = created.sessionId,
                    joinCode = created.joinCode,
                    qrBitmap = QrCodeGenerator.generate(qr),
                    connectedCount = 1,
                    loading = false
                )
                pollStatus()
            }.onFailure { error ->
                _uiState.value = HostUiState(loading = false, error = error.message)
            }
        }
    }

    private suspend fun pollStatus() {
        while (viewModelScope.isActive && SessionManager.sessionId.isNotBlank()) {
            runCatching { SessionManager.api().status(SessionManager.sessionId) }
                .onSuccess { status ->
                    SessionManager.connectedCount.value = status.deviceIds.size
                    SessionManager.calibrated.value = status.calibrated
                    _uiState.update {
                        it.copy(
                            connectedCount = status.deviceIds.size,
                            canStartSession = status.deviceIds.size >= 2,
                            error = null
                        )
                    }
                }
            delay(1_500)
        }
    }

    fun onStartSessionClicked() {
        if (!_uiState.value.canStartSession) return
        SessionManager.connectSocket(context)
        viewModelScope.launch { _events.send(HostEvent.NavigateToCamera) }
    }
}
