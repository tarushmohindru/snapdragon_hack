package com.yourbusiness.formfusion

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.yourbusiness.formfusion.camera.CameraPreview
import com.yourbusiness.formfusion.camera.PoseOverlay
import com.yourbusiness.formfusion.pose.PoseAnalyzer
import com.yourbusiness.formfusion.ui.components.PrimaryButton
import com.yourbusiness.formfusion.ui.components.SecondaryButton
import com.yourbusiness.formfusion.ui.theme.Spacing
import com.yourbusiness.formfusion.viewmodel.CameraEvent
import com.yourbusiness.formfusion.viewmodel.CameraViewModel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Pure View: detector selection and frame/landmark counting live in CameraViewModel.
// Camera permission state stays here — it's Android UI plumbing (ActivityResult APIs),
// not business logic.
@Composable
fun CameraScreen(onBack: () -> Unit, onSessionEnded: (durationSeconds: Long) -> Unit) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    if (!hasCameraPermission) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.md)
            )
            Text(
                text = "Camera access needed",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                text = "FormFusion needs your camera to analyze movement in real time.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xxl)
            )
            PrimaryButton(
                text = "Grant permission",
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        return
    }

    val viewModel = remember { CameraViewModel(context) }
    val uiState by viewModel.uiState.collectAsState()

    // Single source of truth for which camera is bound — PoseOverlay derives its mirroring
    // from this same value, so switching cameras can never leave the overlay un-mirrored.
    val cameraSelector = remember { CameraSelector.DEFAULT_BACK_CAMERA }
    val isFrontCamera = cameraSelector.lensFacing == CameraSelector.LENS_FACING_FRONT

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            setImageAnalysisAnalyzer(
                analysisExecutor,
                PoseAnalyzer(viewModel.personDetector, viewModel.poseEstimator) { persons, imageWidth, imageHeight ->
                    ContextCompat.getMainExecutor(context).execute {
                        viewModel.onFrameAnalyzed(persons, imageWidth, imageHeight)
                    }
                }
            )
            this.cameraSelector = cameraSelector
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.unbind()
            analysisExecutor.shutdown()
            // Wait for any in-flight analyze() call to finish before releasing the native
            // TFLite/ONNX resources it uses — closing them while that background thread is
            // still mid-inference is a concurrent-access native crash, not a catchable one.
            try {
                analysisExecutor.awaitTermination(1, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            viewModel.dispose()
        }
    }

    // Stops feeding frames to the analyzer once the session ends, so no more
    // detections are produced (and none get appended after endSession() has logged).
    LaunchedEffect(uiState.isSessionActive) {
        if (!uiState.isSessionActive) {
            controller.unbind()
        }
    }

    // Navigates to the summary screen the moment endSession() reports how long the
    // session ran, instead of lingering here.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CameraEvent.SessionEnded -> onSessionEnded(event.durationSeconds)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(controller = controller, modifier = Modifier.fillMaxSize())

        PoseOverlay(
            persons = uiState.lastPersons,
            imageWidth = uiState.lastImageWidth,
            imageHeight = uiState.lastImageHeight,
            modifier = Modifier.fillMaxSize(),
            mirrorHorizontally = isFrontCamera
        )

        // Translucent scrim behind the top status row so it stays legible over any
        // background, without ever fully blocking the camera feed underneath.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)
                    )
                )
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg)
        ) {
            AnimatedVisibility(
                visible = uiState.isSessionActive,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LiveStatusMetric(label = "Frames", value = uiState.frameCount.toString())
                    LiveStatusMetric(label = "Persons", value = uiState.lastPersonCount.toString())
                    LiveStatusMetric(label = "Keypoints", value = uiState.lastKeypointCount.toString())
                }
            }

            AnimatedVisibility(
                visible = !uiState.isSessionActive,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = "Session ended — ${uiState.frameCount} frames analyzed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        }

        // Translucent scrim behind the bottom controls, same reasoning as above.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                    )
                )
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (uiState.isSessionActive) {
                PrimaryButton(
                    text = "End Session",
                    onClick = { viewModel.endSession() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
            }
            SecondaryButton(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LiveStatusMetric(label: String, value: String) {
    Column(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
    }
}
