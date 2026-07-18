package com.yourbusiness.formfusion.pose

/**
 * JNI bridge contract with the ML team's native library (libpose.so). Loading is
 * best-effort: until the .so is delivered, the app must keep running on the stub
 * detector instead of crashing.
 */
object PoseNative {
    init {
        // This loads libpose.so — will work once ML team delivers the file.
        // Until then, calling any function below throws UnsatisfiedLinkError.
        try {
            System.loadLibrary("pose")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("PoseNative", "libpose.so not yet present — using stub")
        }
    }

    // Called ONCE. Returns handle (0 = failed).
    external fun init(detBinPath: String, poseBinPath: String, nativeLibDir: String): Long

    // Called per frame. Returns [x0,y0,score0, x1,y1,score1, ...] for 133 joints = 399 floats.
    // Empty array = no person detected.
    external fun run(handle: Long, argb: IntArray, width: Int, height: Int): FloatArray

    // Called on close.
    external fun close(handle: Long)
}
