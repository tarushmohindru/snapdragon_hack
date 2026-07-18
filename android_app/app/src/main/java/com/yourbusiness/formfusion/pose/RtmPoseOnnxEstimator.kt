package com.yourbusiness.formfusion.pose

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.min

/**
 * Pose estimator backed by rtmpose_body2d.onnx, run through ONNX Runtime with the QNN
 * HTP execution provider (falls back to CPU if HTP setup fails). Input/output tensor
 * shapes and value range come from ml/export_assets/rtmpose_body2d-onnx-float/metadata.json.
 * Output is SimCC-style: per-keypoint 1D classification bins for x and y, decoded via argmax.
 */
class RtmPoseOnnxEstimator(private val context: Context) : PoseEstimator {

    companion object {
        private const val TAG = "RtmPoseOnnxEstimator"
        private const val MODEL_FILE = "rtmpose_body2d.onnx"
        private const val DATA_FILE = "rtmpose_body2d.data"
        private const val INPUT_W = 192
        private const val INPUT_H = 256
        private const val NUM_KEYPOINTS = 133
        private const val SIMCC_SPLIT_RATIO = 2.0f
        private const val BOX_PAD_RATIO = 0.1f
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    override fun initialize(): Boolean {
        return try {
            val modelPath = copyAssetIfNeeded(MODEL_FILE)
            copyAssetIfNeeded(DATA_FILE) // external weights; must sit next to the .onnx file

            val ortEnv = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            val qnnAvailable = try {
                options.addQnn(mapOf("backend_path" to "libQnnHtp.so"))
                true
            } catch (e: Exception) {
                Log.w(TAG, "QNN HTP EP unavailable for rtmpose, falling back to CPU", e)
                false
            }

            session = ortEnv.createSession(modelPath, options)
            env = ortEnv
            Log.i(TAG, "rtmpose_body2d.onnx loaded (qnn=$qnnAvailable)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize rtmpose_body2d.onnx", e)
            false
        }
    }

    override fun estimate(bitmap: Bitmap, box: BoundingBox): List<LandmarkPoint> {
        val ortEnv = env ?: return emptyList()
        val ortSession = session ?: return emptyList()

        val (cropX1, cropY1, cropX2, cropY2) = fitBoxToAspectRatio(bitmap, box)
        val cropW = cropX2 - cropX1
        val cropH = cropY2 - cropY1
        if (cropW <= 0 || cropH <= 0) return emptyList()

        val crop = Bitmap.createBitmap(bitmap, cropX1, cropY1, cropW, cropH)
        val resized = Bitmap.createScaledBitmap(crop, INPUT_W, INPUT_H, true)

        val inputBuffer = bitmapToChwBuffer(resized)
        val shape = longArrayOf(1, 3, INPUT_H.toLong(), INPUT_W.toLong())

        return try {
            OnnxTensor.createTensor(ortEnv, inputBuffer, shape).use { inputTensor ->
                ortSession.run(mapOf("image" to inputTensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val predX = result.get("pred_x").get().value as Array<Array<FloatArray>>
                    @Suppress("UNCHECKED_CAST")
                    val predY = result.get("pred_y").get().value as Array<Array<FloatArray>>

                    val scaleX = cropW.toFloat() / INPUT_W
                    val scaleY = cropH.toFloat() / INPUT_H

                    (0 until NUM_KEYPOINTS).map { k ->
                        val (xBin, xConf) = argmaxSoftmaxScore(predX[0][k])
                        val (yBin, yConf) = argmaxSoftmaxScore(predY[0][k])
                        val x = (xBin / SIMCC_SPLIT_RATIO) * scaleX + cropX1
                        val y = (yBin / SIMCC_SPLIT_RATIO) * scaleY + cropY1
                        LandmarkPoint(k, x, y, 0f, min(xConf, yConf))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "rtmpose inference failed", e)
            emptyList()
        }
    }

    override fun release() {
        session?.close()
        session = null
        env = null // shared process-wide singleton; do not close it here
    }

    private fun copyAssetIfNeeded(name: String): String {
        val f = File(context.filesDir, name)
        if (!f.exists()) {
            context.assets.open(name).use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return f.absolutePath
    }

    /**
     * Grows [box] (plus padding) around its own center to match the model's 192:256
     * aspect ratio, then fits it inside the bitmap bounds, so the resize to 192x256
     * doesn't distort. Fitting is done by translating (and, only if the box is bigger
     * than the whole bitmap, uniformly shrinking) rather than clamping each coordinate
     * independently — independent clamping would skew the aspect ratio right back out
     * whenever the box sits near an edge.
     */
    internal fun fitBoxToAspectRatio(bitmap: Bitmap, box: BoundingBox): IntArray {
        val padW = (box.x2 - box.x1) * BOX_PAD_RATIO
        val padH = (box.y2 - box.y1) * BOX_PAD_RATIO
        val cx = (box.x1 + box.x2) / 2f
        val cy = (box.y1 + box.y2) / 2f
        var w = (box.x2 - box.x1) + 2 * padW
        var h = (box.y2 - box.y1) + 2 * padH

        val targetAspect = INPUT_W.toFloat() / INPUT_H
        val aspect = if (h > 0f) w / h else targetAspect
        if (aspect > targetAspect) {
            h = w / targetAspect
        } else {
            w = h * targetAspect
        }

        val maxW = bitmap.width.toFloat()
        val maxH = bitmap.height.toFloat()
        val shrink = min(if (w > 0f) maxW / w else 1f, if (h > 0f) maxH / h else 1f).coerceAtMost(1f)
        w *= shrink
        h *= shrink

        var x1 = cx - w / 2f
        var x2 = cx + w / 2f
        var y1 = cy - h / 2f
        var y2 = cy + h / 2f

        if (x1 < 0f) { x2 -= x1; x1 = 0f }
        if (x2 > maxW) { x1 -= (x2 - maxW); x2 = maxW }
        if (y1 < 0f) { y2 -= y1; y1 = 0f }
        if (y2 > maxH) { y1 -= (y2 - maxH); y2 = maxH }

        val ix1 = x1.toInt().coerceIn(0, bitmap.width - 1)
        val iy1 = y1.toInt().coerceIn(0, bitmap.height - 1)
        val ix2 = x2.toInt().coerceIn(ix1 + 1, bitmap.width)
        val iy2 = y2.toInt().coerceIn(iy1 + 1, bitmap.height)
        return intArrayOf(ix1, iy1, ix2, iy2)
    }

    private fun bitmapToChwBuffer(bitmap: Bitmap): FloatBuffer {
        val planeSize = INPUT_W * INPUT_H
        val pixels = IntArray(planeSize)
        bitmap.getPixels(pixels, 0, INPUT_W, 0, 0, INPUT_W, INPUT_H)

        val floatArray = FloatArray(3 * planeSize)
        for (i in 0 until planeSize) {
            val pixel = pixels[i]
            floatArray[i] = ((pixel shr 16) and 0xFF) / 255f
            floatArray[planeSize + i] = ((pixel shr 8) and 0xFF) / 255f
            floatArray[2 * planeSize + i] = (pixel and 0xFF) / 255f
        }
        return FloatBuffer.wrap(floatArray)
    }

    /** Returns (argmax index, softmax probability at that index) for one keypoint's bins. */
    internal fun argmaxSoftmaxScore(logits: FloatArray): Pair<Int, Float> {
        var maxIdx = 0
        var maxVal = logits[0]
        for (i in 1 until logits.size) {
            if (logits[i] > maxVal) {
                maxVal = logits[i]
                maxIdx = i
            }
        }
        var sumExp = 0f
        for (v in logits) sumExp += exp((v - maxVal).toDouble()).toFloat()
        val confidence = if (sumExp > 0f) 1f / sumExp else 0f
        return maxIdx to confidence
    }
}
