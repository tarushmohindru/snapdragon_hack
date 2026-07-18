package com.yourbusiness.formfusion.pose

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Covers the pure math in RtmPoseOnnxEstimator's mmpose top-down pipeline: box -> center/
 * scale (1.25 padding + 192:256 aspect fix), the inverse affine that maps model-input
 * pixels back to original-frame pixels, and the SimCC argmax + softmax decode. None of this
 * touches ONNX Runtime or real Bitmap/Canvas warping (which need a device or Robolectric),
 * only plain floats.
 */
class RtmPoseOnnxEstimatorTest {

    private lateinit var estimator: RtmPoseOnnxEstimator

    @Before
    fun setUp() {
        estimator = RtmPoseOnnxEstimator(mock(Context::class.java))
    }

    private val targetAspect = 192f / 256f

    // ---- computeCenterScale (mmpose GetBBoxCenterScale + aspect fix) ----

    @Test
    fun `computeCenterScale places center at the box center`() {
        val cs = estimator.computeCenterScale(BoundingBox(100f, 100f, 200f, 200f, 0.9f))
        assertEquals(150f, cs.centerX, 1e-3f)
        assertEquals(150f, cs.centerY, 1e-3f)
    }

    @Test
    fun `computeCenterScale applies the 1_25 padding before the aspect fix`() {
        // 100x100 box -> padded 125x125; then aspect-fixed. 125 > 125*0.75 so height grows to
        // 125/0.75 = 166.67, width stays 125 (the padded width).
        val cs = estimator.computeCenterScale(BoundingBox(100f, 100f, 200f, 200f, 0.9f))
        assertEquals(125f, cs.scaleW, 1e-2f)
        assertEquals(166.667f, cs.scaleH, 1e-2f)
    }

    @Test
    fun `computeCenterScale output always has the model's 192 to 256 aspect ratio`() {
        val boxes = listOf(
            BoundingBox(0f, 0f, 100f, 100f, 1f),
            BoundingBox(10f, 10f, 300f, 60f, 1f),   // wide
            BoundingBox(10f, 10f, 60f, 400f, 1f)    // tall
        )
        boxes.forEach { box ->
            val cs = estimator.computeCenterScale(box)
            assertEquals(targetAspect, cs.scaleW / cs.scaleH, 1e-3f)
        }
    }

    @Test
    fun `computeCenterScale grows height for a wide box`() {
        val cs = estimator.computeCenterScale(BoundingBox(100f, 100f, 300f, 150f, 0.9f))
        // padded w=250, h=62.5; wider than target so height grows, width stays 250.
        assertEquals(250f, cs.scaleW, 1e-2f)
        assertTrue(cs.scaleH > 62.5f)
    }

    @Test
    fun `computeCenterScale grows width for a tall box`() {
        val cs = estimator.computeCenterScale(BoundingBox(100f, 100f, 150f, 400f, 0.9f))
        // padded w=62.5, h=375; taller than target so width grows, height stays 375.
        assertEquals(375f, cs.scaleH, 1e-2f)
        assertTrue(cs.scaleW > 62.5f)
    }

    // ---- inputToOriginal (inverse affine) ----

    @Test
    fun `inputToOriginal maps model-input origin to the region's top-left corner`() {
        val cs = CenterScale(centerX = 150f, centerY = 150f, scaleW = 120f, scaleH = 160f)
        val (x, y) = estimator.inputToOriginal(0f, 0f, cs)
        assertEquals(150f - 60f, x, 1e-3f)
        assertEquals(150f - 80f, y, 1e-3f)
    }

    @Test
    fun `inputToOriginal maps the model-input far corner to the region's bottom-right corner`() {
        val cs = CenterScale(centerX = 150f, centerY = 150f, scaleW = 120f, scaleH = 160f)
        val (x, y) = estimator.inputToOriginal(192f, 256f, cs)
        assertEquals(150f + 60f, x, 1e-3f)
        assertEquals(150f + 80f, y, 1e-3f)
    }

    @Test
    fun `inputToOriginal maps the model-input center to the box center`() {
        val cs = estimator.computeCenterScale(BoundingBox(100f, 100f, 200f, 200f, 0.9f))
        val (x, y) = estimator.inputToOriginal(96f, 128f, cs) // 192/2, 256/2
        assertEquals(150f, x, 1e-2f)
        assertEquals(150f, y, 1e-2f)
    }

    @Test
    fun `inputToOriginal is a linear ramp across the input width`() {
        val cs = CenterScale(centerX = 100f, centerY = 100f, scaleW = 192f, scaleH = 256f)
        // scale == input size, center 100,100 -> origin at (4,-28)
        val (x0, _) = estimator.inputToOriginal(0f, 0f, cs)
        val (x1, _) = estimator.inputToOriginal(96f, 0f, cs)
        val (x2, _) = estimator.inputToOriginal(192f, 0f, cs)
        assertEquals(4f, x0, 1e-3f)
        assertEquals(100f, x1, 1e-3f)
        assertEquals(196f, x2, 1e-3f)
    }

    // ---- argmaxSoftmaxScore ----

    @Test
    fun `argmaxSoftmaxScore picks the dominant logit with high confidence`() {
        val logits = FloatArray(10) { -10f }
        logits[3] = 10f
        val (idx, conf) = estimator.argmaxSoftmaxScore(logits)
        assertEquals(3, idx)
        assertTrue(conf > 0.99f)
    }

    @Test
    fun `argmaxSoftmaxScore on uniform logits picks the first index with uniform confidence`() {
        val logits = FloatArray(4) { 2f }
        val (idx, conf) = estimator.argmaxSoftmaxScore(logits)
        assertEquals(0, idx)
        assertEquals(0.25f, conf, 1e-4f)
    }

    @Test
    fun `argmaxSoftmaxScore on a single-element array`() {
        val (idx, conf) = estimator.argmaxSoftmaxScore(floatArrayOf(5f))
        assertEquals(0, idx)
        assertEquals(1f, conf, 1e-5f)
    }

    @Test
    fun `argmaxSoftmaxScore handles all-negative logits`() {
        val (idx, conf) = estimator.argmaxSoftmaxScore(floatArrayOf(-5f, -1f, -3f))
        assertEquals(1, idx)
        assertTrue(conf in 0f..1f)
    }

    @Test
    fun `argmaxSoftmaxScore is numerically stable for extreme-magnitude logits`() {
        val (idx, conf) = estimator.argmaxSoftmaxScore(floatArrayOf(1000f, -1000f, 999f))
        assertEquals(0, idx)
        assertTrue(conf.isFinite())
        assertTrue(conf in 0f..1f)
    }

    @Test
    fun `argmaxSoftmaxScore picks the first occurrence on a tie`() {
        val (idx, _) = estimator.argmaxSoftmaxScore(floatArrayOf(1f, 1f, 1f))
        assertEquals(0, idx)
    }
}
