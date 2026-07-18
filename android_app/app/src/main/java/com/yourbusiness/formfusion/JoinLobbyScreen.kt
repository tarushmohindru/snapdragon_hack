package com.yourbusiness.formfusion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourbusiness.formfusion.viewmodel.JoinLobbyEvent
import com.yourbusiness.formfusion.viewmodel.JoinLobbyViewModel

// Pure View: WebSocket client, retry logic, and connection tracking all live in JoinLobbyViewModel.
@Composable
fun JoinLobbyScreen(
    onSessionStarted: () -> Unit,
    onLeave: () -> Unit
) {
    val context = LocalContext.current
    val viewModel = remember { JoinLobbyViewModel(context.applicationContext) }
    val uiState by viewModel.uiState.collectAsState()

    DisposableEffect(viewModel) {
        onDispose { viewModel.dispose() }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                JoinLobbyEvent.NavigateToCamera -> onSessionStarted()
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

        if (uiState.showConnectionLost) {
            Column(
                modifier = Modifier.padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Connection lost — tap to retry")
                Button(
                    onClick = viewModel::onRetryClicked,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Retry")
                }
                Button(
                    onClick = onLeave,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Leave")
                }
            }
        } else {
            val transition = rememberInfiniteTransition(label = "waitingPulse")
            val pulseAlpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )

            Column(
                modifier = Modifier.padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
                Text(
                    text = "Waiting for host to start...",
                    modifier = Modifier.alpha(pulseAlpha)
                )
                Text(
                    text = "${uiState.connectedCount} phone${if (uiState.connectedCount == 1) "" else "s"} in session",
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Button(
                onClick = onLeave,
                modifier = Modifier.padding(top = 24.dp)
            ) {
                Text("Leave")
            }
        }
    }
}
