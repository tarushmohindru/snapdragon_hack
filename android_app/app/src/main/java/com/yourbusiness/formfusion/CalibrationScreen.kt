package com.yourbusiness.formfusion

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.yourbusiness.formfusion.camera.CameraPreview
import com.yourbusiness.formfusion.network.Role
import com.yourbusiness.formfusion.network.SessionManager
import com.yourbusiness.formfusion.ui.components.PrimaryButton
import com.yourbusiness.formfusion.ui.components.SecondaryButton
import com.yourbusiness.formfusion.ui.components.StatusChip
import com.yourbusiness.formfusion.ui.components.StatusTone
import com.yourbusiness.formfusion.ui.theme.Spacing
import com.yourbusiness.formfusion.viewmodel.CalibrationViewModel
import java.io.File
import java.util.concurrent.Executors

@Composable
fun CalibrationScreen(onComplete: () -> Unit, onLeave: () -> Unit) {
    val context = LocalContext.current
    val viewModel = remember { CalibrationViewModel(context.applicationContext) }
    val state by viewModel.uiState.collectAsState()
    val executor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
    }
    var permission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permission = it }

    DisposableEffect(Unit) {
        onDispose {
            controller.unbind()
            executor.shutdown()
            viewModel.dispose()
        }
    }

    if (!permission) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Camera permission is required for calibration.")
            PrimaryButton(
                text = "Grant permission",
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg)
            )
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        CameraPreview(controller, Modifier.fillMaxSize())
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.62f)).padding(Spacing.lg)
        ) {
            Text("Stereo calibration", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(
                "Both phones: frame the same 9×6 checkerboard, then capture pair ${state.captureNumber + 1}. Move the board between captures.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f)
            )
            StatusChip(
                text = if (state.calibrated) "Calibration ready" else "${state.completePairs}/10 complete pairs",
                tone = if (state.calibrated) StatusTone.Success else StatusTone.Neutral
            )
            state.reprojectionError?.let {
                Text("Reprojection error %.4f px".format(it), color = Color.White)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.62f)).padding(Spacing.lg)
        ) {
            if (!state.calibrated) {
                PrimaryButton(
                    text = if (state.uploading) "Uploading…" else "Capture pair ${state.captureNumber + 1}",
                    enabled = !state.uploading && state.captureNumber < 10,
                    onClick = {
                        val file = File(context.cacheDir, "calibration-${System.nanoTime()}.jpg")
                        controller.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            executor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    viewModel.upload(file)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    file.delete()
                                }
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (SessionManager.role == Role.HOST) {
                    SecondaryButton(
                        text = if (state.finalizing) "Calibrating…" else "Finalize calibration",
                        enabled = state.completePairs >= 10 && !state.finalizing,
                        onClick = viewModel::finalizeCalibration,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
                    )
                }
            } else {
                PrimaryButton(
                    text = "Start live workout",
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SecondaryButton(
                text = "Leave session",
                onClick = onLeave,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
            )
        }
    }
}
