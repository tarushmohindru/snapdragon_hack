package com.yourbusiness.formfusion

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.yourbusiness.formfusion.camera.CameraPreview
import com.yourbusiness.formfusion.pose.PoseAnalyzer
import com.yourbusiness.formfusion.viewmodel.CameraViewModel
import java.util.concurrent.Executors

// Pure View: detector selection and frame/landmark counting live in CameraViewModel.
// Camera permission state stays here — it's Android UI plumbing (ActivityResult APIs),
// not business logic.
@Composable
fun CameraScreen(onBack: () -> Unit) {
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
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Camera permission needed")
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant permission")
            }
        }
        return
    }

    val viewModel = remember { CameraViewModel(context) }
    val uiState by viewModel.uiState.collectAsState()

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            setImageAnalysisAnalyzer(
                analysisExecutor,
                PoseAnalyzer(viewModel.personDetector, viewModel.poseEstimator) { persons ->
                    ContextCompat.getMainExecutor(context).execute {
                        viewModel.onFrameAnalyzed(persons)
                    }
                }
            )
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.unbind()
            analysisExecutor.shutdown()
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

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(controller = controller, modifier = Modifier.fillMaxSize())

        Text(
            text = if (uiState.isSessionActive) {
                "Frames analyzed: ${uiState.frameCount}  |  persons: ${uiState.lastPersonCount}  |  keypoints: ${uiState.lastKeypointCount}"
            } else {
                "Session ended — ${uiState.frameCount} frames analyzed. Check Logcat (tag CameraViewModel) for the full pipeline output."
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (uiState.isSessionActive) {
                Button(onClick = { viewModel.endSession() }) {
                    Text("End Session")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Button(onClick = onBack) {
                Text("Back")
            }
        }
    }
}
