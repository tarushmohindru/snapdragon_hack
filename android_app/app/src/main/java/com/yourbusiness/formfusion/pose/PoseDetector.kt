package com.yourbusiness.formfusion.pose

import android.graphics.Bitmap

/**
 * A single detected body keypoint.
 * Landmark count is intentionally flexible — do not assume exactly 33;
 * our own model may output a different number of keypoints.
 */
data class LandmarkPoint(
    val id: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float
)

/**
 * Contract that camera/analysis code depends on. Swap the implementation
 * to change the pose-estimation backend without touching any caller.
 */
interface PoseDetector {
    // Takes an upright bitmap frame + its original dimensions; returns detected body points.
    fun detect(bitmap: Bitmap, imageWidth: Int, imageHeight: Int): List<LandmarkPoint>
}
