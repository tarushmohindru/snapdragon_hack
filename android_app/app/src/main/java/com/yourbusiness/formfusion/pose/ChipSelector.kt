package com.yourbusiness.formfusion.pose

import android.os.Build

/**
 * Maps the phone's SoC to the QNN HTP (Hexagon Tensor Processor) runtime version
 * the ML team compiled model binaries for.
 */
object ChipSelector {
    private val SOC_TO_HTP = mapOf(
        "SM8550" to "v73",   // Snapdragon 8 Gen 2
        "SM7575" to "v73",   // 8s Gen 3 (shares v73)
        "SM8650" to "v75",   // Snapdragon 8 Gen 3
        "SM8750" to "v79",   // Snapdragon 8 Elite
        "SM8750AC" to "v79", // Galaxy S25
    )

    fun getHtpVersion(): String? {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Build.SOC_MODEL else return "v73"
        return SOC_TO_HTP[soc.uppercase()]
    }
}
