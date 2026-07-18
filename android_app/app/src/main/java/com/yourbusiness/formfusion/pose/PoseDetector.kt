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

// ===== TODO(ML) — REPLACE THIS with the real model tomorrow =====
// Create YourModelPoseDetector : PoseDetector that:
//   1) resizes `bitmap` to the model's input size,
//   2) normalizes pixels to the model's expected range,
//   3) runs inference with the model runtime (TFLite/LiteRT, ONNX, or QNN),
//   4) parses the output into List<LandmarkPoint> scaled back to imageWidth/imageHeight.
// Then swap StubPoseDetector for YourModelPoseDetector in CameraScreen. Nothing else changes.
// ================================================================
class StubPoseDetector : PoseDetector {
    override fun detect(bitmap: Bitmap, imageWidth: Int, imageHeight: Int): List<LandmarkPoint> {
        return emptyList()
    }
}
