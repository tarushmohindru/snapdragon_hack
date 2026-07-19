package com.yourbusiness.formfusion

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yourbusiness.formfusion.ui.components.PrimaryButton
import com.yourbusiness.formfusion.ui.components.SectionHeader
import com.yourbusiness.formfusion.ui.components.StatusChip
import com.yourbusiness.formfusion.ui.components.StatusTone
import com.yourbusiness.formfusion.ui.components.SurfaceCard
import com.yourbusiness.formfusion.ui.components.TertiaryTextButton
import com.yourbusiness.formfusion.ui.theme.Spacing
import com.yourbusiness.formfusion.ui.theme.Success
import com.yourbusiness.formfusion.viewmodel.HostEvent
import com.yourbusiness.formfusion.viewmodel.HostViewModel

// Pure View: session generation, server lifecycle, and QR encoding all live in HostViewModel.
@Composable
fun HostScreen(
    onSessionStarted: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel = remember { HostViewModel(context.applicationContext) }
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
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SectionHeader(
            title = "Host Session",
            subtitle = "Session ${uiState.sessionId}",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(Spacing.xl))

        uiState.error?.let {
            StatusChip(text = it, tone = StatusTone.Error)
            PrimaryButton(
                text = "Retry backend",
                onClick = viewModel::createSession,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)
            )
        }

        SurfaceCard {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                uiState.qrBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Session QR code",
                        modifier = Modifier
                            .padding(vertical = Spacing.md)
                            .size(220.dp)
                    )
                }

                StatusChip(
                    text = "${uiState.connectedCount} phone${if (uiState.connectedCount == 1) "" else "s"} connected",
                    tone = if (uiState.connectedCount > 0) StatusTone.Success else StatusTone.Neutral
                )
                if (uiState.joinCode.isNotBlank()) {
                    Text(
                        text = "Join code ${uiState.joinCode}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = Spacing.md)
                    )
                }
            }
        }

        if (uiState.connectedCount > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg)
            ) {
                for (i in 1..uiState.connectedCount) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = Spacing.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = Success,
                            modifier = Modifier.padding(end = Spacing.xs)
                        )
                        Text("Phone $i", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        PrimaryButton(
            text = "Start Session",
            onClick = viewModel::onStartSessionClicked,
            enabled = uiState.canStartSession,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xxl)
        )

        TertiaryTextButton(text = "Back", onClick = onBack)
    }
}
