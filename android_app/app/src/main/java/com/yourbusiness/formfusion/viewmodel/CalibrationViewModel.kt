package com.yourbusiness.formfusion.viewmodel

import android.content.Context
import com.yourbusiness.formfusion.network.Role
import com.yourbusiness.formfusion.network.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class CalibrationUiState(
    val captureNumber: Int = 0,
    val completePairs: Int = 0,
    val calibrated: Boolean = false,
    val uploading: Boolean = false,
    val finalizing: Boolean = false,
    val reprojectionError: Float? = null,
    val error: String? = null
)

class CalibrationViewModel(private val context: Context) : BaseViewModel() {
    private val _uiState = MutableStateFlow(CalibrationUiState())
    val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive && !_uiState.value.calibrated) {
                refresh()
                delay(2_000)
            }
        }
    }

    fun upload(file: File) {
        val next = _uiState.value.captureNumber + 1
        viewModelScope.launch {
            _uiState.update { it.copy(uploading = true, error = null) }
            runCatching {
                SessionManager.api().uploadCalibrationCapture(
                    SessionManager.sessionId,
                    SessionManager.stableDeviceId(context),
                    "pair-%03d".format(next),
                    file
                )
            }.onSuccess { status ->
                _uiState.update {
                    it.copy(
                        captureNumber = next,
                        completePairs = status.completePairs,
                        calibrated = status.calibrated,
                        reprojectionError = status.reprojectionError,
                        uploading = false
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(uploading = false, error = error.message) }
            }
            file.delete()
        }
    }

    fun finalizeCalibration() {
        if (SessionManager.role != Role.HOST) return
        viewModelScope.launch {
            _uiState.update { it.copy(finalizing = true, error = null) }
            runCatching {
                val devices = SessionManager.api().status(SessionManager.sessionId).deviceIds
                require(devices.size >= 2) { "Two joined cameras are required." }
                SessionManager.api().finalizeCalibration(
                    SessionManager.sessionId,
                    devices[0],
                    devices[1]
                )
            }.onSuccess { status ->
                SessionManager.calibrated.value = status.calibrated
                _uiState.update {
                    it.copy(
                        finalizing = false,
                        calibrated = status.calibrated,
                        reprojectionError = status.reprojectionError
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(finalizing = false, error = error.message) }
            }
        }
    }

    private suspend fun refresh() {
        runCatching { SessionManager.api().calibrationStatus(SessionManager.sessionId) }
            .onSuccess { status ->
                SessionManager.calibrated.value = status.calibrated
                _uiState.update {
                    it.copy(
                        completePairs = status.completePairs,
                        calibrated = status.calibrated,
                        reprojectionError = status.reprojectionError
                    )
                }
            }
    }
}
