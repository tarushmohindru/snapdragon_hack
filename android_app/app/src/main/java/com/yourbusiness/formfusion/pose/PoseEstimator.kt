package com.yourbusiness.formfusion.pose

import android.graphics.Bitmap

/** One detected person: the RTMDet box plus the RTMPose keypoints estimated inside it. */
data class PersonPose(
    val box: BoundingBox,
    val landmarks: List<LandmarkPoint>
)

/**
 * Contract for the pose-estimation stage that runs on each person crop produced by
 * [PersonDetector]. Swap the implementation to change the pose backend without touching
 * any caller.
 */
interface PoseEstimator {
    fun estimate(bitmap: Bitmap, box: BoundingBox): List<LandmarkPoint>
    fun initialize(): Boolean
    fun release()
}

class StubPoseEstimator : PoseEstimator {
    override fun estimate(bitmap: Bitmap, box: BoundingBox): List<LandmarkPoint> = emptyList()
    override fun initialize(): Boolean = true
    override fun release() {}
}
