package com.yourbusiness.formfusion.pose

import android.graphics.Bitmap

/**
 * A single detected person, in the pixel space of the bitmap passed to [PersonDetector.detect].
 */
data class BoundingBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val score: Float
)

/**
 * Contract for the person-detection stage that runs ahead of pose estimation.
 * Swap the implementation to change the detector backend without touching any caller.
 */
interface PersonDetector {
    fun detect(bitmap: Bitmap): List<BoundingBox>
    fun initialize(): Boolean
    fun release()
}

class StubPersonDetector : PersonDetector {
    override fun detect(bitmap: Bitmap): List<BoundingBox> = emptyList()
    override fun initialize(): Boolean = true
    override fun release() {}
}
