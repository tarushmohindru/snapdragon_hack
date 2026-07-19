package com.yourbusiness.formfusion.viewmodel

import android.content.Context
import android.graphics.Bitmap
import com.yourbusiness.formfusion.pose.BoundingBox
import com.yourbusiness.formfusion.pose.LandmarkPoint
import com.yourbusiness.formfusion.pose.PersonDetector
import com.yourbusiness.formfusion.pose.PersonPose
import com.yourbusiness.formfusion.pose.PoseEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

private class FakePersonDetector : PersonDetector {
    override fun detect(bitmap: Bitmap): List<BoundingBox> = emptyList()
    override fun initialize(): Boolean = true
    override fun release() {}
}

private class FakePoseEstimator : PoseEstimator {
    override fun estimate(bitmap: Bitmap, box: BoundingBox): List<LandmarkPoint> = emptyList()
    override fun initialize(): Boolean = true
    override fun release() {}
}

/**
 * Covers CameraViewModel's session/counting state machine using fake detectors — real
 * RtmDetDetector/RtmPoseOnnxEstimator need TFLite/ONNX Runtime native libs and a device,
 * so they're swapped out via the constructor-injection seam instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // BaseViewModel eagerly resolves Dispatchers.Main.immediate when constructed.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        personDetector: PersonDetector = FakePersonDetector(),
        poseEstimator: PoseEstimator = FakePoseEstimator()
    ): CameraViewModel = CameraViewModel(mock(Context::class.java), personDetector, poseEstimator)

    private fun person(keypointCount: Int) = PersonPose(
        box = BoundingBox(0f, 0f, 10f, 10f, 0.9f),
        landmarks = (0 until keypointCount).map { LandmarkPoint(it, 0f, 0f, 0f, 0.5f) }
    )

    @Test
    fun `initial state is zeroed and session active`() {
        val state = newViewModel().uiState.value
        assertEquals(0, state.frameCount)
        assertEquals(0, state.lastPersonCount)
        assertEquals(0, state.lastKeypointCount)
        assertTrue(state.isSessionActive)
    }

    @Test
    fun `onFrameAnalyzed with no persons increments frameCount and zeroes counts`() {
        val vm = newViewModel()
        vm.onFrameAnalyzed(emptyList(), imageWidth = 480, imageHeight = 640)
        val state = vm.uiState.value
        assertEquals(1, state.frameCount)
        assertEquals(0, state.lastPersonCount)
        assertEquals(0, state.lastKeypointCount)
    }

    @Test
    fun `onFrameAnalyzed reflects only the most recent frame's counts, not a running total`() {
        val vm = newViewModel()
        vm.onFrameAnalyzed(listOf(person(5), person(3)), imageWidth = 480, imageHeight = 640)
        vm.onFrameAnalyzed(listOf(person(1)), imageWidth = 480, imageHeight = 640)
        val state = vm.uiState.value
        assertEquals(2, state.frameCount)
        assertEquals(1, state.lastPersonCount)
        assertEquals(1, state.lastKeypointCount)
    }

    @Test
    fun `onFrameAnalyzed sums keypoints across multiple persons in one frame`() {
        val vm = newViewModel()
        vm.onFrameAnalyzed(listOf(person(5), person(3), person(0)), imageWidth = 480, imageHeight = 640)
        val state = vm.uiState.value
        assertEquals(3, state.lastPersonCount)
        assertEquals(8, state.lastKeypointCount)
    }

    @Test
    fun `frameCount accumulates across many frames`() {
        val vm = newViewModel()
        repeat(10) { vm.onFrameAnalyzed(emptyList(), imageWidth = 480, imageHeight = 640) }
        assertEquals(10, vm.uiState.value.frameCount)
    }

    @Test
    fun `endSession flips isSessionActive to false`() {
        val vm = newViewModel()
        vm.endSession()
        assertFalse(vm.uiState.value.isSessionActive)
    }

    @Test
    fun `onFrameAnalyzed is ignored after endSession`() {
        val vm = newViewModel()
        vm.onFrameAnalyzed(listOf(person(2)), imageWidth = 480, imageHeight = 640)
        vm.endSession()
        vm.onFrameAnalyzed(listOf(person(2)), imageWidth = 480, imageHeight = 640)
        assertEquals(1, vm.uiState.value.frameCount)
    }

    @Test
    fun `endSession is idempotent when called twice`() {
        val vm = newViewModel()
        vm.onFrameAnalyzed(listOf(person(2)), imageWidth = 480, imageHeight = 640)
        vm.endSession()
        val stateAfterFirst = vm.uiState.value
        vm.endSession()
        assertEquals(stateAfterFirst, vm.uiState.value)
    }

    @Test
    fun `dispose releases both the person detector and pose estimator`() {
        var detectorReleased = false
        var estimatorReleased = false
        val detector = object : PersonDetector by FakePersonDetector() {
            override fun release() { detectorReleased = true }
        }
        val estimator = object : PoseEstimator by FakePoseEstimator() {
            override fun release() { estimatorReleased = true }
        }

        newViewModel(detector, estimator).dispose()

        assertTrue(detectorReleased)
        assertTrue(estimatorReleased)
    }
}
