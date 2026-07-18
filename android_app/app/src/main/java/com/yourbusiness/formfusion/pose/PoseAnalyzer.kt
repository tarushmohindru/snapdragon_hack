package com.yourbusiness.formfusion.pose

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class PoseAnalyzer(
    private val detector: PoseDetector,
    private val onResult: (landmarks: List<LandmarkPoint>) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = rotateBitmap(imageProxy.toBitmap(), rotationDegrees)

            val landmarks = detector.detect(bitmap, bitmap.width, bitmap.height)
            onResult(landmarks)

            Log.d(
                "PoseAnalyzer",
                "frame ${bitmap.width}x${bitmap.height}, landmarks=${landmarks.size}"
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
