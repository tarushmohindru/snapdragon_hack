package com.yourbusiness.formfusion.pose

import android.content.Context
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import kotlin.math.abs

/**
 * Covers the pure math in RtmPoseOnnxEstimator (aspect-ratio box fitting, SimCC argmax +
 * softmax decode) without touching ONNX Runtime — those internal functions only need a
 * Bitmap for its width/height, so a Mockito mock stands in for a real one.
 */
class RtmPoseOnnxEstimatorTest {

    private lateinit var estimator: RtmPoseOnnxEstimator

    @Before
    fun setUp() {
        estimator = RtmPoseOnnxEstimator(mock(Context::class.java))
    }

    private fun bitmapOf(width: Int, height: Int): Bitmap {
        val bitmap = mock(Bitmap::class.java)
        `when`(bitmap.width).thenReturn(width)
        `when`(bitmap.height).thenReturn(height)
        return bitmap
    }

    private val targetAspect = 192f / 256f

    // ---- fitBoxToAspectRatio ----

    @Test
    fun `fitBoxToAspectRatio keeps a box already matching the target aspect ratio`() {
        val bitmap = bitmapOf(1000, 1000)
        val box = BoundingBox(400f, 400f, 550f, 600f, 0.9f) // 150x200 = 0.75 aspect already
        val (x1, y1, x2, y2) = estimator.fitBoxToAspectRatio(bitmap, box)
        val aspect = (x2 - x1).toFloat() / (y2 - y1).toFloat()
        assertTrue(abs(aspect - targetAspect) < 0.02f)
    }

    @Test
    fun `fitBoxToAspectRatio grows height for a too-wide box`() {
        val bitmap = bitmapOf(1000, 1000)
        val box = BoundingBox(100f, 100f, 500f, 200f, 0.9f) // 400 wide, 100 tall
        val (x1, y1, x2, y2) = estimator.fitBoxToAspectRatio(bitmap, box)
        val w = x2 - x1
        val h = y2 - y1
        assertTrue(h > 100)
        assertTrue(abs(w.toFloat() / h.toFloat() - targetAspect) < 0.05f)
    }

    @Test
    fun `fitBoxToAspectRatio grows width for a too-tall box`() {
        val bitmap = bitmapOf(1000, 1000)
        val box = BoundingBox(100f, 100f, 200f, 500f, 0.9f) // 100 wide, 400 tall
        val (x1, y1, x2, y2) = estimator.fitBoxToAspectRatio(bitmap, box)
        val w = x2 - x1
        val h = y2 - y1
        assertTrue(w > 100)
        assertTrue(abs(w.toFloat() / h.toFloat() - targetAspect) < 0.05f)
    }

    @Test
    fun `fitBoxToAspectRatio clamps to bitmap bounds for a box bigger than the frame`() {
        val bitmap = bitmapOf(200, 200)
        val box = BoundingBox(-50f, -50f, 250f, 250f, 0.9f)
        val (x1, y1, x2, y2) = estimator.fitBoxToAspectRatio(bitmap, box)
        assertTrue(x1 >= 0 && y1 >= 0)
        assertTrue(x2 <= 200 && y2 <= 200)
        assertTrue(x2 > x1 && y2 > y1)
    }

    @Test
    fun `fitBoxToAspectRatio handles a zero-height box without dividing by zero`() {
        val bitmap = bitmapOf(1000, 1000)
        val box = BoundingBox(100f, 100f, 200f, 100f, 0.9f) // y1 == y2
        val (x1, y1, x2, y2) = estimator.fitBoxToAspectRatio(bitmap, box)
        assertTrue(x2 > x1)
        assertTrue(y2 > y1)
    }

    @Test
    fun `fitBoxToAspectRatio never returns a degenerate rect for a tiny box`() {
        val bitmap = bitmapOf(1000, 1000)
        val box = BoundingBox(500f, 500f, 501f, 501f, 0.9f)
        val (x1, y1, x2, y2) = estimator.fitBoxToAspectRatio(bitmap, box)
        assertTrue(x2 > x1)
        assertTrue(y2 > y1)
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
