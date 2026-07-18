package com.yourbusiness.formfusion

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions

/**
 * Launches ML Kit's ready-made QR scanner UI (no camera permission needed — ML Kit
 * handles it internally) and returns the raw scanned payload.
 */
fun launchQrScanner(
    context: Context,
    onResult: (String) -> Unit,
    onError: (Exception) -> Unit
) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    val scanner = GmsBarcodeScanning.getClient(context, options)
    scanner.startScan()
        .addOnSuccessListener { barcode -> barcode.rawValue?.let { onResult(it) } }
        .addOnFailureListener { e -> onError(e) }
        .addOnCanceledListener { /* user cancelled, do nothing */ }
}
