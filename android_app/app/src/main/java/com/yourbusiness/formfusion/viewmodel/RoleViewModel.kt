package com.yourbusiness.formfusion.viewmodel

import com.yourbusiness.formfusion.network.Role
import com.yourbusiness.formfusion.network.SessionManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed interface RoleEvent {
    data object NavigateToJoinLobby : RoleEvent
    data class ShowError(val message: String) : RoleEvent
}

class RoleViewModel : BaseViewModel() {
    private val _events = Channel<RoleEvent>(Channel.BUFFERED)
    val events: Flow<RoleEvent> = _events.receiveAsFlow()

    fun onQrScanned(rawPayload: String) {
        viewModelScope.launch {
            runCatching {
                val json = JSONObject(rawPayload)
                SessionManager.reset()
                SessionManager.role = Role.FOLLOWER
                SessionManager.backendUrl = json.getString("backendUrl")
                SessionManager.sessionId = json.getString("sessionId")
                SessionManager.joinCode = json.getString("joinCode")
            }.onSuccess {
                _events.send(RoleEvent.NavigateToJoinLobby)
            }.onFailure {
                _events.send(RoleEvent.ShowError("This is not a valid FormFusion session QR."))
            }
        }
    }

    fun onScanFailed(error: Exception) {
        viewModelScope.launch {
            _events.send(RoleEvent.ShowError("Scan failed: ${error.message}"))
        }
    }
}
