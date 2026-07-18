package com.yourbusiness.formfusion.pose

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.min

/**
 * Pose estimator backed by rtmpose_body2d.onnx, run through ONNX Runtime with the QNN HTP
 * execution provider (falls back to CPU if HTP setup fails).
 *
 * This mirrors mmpose's standard top-down pipeline exactly — the same one the qai_hub
 * `RTMPosebody2dApp` inferencer runs — because that is the reference that produces correct
 * keypoints:
 *
 *  - Input tensor: RGB, float [0,1], NCHW 1x3x256x192. The exported model's forward() does
 *    the RGB->BGR swap and (x-mean)/std normalization INTERNALLY (see model.py), so we must
 *    feed plain RGB/255 and do NOT normalize here.
 *  - Box -> center/scale: [computeCenterScale] applies mmpose's GetBBoxCenterScale with the
 *    default 1.25 padding, then grows the shorter side so the region's aspect ratio matches
 *    192:256 (TopdownAffine's `_fix_aspect_ratio`).
 *  - Warp: [warpToModelInput] affine-warps that region straight out of the full frame into
 *    192x256, sampling anything past the frame edge as border — never a hard pre-crop, so a
 *    person near an edge keeps correct geometry.
 *  - Decode: SimCC argmax / split_ratio gives the keypoint in 192x256 input space;
 *    [inputToOriginal] applies the exact inverse affine to land it back in the original
 *    frame's pixel space.
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

        // mmpose GetBBoxCenterScale default. Expands the detection box around its center
        // before the affine warp so joints near the box edge aren't clipped. This is the one
        // tunable constant of the preprocessing — 1.25 is the RTMPose config default.
        private const val BBOX_PADDING = 1.25f

        // model.py's forward() applies (x - mean)/std with mmpose's PoseDataPreprocessor
        // mean/std, which are in 0-255 scale. So the input must be RAW 0-255 pixels. Feeding
        // [0,1] collapses every pixel to ~-2.1 after that normalization and destroys the
        // image — the prime suspect for garbage keypoints. Flip to true to feed [0,1]
        // (metadata's declared value_range) if 0-255 turns out to regress.
        private const val FEED_NORMALIZED_0_1 = false

        // Joints sampled by the on-device diagnostic log (RtmPoseDebug). Chosen to span the
        // body vertically so a plausible layout is easy to spot: nose top, ankles bottom.
        private val DIAG_JOINTS = mapOf(
            0 to "nose", 5 to "Lsh", 6 to "Rsh", 11 to "Lhip", 12 to "Rhip",
            15 to "Lank", 16 to "Rank"
        )
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var diagFrame = 0

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

        val cs = computeCenterScale(box)
        if (cs.scaleW <= 0f || cs.scaleH <= 0f) return emptyList()

        val modelInput = warpToModelInput(bitmap, cs)
        val inputBuffer = bitmapToChwBuffer(modelInput)
        val shape = longArrayOf(1, 3, INPUT_H.toLong(), INPUT_W.toLong())

        return try {
            OnnxTensor.createTensor(ortEnv, inputBuffer, shape).use { inputTensor ->
                ortSession.run(mapOf("image" to inputTensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val predX = result.get("pred_x").get().value as Array<Array<FloatArray>>
                    @Suppress("UNCHECKED_CAST")
                    val predY = result.get("pred_y").get().value as Array<Array<FloatArray>>

                    // Diagnostic (throttled): distinguishes "model output is garbage" from
                    // "output is fine but my mapping is wrong". `in=` is the keypoint in the
                    // model's own 192x256 input space (before any of my affine); a plausible
                    // person should have nose near the top and ankles near the bottom. `raw=`
                    // is the SimCC peak value — near-flat/tiny peaks mean the input image was
                    // destroyed (normalization/channel bug).
                    val logThisFrame = (diagFrame++ % 30 == 0)
                    val diag = if (logThisFrame) StringBuilder() else null

                    val landmarks = (0 until NUM_KEYPOINTS).map { k ->
                        val (xBin, xConf) = argmaxSoftmaxScore(predX[0][k])
                        val (yBin, yConf) = argmaxSoftmaxScore(predY[0][k])
                        val inputX = xBin / SIMCC_SPLIT_RATIO
                        val inputY = yBin / SIMCC_SPLIT_RATIO
                        // SimCC bin -> model-input pixel (0..192, 0..256), then inverse affine.
                        val (origX, origY) = inputToOriginal(inputX, inputY, cs)

                        diag?.let {
                            val label = DIAG_JOINTS[k] ?: return@let
                            val rawX = predX[0][k].maxOrNull() ?: 0f
                            val rawY = predY[0][k].maxOrNull() ?: 0f
                            it.append(
                                "$label in=(${"%.0f".format(inputX)},${"%.0f".format(inputY)}) " +
                                    "raw=(${"%.2f".format(rawX)},${"%.2f".format(rawY)}) | "
                            )
                        }
                        LandmarkPoint(k, origX, origY, 0f, min(xConf, yConf))
                    }

                    diag?.let {
                        Log.i(
                            "RtmPoseDebug",
                            "norm=${if (FEED_NORMALIZED_0_1) "0..1" else "0..255"} " +
                                "center=(${"%.0f".format(cs.centerX)},${"%.0f".format(cs.centerY)}) " +
                                "scale=(${"%.0f".format(cs.scaleW)},${"%.0f".format(cs.scaleH)}) | $it"
                        )
                    }
                    landmarks
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
     * mmpose `GetBBoxCenterScale(padding=1.25)` + `TopdownAffine` aspect fix. Converts the
     * detection box (original-image xyxy) into the center and the width/height of the region
     * that gets affine-warped onto the 192x256 model input. The region is padded by
     * [BBOX_PADDING] around the box center, then grown on the shorter side so its aspect
     * ratio is exactly 192:256 (so the warp introduces no distortion).
     */
    internal fun computeCenterScale(box: BoundingBox): CenterScale {
        val centerX = (box.x1 + box.x2) / 2f
        val centerY = (box.y1 + box.y2) / 2f
        var w = (box.x2 - box.x1) * BBOX_PADDING
        var h = (box.y2 - box.y1) * BBOX_PADDING

        val aspect = INPUT_W.toFloat() / INPUT_H
        if (w > h * aspect) h = w / aspect else w = h * aspect
        return CenterScale(centerX, centerY, w, h)
    }

    /**
     * Inverse of the affine warp: maps a model-input pixel (x in 0..192, y in 0..256) back
     * to the original frame's pixel space. Matches mmpose's decode
     * `keypoint / input_size * scale + (center - scale/2)`.
     */
    internal fun inputToOriginal(inputX: Float, inputY: Float, cs: CenterScale): Pair<Float, Float> {
        val originX = cs.centerX - cs.scaleW / 2f
        val originY = cs.centerY - cs.scaleH / 2f
        val origX = inputX * cs.scaleW / INPUT_W + originX
        val origY = inputY * cs.scaleH / INPUT_H + originY
        return origX to origY
    }

    /**
     * Affine-warps the [center, scale] region of the full [bitmap] onto a fresh 192x256
     * bitmap. Uses a translate+scale Matrix (no rotation, as mmpose does at inference), and
     * pre-fills black so any part of the region beyond the frame edge is border-sampled
     * rather than clamped — the same behavior as cv2.warpAffine's constant border.
     */
    private fun warpToModelInput(bitmap: Bitmap, cs: CenterScale): Bitmap {
        val originX = cs.centerX - cs.scaleW / 2f
        val originY = cs.centerY - cs.scaleH / 2f
        val matrix = Matrix().apply {
            postTranslate(-originX, -originY)
            postScale(INPUT_W / cs.scaleW, INPUT_H / cs.scaleH)
        }
        val out = Bitmap.createBitmap(INPUT_W, INPUT_H, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    private fun bitmapToChwBuffer(bitmap: Bitmap): FloatBuffer {
        val planeSize = INPUT_W * INPUT_H
        val pixels = IntArray(planeSize)
        bitmap.getPixels(pixels, 0, INPUT_W, 0, 0, INPUT_W, INPUT_H)

        // RGB, NCHW. The model applies RGB->BGR and (x-mean)/std internally (see model.py).
        // Its mean/std are 0-255 scale, so feed raw 0-255 unless FEED_NORMALIZED_0_1 is set.
        val divisor = if (FEED_NORMALIZED_0_1) 255f else 1f
        val floatArray = FloatArray(3 * planeSize)
        for (i in 0 until planeSize) {
            val pixel = pixels[i]
            floatArray[i] = ((pixel shr 16) and 0xFF) / divisor
            floatArray[planeSize + i] = ((pixel shr 8) and 0xFF) / divisor
            floatArray[2 * planeSize + i] = (pixel and 0xFF) / divisor
        }
        return FloatBuffer.wrap(floatArray)
    }

    /**
     * Returns (argmax index, a confidence in (0,1]) for one keypoint's SimCC bins. The
     * confidence is the softmax probability mass at the peak — a peakedness measure usable
     * for thresholding out uncertain joints; it does not affect the decoded position.
     */
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

/** Center + scaled region size (original-image pixel space) that maps onto the 192x256 model input. */
internal data class CenterScale(
    val centerX: Float,
    val centerY: Float,
    val scaleW: Float,
    val scaleH: Float
)
