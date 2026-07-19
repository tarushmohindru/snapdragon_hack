package com.yourbusiness.formfusion.pose

/**
 * Keypoint connectivity for the COCO-WholeBody 133-point layout rtmpose_body2d.onnx outputs
 * (0-16 body, 17-22 feet, 23-90 face, 91-132 hands). Only body+feet (0-22) are wired up here
 * — that's the recognizable "stencil around the body"; the 68 face and 42 hand points are
 * still in each [PersonPose]'s landmarks, just not drawn as part of this skeleton.
 */
object Skeleton {
    const val MAX_DRAWN_KEYPOINT_ID = 22

    val EDGES: List<Pair<Int, Int>> = listOf(
        // legs + hips
        15 to 13, 13 to 11, 16 to 14, 14 to 12, 11 to 12,
        // torso + arms
        5 to 11, 6 to 12, 5 to 6, 5 to 7, 6 to 8, 7 to 9, 8 to 10,
        // face outline (eyes/ears) off the two shoulders' midline
        1 to 2, 0 to 1, 0 to 2, 1 to 3, 2 to 4, 3 to 5, 4 to 6,
        // feet
        15 to 17, 17 to 18, 15 to 19,
        16 to 20, 20 to 21, 16 to 22
    )
}
