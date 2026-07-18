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

/** Owns QR-payload parsing and session setup for the Join path — RoleScreen has no logic of its own. */
class RoleViewModel : BaseViewModel() {

    private val _events = Channel<RoleEvent>(Channel.BUFFERED)
    val events: Flow<RoleEvent> = _events.receiveAsFlow()

    fun onQrScanned(rawPayload: String) {
        viewModelScope.launch {
            try {
                val json = JSONObject(rawPayload)
                SessionManager.reset()
                SessionManager.role = Role.FOLLOWER
                SessionManager.sessionId = json.getString("sessionId")
                SessionManager.token = json.getString("token")
                SessionManager.hostIp = json.getString("host")
                SessionManager.port = json.getInt("port")
                _events.send(RoleEvent.NavigateToJoinLobby)
            } catch (e: Exception) {
                _events.send(RoleEvent.ShowError("Invalid QR code"))
            }
        }
    }

    fun onScanFailed(error: Exception) {
        viewModelScope.launch {
            _events.send(RoleEvent.ShowError("Scan failed: ${error.message}"))
        }
    }
}
