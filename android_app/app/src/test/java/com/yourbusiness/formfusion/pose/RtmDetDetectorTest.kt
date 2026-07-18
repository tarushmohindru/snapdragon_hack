package com.yourbusiness.formfusion.pose

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Covers the pure post-processing math in RtmDetDetector (IoU, NMS, box unscaling) without
 * touching TFLite/QNN — those internal functions don't use the Context, so an unconfigured
 * mock is enough to construct the detector.
 */
class RtmDetDetectorTest {

    private lateinit var detector: RtmDetDetector

    @Before
    fun setUp() {
        detector = RtmDetDetector(mock(Context::class.java))
    }

    private fun box(x1: Float, y1: Float, x2: Float, y2: Float, score: Float = 1f) =
        BoundingBox(x1, y1, x2, y2, score)

    // ---- iou ----

    @Test
    fun `iou of identical boxes is 1`() {
        val a = box(0f, 0f, 10f, 10f)
        assertEquals(1f, detector.iou(a, a), 1e-5f)
    }

    @Test
    fun `iou of non-overlapping boxes is 0`() {
        val a = box(0f, 0f, 10f, 10f)
        val b = box(20f, 20f, 30f, 30f)
        assertEquals(0f, detector.iou(a, b), 1e-5f)
    }

    @Test
    fun `iou of partially overlapping boxes matches hand-computed value`() {
        val a = box(0f, 0f, 10f, 10f) // area 100
        val b = box(5f, 5f, 15f, 15f) // area 100, intersection 5x5=25, union=175
        assertEquals(25f / 175f, detector.iou(a, b), 1e-5f)
    }

    @Test
    fun `iou when one box fully contains another`() {
        val outer = box(0f, 0f, 10f, 10f) // area 100
        val inner = box(2f, 2f, 4f, 4f) // area 4
        assertEquals(4f / 100f, detector.iou(outer, inner), 1e-5f)
    }

    @Test
    fun `iou is 0 for a degenerate zero-width box`() {
        val a = box(0f, 0f, 10f, 10f)
        val zeroWidth = box(5f, 0f, 5f, 10f) // x1 == x2
        assertEquals(0f, detector.iou(a, zeroWidth), 1e-5f)
    }

    @Test
    fun `iou is symmetric`() {
        val a = box(0f, 0f, 10f, 10f)
        val b = box(5f, 5f, 15f, 15f)
        assertEquals(detector.iou(a, b), detector.iou(b, a), 1e-5f)
    }

    // ---- nonMaxSuppression ----

    @Test
    fun `nms of empty list returns empty list`() {
        assertTrue(detector.nonMaxSuppression(emptyList()).isEmpty())
    }

    @Test
    fun `nms keeps a single box unchanged`() {
        val a = box(0f, 0f, 10f, 10f, 0.9f)
        assertEquals(listOf(a), detector.nonMaxSuppression(listOf(a)))
    }

    @Test
    fun `nms suppresses a lower-score near-duplicate of the same box`() {
        val high = box(0f, 0f, 10f, 10f, 0.9f)
        val low = box(0.5f, 0.5f, 10.5f, 10.5f, 0.5f) // near-identical, high IoU
        assertEquals(listOf(high), detector.nonMaxSuppression(listOf(low, high)))
    }

    @Test
    fun `nms keeps both boxes when they do not overlap`() {
        val a = box(0f, 0f, 10f, 10f, 0.9f)
        val b = box(100f, 100f, 110f, 110f, 0.8f)
        val result = detector.nonMaxSuppression(listOf(a, b))
        assertEquals(2, result.size)
        assertTrue(result.contains(a))
        assertTrue(result.contains(b))
    }

    @Test
    fun `nms result is sorted by descending score`() {
        val low = box(0f, 0f, 10f, 10f, 0.3f)
        val mid = box(100f, 100f, 110f, 110f, 0.6f)
        val high = box(200f, 200f, 210f, 210f, 0.9f)
        assertEquals(listOf(high, mid, low), detector.nonMaxSuppression(listOf(low, high, mid)))
    }

    @Test
    fun `nms keeps a box whose overlap is below the iou threshold`() {
        val a = box(0f, 0f, 10f, 10f, 0.9f) // area 100
        val b = box(0f, 4f, 10f, 14f, 0.5f) // intersection 10x6=60, union 140, iou=0.4286 < 0.45
        assertEquals(2, detector.nonMaxSuppression(listOf(a, b)).size)
    }

    @Test
    fun `nms suppresses a box whose overlap is above the iou threshold`() {
        val a = box(0f, 0f, 10f, 10f, 0.9f) // area 100
        val b = box(0f, 2f, 10f, 12f, 0.5f) // intersection 10x8=80, union 120, iou=0.667 > 0.45
        assertEquals(listOf(a), detector.nonMaxSuppression(listOf(a, b)))
    }

    // ---- unscaleBox ----

    @Test
    fun `unscaleBox divides coordinates by scale`() {
        val result = detector.unscaleBox(box(64f, 128f, 192f, 256f, 0.7f), scale = 0.5f, bitmapWidth = 1000, bitmapHeight = 1000)
        assertEquals(128f, result.x1, 1e-4f)
        assertEquals(256f, result.y1, 1e-4f)
        assertEquals(384f, result.x2, 1e-4f)
        assertEquals(512f, result.y2, 1e-4f)
        assertEquals(0.7f, result.score, 1e-4f)
    }

    @Test
    fun `unscaleBox is a passthrough when scale is 1 and box is within bounds`() {
        val input = box(10f, 20f, 30f, 40f, 0.8f)
        val result = detector.unscaleBox(input, scale = 1f, bitmapWidth = 1000, bitmapHeight = 1000)
        assertEquals(input.x1, result.x1, 1e-4f)
        assertEquals(input.y1, result.y1, 1e-4f)
        assertEquals(input.x2, result.x2, 1e-4f)
        assertEquals(input.y2, result.y2, 1e-4f)
    }

    @Test
    fun `unscaleBox clamps coordinates exceeding the bitmap bounds`() {
        val result = detector.unscaleBox(box(0f, 0f, 2000f, 2000f, 0.6f), scale = 0.5f, bitmapWidth = 480, bitmapHeight = 640)
        // divided by 0.5 -> (0,0,4000,4000), must clamp to the bitmap's own bounds
        assertEquals(0f, result.x1, 1e-4f)
        assertEquals(0f, result.y1, 1e-4f)
        assertEquals(480f, result.x2, 1e-4f)
        assertEquals(640f, result.y2, 1e-4f)
    }

    @Test
    fun `unscaleBox clamps negative coordinates to 0`() {
        val result = detector.unscaleBox(box(-100f, -50f, 10f, 10f, 0.4f), scale = 1f, bitmapWidth = 480, bitmapHeight = 640)
        assertEquals(0f, result.x1, 1e-4f)
        assertEquals(0f, result.y1, 1e-4f)
    }
}
