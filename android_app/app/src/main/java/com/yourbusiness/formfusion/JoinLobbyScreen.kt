package com.yourbusiness.formfusion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.yourbusiness.formfusion.ui.components.PrimaryButton
import com.yourbusiness.formfusion.ui.components.SecondaryButton
import com.yourbusiness.formfusion.ui.components.SectionHeader
import com.yourbusiness.formfusion.ui.components.StatusChip
import com.yourbusiness.formfusion.ui.components.StatusTone
import com.yourbusiness.formfusion.ui.components.TertiaryTextButton
import com.yourbusiness.formfusion.ui.theme.Spacing
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
            .background(MaterialTheme.colorScheme.background)
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SectionHeader(
            title = "Join Session",
            subtitle = "Session ${uiState.sessionId}",
            modifier = Modifier.fillMaxWidth()
        )

        if (uiState.showConnectionLost) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StatusChip(text = "Connection lost", tone = StatusTone.Error)
                Text(
                    text = uiState.error ?: "Tap retry to reconnect",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm)
                )

                PrimaryButton(
                    text = "Retry",
                    onClick = viewModel::onRetryClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xl)
                )
                TertiaryTextButton(text = "Leave", onClick = onLeave)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = Spacing.md)
                )
                Text(
                    text = "Joining backend session...",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.alpha(pulseAlpha)
                )
                StatusChip(
                    text = "${uiState.connectedCount} phone${if (uiState.connectedCount == 1) "" else "s"} in session",
                    tone = StatusTone.Neutral
                )
            }

            SecondaryButton(
                text = "Leave",
                onClick = onLeave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xxl)
            )
        }
    }
}
