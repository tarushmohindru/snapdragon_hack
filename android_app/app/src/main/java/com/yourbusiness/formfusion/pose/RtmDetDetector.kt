package com.yourbusiness.formfusion.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Person detector backed by rtmdet.tflite, run through LiteRT with the QNN HTP delegate
 * (falls back to CPU if HTP is unavailable on this device). Input/output tensor shapes and
 * value ranges come from assets/metadata.json; class 0 in assets/labels.txt is "person".
 */
class RtmDetDetector(private val context: Context) : PersonDetector {

    companion object {
        private const val TAG = "RtmDetDetector"
        private const val MODEL_FILE = "rtmdet.tflite"
        private const val INPUT_SIZE = 640
        private const val PERSON_CLASS_ID = 0
        private const val CONF_THRESHOLD = 0.5f
        private const val IOU_THRESHOLD = 0.45f
    }

    private var interpreter: Interpreter? = null
    private var qnnDelegate: QnnDelegate? = null
    private var boxesOutputIndex = 0
    private var scoresOutputIndex = 1
    private var classOutputIndex = 2

    override fun initialize(): Boolean {
        return try {
            val modelBuffer = loadModelFile()
            val options = Interpreter.Options()
            qnnDelegate = createHtpDelegate()
            qnnDelegate?.let { options.addDelegate(it) }

            val interp = Interpreter(modelBuffer, options)
            boxesOutputIndex = runCatching { interp.getOutputIndex("boxes") }.getOrDefault(0)
            scoresOutputIndex = runCatching { interp.getOutputIndex("scores") }.getOrDefault(1)
            classOutputIndex = runCatching { interp.getOutputIndex("class_idx") }.getOrDefault(2)
            interpreter = interp
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize rtmdet.tflite", e)
            false
        }
    }

    override fun detect(bitmap: Bitmap): List<BoundingBox> {
        val interp = interpreter ?: return emptyList()

        val scale = INPUT_SIZE.toFloat() / max(bitmap.width, bitmap.height)
        val letterboxed = letterboxSquare(bitmap, scale)
        val input = bitmapToInputArray(letterboxed)

        val boxesOut = Array(1) { Array(8400) { FloatArray(4) } }
        val scoresOut = Array(1) { FloatArray(8400) }
        val classOut = Array(1) { ByteArray(8400) }

        interp.runForMultipleInputsOutputs(
            arrayOf(input),
            mapOf(
                boxesOutputIndex to boxesOut,
                scoresOutputIndex to scoresOut,
                classOutputIndex to classOut
            )
        )

        val candidates = mutableListOf<BoundingBox>()
        for (i in 0 until 8400) {
            val score = scoresOut[0][i]
            if (score < CONF_THRESHOLD) continue
            if (classOut[0][i].toInt() != PERSON_CLASS_ID) continue
            val box = boxesOut[0][i]
            candidates.add(BoundingBox(box[0], box[1], box[2], box[3], score))
        }

        // Boxes come back in the 640x640 letterboxed canvas; unscale to the original
        // bitmap's pixel space (padding was applied top-left only, so no offset to subtract).
        return nonMaxSuppression(candidates).map { unscaleBox(it, scale, bitmap.width, bitmap.height) }
    }

    override fun release() {
        interpreter?.close()
        interpreter = null
        qnnDelegate?.close()
        qnnDelegate = null
    }

    private fun createHtpDelegate(): QnnDelegate? {
        return try {
            val delegateOptions = QnnDelegate.Options()
            delegateOptions.setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
            delegateOptions.setSkelLibraryDir(context.applicationInfo.nativeLibraryDir)
            val delegate = QnnDelegate(delegateOptions)
            if (delegate.isAvailable) {
                delegate
            } else {
                Log.w(TAG, "QNN HTP backend not available on this device, using CPU")
                delegate.close()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "QNN HTP delegate unavailable, falling back to CPU", e)
            null
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val afd = context.assets.openFd(MODEL_FILE)
        FileInputStream(afd.fileDescriptor).use { input ->
            return input.channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        }
    }

    private fun letterboxSquare(bitmap: Bitmap, scale: Float): Bitmap {
        val newW = round(bitmap.width * scale).toInt().coerceAtLeast(1)
        val newH = round(bitmap.height * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

        val canvasBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(resized, 0f, 0f, null)
        return canvasBitmap
    }

    private fun bitmapToInputArray(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val input = Array(1) { Array(INPUT_SIZE) { Array(INPUT_SIZE) { FloatArray(3) } } }
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val pixel = pixels[y * INPUT_SIZE + x]
                val dst = input[0][y][x]
                dst[0] = ((pixel shr 16) and 0xFF) / 255f
                dst[1] = ((pixel shr 8) and 0xFF) / 255f
                dst[2] = (pixel and 0xFF) / 255f
            }
        }
        return input
    }

    /** Clamps an unscaled box back into the bitmap's own pixel bounds. */
    internal fun unscaleBox(box: BoundingBox, scale: Float, bitmapWidth: Int, bitmapHeight: Int): BoundingBox {
        return BoundingBox(
            x1 = (box.x1 / scale).coerceIn(0f, bitmapWidth.toFloat()),
            y1 = (box.y1 / scale).coerceIn(0f, bitmapHeight.toFloat()),
            x2 = (box.x2 / scale).coerceIn(0f, bitmapWidth.toFloat()),
            y2 = (box.y2 / scale).coerceIn(0f, bitmapHeight.toFloat()),
            score = box.score
        )
    }

    internal fun nonMaxSuppression(boxes: List<BoundingBox>): List<BoundingBox> {
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val kept = mutableListOf<BoundingBox>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { iou(best, it) > IOU_THRESHOLD }
        }
        return kept
    }

    internal fun iou(a: BoundingBox, b: BoundingBox): Float {
        val interX1 = max(a.x1, b.x1)
        val interY1 = max(a.y1, b.y1)
        val interX2 = min(a.x2, b.x2)
        val interY2 = min(a.y2, b.y2)
        val interArea = max(0f, interX2 - interX1) * max(0f, interY2 - interY1)
        val areaA = max(0f, a.x2 - a.x1) * max(0f, a.y2 - a.y1)
        val areaB = max(0f, b.x2 - b.x1) * max(0f, b.y2 - b.y1)
        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }
}
