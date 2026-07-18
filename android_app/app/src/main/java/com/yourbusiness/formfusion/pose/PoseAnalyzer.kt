package com.yourbusiness.formfusion.pose

import android.graphics.Bitmap
import android.graphics.Matrix
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
    private val onResult: (persons: List<PersonPose>) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = rotateBitmap(imageProxy.toBitmap(), rotationDegrees)

            val boxes = personDetector.detect(bitmap)
            val persons = boxes.map { box ->
                PersonPose(box, poseEstimator.estimate(bitmap, box))
            }
            onResult(persons)

            Log.d(
                "PoseAnalyzer",
                "frame ${bitmap.width}x${bitmap.height}, persons=${persons.size}, " +
                    "keypoints=${persons.sumOf { it.landmarks.size }}"
            )
        } finally {
            imageProxy.close() //release after processing each frame
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
