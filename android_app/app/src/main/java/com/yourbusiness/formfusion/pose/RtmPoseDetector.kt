package com.yourbusiness.formfusion.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * Real pose detector backed by libpose.so + QNN model binaries. Stays inert
 * (initialize() returns false) until the ML team's native library and .bin
 * assets are actually present — see README for the activation steps.
 */
class RtmPoseDetector(private val context: Context) : PoseDetector {
    private var handle: Long = 0L

    fun initialize(): Boolean {
        return try {
            val htpVersion = ChipSelector.getHtpVersion() ?: return false
            val detPath = copyAssetIfNeeded(context, "rtmdet_$htpVersion.bin")
            val posePath = copyAssetIfNeeded(context, "rtmpose_$htpVersion.bin")
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            handle = PoseNative.init(detPath, posePath, nativeLibDir)
            handle != 0L
        } catch (e: Exception) {
            Log.w("RtmPoseDetector", "libpose.so or model files not present yet, staying on stub", e)
            false
        }
    }

    override fun detect(bitmap: Bitmap, imageWidth: Int, imageHeight: Int): List<LandmarkPoint> {
        if (handle == 0L) return emptyList()
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val result = PoseNative.run(handle, argb, bitmap.width, bitmap.height)
        if (result.isEmpty()) return emptyList()
        return (0 until 133).map { i ->
            LandmarkPoint(i, result[i * 3], result[i * 3 + 1], 0f, result[i * 3 + 2])
        }
    }

    fun release() {
        if (handle != 0L) {
            PoseNative.close(handle)
            handle = 0L
        }
    }

    private fun copyAssetIfNeeded(context: Context, name: String): String {
        val f = File(context.filesDir, name)
        if (!f.exists()) {
            context.assets.open(name).use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return f.absolutePath
    }
}
