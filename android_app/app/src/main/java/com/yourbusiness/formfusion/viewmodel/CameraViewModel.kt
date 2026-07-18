package com.yourbusiness.formfusion.viewmodel

import android.content.Context
import android.util.Log
import com.yourbusiness.formfusion.pose.PersonDetector
import com.yourbusiness.formfusion.pose.PersonPose
import com.yourbusiness.formfusion.pose.PoseEstimator
import com.yourbusiness.formfusion.pose.RtmDetDetector
import com.yourbusiness.formfusion.pose.RtmPoseOnnxEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CameraUiState(
    val frameCount: Int = 0,
    val lastPersonCount: Int = 0,
    val lastKeypointCount: Int = 0,
    val isSessionActive: Boolean = true
)

/**
 * Owns the full detection + pose pipeline (RTMDet -> RTMPose) and frame counting.
 * CameraScreen only wires the CameraX analyzer to [personDetector]/[poseEstimator] and
 * [onFrameAnalyzed] — no pipeline or counting logic in the View.
 *
 * [personDetector]/[poseEstimator] default to the real RTMDet/RTMPose implementations but
 * are constructor-injectable so tests can substitute fakes without touching TFLite/ONNX
 * Runtime or Android's Bitmap/Context.
 */
class CameraViewModel(
    context: Context,
    val personDetector: PersonDetector = RtmDetDetector(context.applicationContext),
    val poseEstimator: PoseEstimator = RtmPoseOnnxEstimator(context.applicationContext)
) : BaseViewModel() {

    companion object {
        private const val TAG = "CameraViewModel"
    }

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    // Raw per-frame pipeline output collected for the running session, dumped to
    // Logcat by endSession() — for now this is the only way to check the models'
    // output, since there's no on-screen box/skeleton overlay yet.
    private val sessionFrames = mutableListOf<List<PersonPose>>()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            personDetector.initialize()
            poseEstimator.initialize()
        }
    }

    fun onFrameAnalyzed(persons: List<PersonPose>) {
        if (!_uiState.value.isSessionActive) return
        sessionFrames.add(persons)
        _uiState.update {
            it.copy(
                frameCount = it.frameCount + 1,
                lastPersonCount = persons.size,
                lastKeypointCount = persons.sumOf { p -> p.landmarks.size }
            )
        }
    }

    fun endSession() {
        if (!_uiState.value.isSessionActive) return
        _uiState.update { it.copy(isSessionActive = false) }

        Log.i(TAG, "===== Session ended: ${sessionFrames.size} frames analyzed =====")
        sessionFrames.forEachIndexed { frameIndex, persons ->
            if (persons.isEmpty()) {
                Log.i(TAG, "frame $frameIndex: no persons detected")
            } else {
                persons.forEachIndexed { personIndex, person ->
                    val box = person.box
                    val keypoints = person.landmarks.joinToString(separator = " ") {
                        "${it.id}:${"%.1f".format(it.x)},${"%.1f".format(it.y)},${"%.2f".format(it.visibility)}"
                    }
                    Log.i(
                        TAG,
                        "frame $frameIndex person[$personIndex] score=${"%.2f".format(box.score)} " +
                            "box=(${"%.1f".format(box.x1)}, ${"%.1f".format(box.y1)}, " +
                            "${"%.1f".format(box.x2)}, ${"%.1f".format(box.y2)}) keypoints=[$keypoints]"
                    )
                }
            }
        }
        Log.i(TAG, "===== End of RTMDet + RTMPose output =====")
    }

    override fun dispose() {
        personDetector.release()
        poseEstimator.release()
        super.dispose()
    }
}
