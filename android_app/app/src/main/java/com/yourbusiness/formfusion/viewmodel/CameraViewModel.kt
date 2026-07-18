package com.yourbusiness.formfusion.viewmodel

import com.yourbusiness.formfusion.pose.LandmarkPoint
import com.yourbusiness.formfusion.pose.PoseDetector
import com.yourbusiness.formfusion.pose.StubPoseDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CameraUiState(
    val frameCount: Int = 0,
    val lastLandmarkCount: Int = 0
)

/**
 * Owns detector selection and frame/landmark counting. CameraScreen only wires the
 * CameraX analyzer to [detector] and [onFrameAnalyzed] — no counting logic in the View.
 */
class CameraViewModel : BaseViewModel() {

    // ===== TODO(ML): swap StubPoseDetector for RtmPoseDetector once libpose.so + .bin assets land =====
    val detector: PoseDetector = StubPoseDetector()

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    fun onFrameAnalyzed(landmarks: List<LandmarkPoint>) {
        _uiState.update {
            it.copy(frameCount = it.frameCount + 1, lastLandmarkCount = landmarks.size)
        }
    }
}
