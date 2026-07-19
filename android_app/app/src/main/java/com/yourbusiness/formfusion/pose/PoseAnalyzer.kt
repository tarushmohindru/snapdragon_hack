package com.yourbusiness.formfusion.pose

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Full two-stage pipeline: [personDetector] finds people in the frame, then
 * [poseEstimator] runs on each detected crop to produce that person's keypoints.
 */
class PoseAnalyzer(
    private val personDetector: PersonDetector,
    private val poseEstimator: PoseEstimator,
    private val onResult: (persons: List<PersonPose>, imageWidth: Int, imageHeight: Int) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        try {
            // ImageProxy.toBitmap() returns the full, uncropped sensor buffer — it does NOT
            // apply imageProxy.cropRect. LifecycleCameraController binds Preview + ImageAnalysis
            // through a shared ViewPort so both see the same field of view, and cropRect is
            // exactly where that gets encoded here. Skipping this crop is what let the
            // analysis buffer's aspect ratio silently diverge from what PreviewView actually
            // shows, which is what was throwing the overlay off.
            val visibleBitmap = cropToViewport(imageProxy.toBitmap(), imageProxy.cropRect)

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = rotateBitmap(visibleBitmap, rotationDegrees)

            val boxes = personDetector.detect(bitmap)
            val persons = boxes.map { box ->
                PersonPose(box, poseEstimator.estimate(bitmap, box))
            }
            onResult(persons, bitmap.width, bitmap.height)

            Log.d(
                "PoseAnalyzer",
                "frame ${bitmap.width}x${bitmap.height}, persons=${persons.size}, " +
                    "keypoints=${persons.sumOf { it.landmarks.size }}"
            )
        } finally {
            imageProxy.close() //release after processing each frame
        }
    }

    private fun cropToViewport(bitmap: Bitmap, cropRect: Rect): Bitmap {
        if (cropRect.left == 0 && cropRect.top == 0 &&
            cropRect.width() == bitmap.width && cropRect.height() == bitmap.height
        ) {
            return bitmap // no-op: cropRect already covers the full buffer on this device
        }
        return Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
