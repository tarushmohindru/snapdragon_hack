package com.yourbusiness.formfusion.camera

import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Single source of truth for PreviewView's scale type. [PoseOverlay]'s coordinate mapping
 * implements the fit-vs-fill math for both FILL_* and FIT_* variants keyed off this same
 * constant, so the preview and the overlay can never drift out of sync with each other.
 */
val PREVIEW_SCALE_TYPE: PreviewView.ScaleType = PreviewView.ScaleType.FILL_CENTER

@Composable
fun CameraPreview(controller: LifecycleCameraController, modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PREVIEW_SCALE_TYPE
                this.controller = controller
                controller.bindToLifecycle(lifecycleOwner)
            }
        }
    )
}
