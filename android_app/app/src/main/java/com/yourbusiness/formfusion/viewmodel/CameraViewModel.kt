package com.yourbusiness.formfusion.viewmodel

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.yourbusiness.formfusion.pose.PersonDetector
import com.yourbusiness.formfusion.pose.PersonPose
import com.yourbusiness.formfusion.pose.PoseEstimator
import com.yourbusiness.formfusion.pose.RtmDetDetector
import com.yourbusiness.formfusion.pose.RtmPoseOnnxEstimator
import com.yourbusiness.formfusion.network.Role
import com.yourbusiness.formfusion.network.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class CameraUiState(
    val frameCount: Int = 0,
    val lastPersonCount: Int = 0,
    val lastKeypointCount: Int = 0,
    val lastPersons: List<PersonPose> = emptyList(),
    val lastImageWidth: Int = 0,
    val lastImageHeight: Int = 0,
    val primaryAngleDegrees: Float? = null,
    val repCount: Int = 0,
    val movementState: String = "waiting",
    val formQuality: String = "unknown",
    val reprojectionError: Float? = null,
    val socketConnected: Boolean = false,
    val aiFeedback: String? = null,
    val networkError: String? = null,
    val worldJoints: Map<Int, Triple<Float, Float, Float>> = emptyMap(),
    val isSessionActive: Boolean = true
)

sealed interface CameraEvent {
    data class SessionEnded(val durationSeconds: Long) : CameraEvent
}

/**
 * Owns the full detection + pose pipeline (RTMDet -> RTMPose) and frame counting.
 * CameraScreen only wires the CameraX analyzer to [personDetector]/[poseEstimator] and
 * [onFrameAnalyzed] — no pipeline or counting logic in the View.
 *
 * [personDetector]/[poseEstimator] default to the real RTMDet/RTMPose implementations but
 * are constructor-injectable so tests can substitute fakes without touching TFLite/ONNX
 * Runtime or Android's Bitmap/Context. [elapsedRealtime] defaults to the real clock but is
 * injectable too, so session-duration tests don't depend on wall-clock timing.
 */
class CameraViewModel(
    context: Context,
    val personDetector: PersonDetector = RtmDetDetector(context.applicationContext),
    val poseEstimator: PoseEstimator = RtmPoseOnnxEstimator(context.applicationContext),
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() }
) : BaseViewModel() {

    companion object {
        private const val TAG = "CameraViewModel"
    }

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _events = Channel<CameraEvent>(Channel.BUFFERED)
    val events: Flow<CameraEvent> = _events.receiveAsFlow()

    // Raw per-frame pipeline output collected for the running session, dumped to
    // Logcat by endSession() — for now this is the only way to check the models'
    // output, since there's no on-screen box/skeleton overlay yet.
    private val sessionFrames = mutableListOf<List<PersonPose>>()

    // Session "start" is when this screen/ViewModel is created — there's no separate
    // Start button, the camera begins analyzing immediately on entering the screen.
    private val sessionStartTime = elapsedRealtime()
    private var lastSentAtMs = 0L
    private var frameId = 0

    init {
        viewModelScope.launch(Dispatchers.Default) {
            personDetector.initialize()
            poseEstimator.initialize()
        }
        if (SessionManager.sessionId.isNotBlank()) {
            SessionManager.connectSocket(context.applicationContext)
        }
        viewModelScope.launch {
            SessionManager.liveResult.collect { result ->
                if (result != null) {
                    _uiState.update {
                        it.copy(
                            primaryAngleDegrees = result.primaryAngleDegrees,
                            repCount = result.repCount,
                            movementState = result.movementState,
                            formQuality = result.formQuality,
                            reprojectionError = result.reprojectionError,
                            worldJoints = result.joints
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            SessionManager.socketConnected.collect { connected ->
                _uiState.update { it.copy(socketConnected = connected) }
            }
        }
        viewModelScope.launch {
            SessionManager.connectionError.collect { error ->
                _uiState.update { it.copy(networkError = error) }
            }
        }
        viewModelScope.launch {
            SessionManager.aiFeedback.collect { feedback ->
                _uiState.update { it.copy(aiFeedback = feedback) }
            }
        }
    }

    fun onFrameAnalyzed(persons: List<PersonPose>, imageWidth: Int, imageHeight: Int) {
        if (!_uiState.value.isSessionActive) return
        sessionFrames.add(persons)
        _uiState.update {
            it.copy(
                frameCount = it.frameCount + 1,
                lastPersonCount = persons.size,
                lastKeypointCount = persons.sumOf { p -> p.landmarks.size },
                lastPersons = persons,
                lastImageWidth = imageWidth,
                lastImageHeight = imageHeight
            )
        }
        sendFrameIfDue(persons, imageWidth, imageHeight)
    }

    private fun sendFrameIfDue(persons: List<PersonPose>, imageWidth: Int, imageHeight: Int) {
        if (SessionManager.sessionId.isBlank()) return
        val person = persons.firstOrNull { it.landmarks.isNotEmpty() } ?: return
        val now = System.currentTimeMillis()
        if (now - lastSentAtMs < 80) return
        lastSentAtMs = now
        frameId += 1
        val keypoints = JSONArray()
        person.landmarks.forEach { point ->
            keypoints.put(
                JSONObject()
                    .put("id", point.id)
                    .put("x", point.x)
                    .put("y", point.y)
                    .put("confidence", point.visibility.coerceIn(0f, 1f))
            )
        }
        val payload = JSONObject()
            .put("schema_version", 1)
            .put("type", "pose.frame")
            .put("session_id", SessionManager.sessionId)
            .put("device_id", SessionManager.deviceId)
            .put("frame_id", frameId)
            .put("captured_at_ms", now)
            .put(
                "image",
                JSONObject()
                    .put("width", imageWidth)
                    .put("height", imageHeight)
                    .put("rotation_degrees", 0)
                    .put("mirrored", false)
            )
            .put(
                "person",
                JSONObject().put("track_id", 1).put("keypoints", keypoints)
            )
        SessionManager.sendPoseFrame(payload.toString())
    }

    fun requestAiFeedback() {
        if (SessionManager.sessionId.isBlank()) return
        viewModelScope.launch {
            runCatching { SessionManager.api().requestFeedback(SessionManager.sessionId) }
                .onSuccess { SessionManager.aiFeedback.value = it }
                .onFailure { _uiState.update { state -> state.copy(networkError = it.message) } }
        }
    }

    fun endSession() {
        if (!_uiState.value.isSessionActive) return
        _uiState.update { it.copy(isSessionActive = false) }

        val durationSeconds = (elapsedRealtime() - sessionStartTime) / 1000
        viewModelScope.launch {
            if (SessionManager.sessionId.isNotBlank() && SessionManager.role == Role.HOST) {
                runCatching { SessionManager.api().closeSession(SessionManager.sessionId) }
                runCatching { SessionManager.api().generateSummary(SessionManager.sessionId) }
                    .onSuccess { SessionManager.aiSummary.value = it }
            }
            SessionManager.disconnectSocket()
            _events.send(CameraEvent.SessionEnded(durationSeconds))
        }

        Log.i(TAG, "===== Session ended: ${sessionFrames.size} frames analyzed, duration=${durationSeconds}s =====")
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
