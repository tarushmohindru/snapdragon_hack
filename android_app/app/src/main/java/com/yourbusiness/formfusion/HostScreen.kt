package com.yourbusiness.formfusion

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourbusiness.formfusion.viewmodel.HostEvent
import com.yourbusiness.formfusion.viewmodel.HostViewModel

// Pure View: session generation, server lifecycle, and QR encoding all live in HostViewModel.
@Composable
fun HostScreen(
    onSessionStarted: () -> Unit,
    onBack: () -> Unit
) {
    val viewModel = remember { HostViewModel() }
    val uiState by viewModel.uiState.collectAsState()

    DisposableEffect(viewModel) {
        onDispose { viewModel.dispose() }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                HostEvent.NavigateToCamera -> onSessionStarted()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Session: ${uiState.sessionId}",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        uiState.qrBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Session QR code",
                modifier = Modifier
                    .padding(24.dp)
                    .size(240.dp)
            )
        }

        Text("${uiState.connectedCount} phone${if (uiState.connectedCount == 1) "" else "s"} connected")

        Column(modifier = Modifier.padding(top = 8.dp)) {
            for (i in 1..uiState.connectedCount) {
                Text("Phone $i ✓")
            }
        }

        Button(
            onClick = viewModel::onStartSessionClicked,
            enabled = uiState.canStartSession,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Text("Start Session")
        }

        Button(
            onClick = onBack,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Back")
        }
    }
}
