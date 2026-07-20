package com.yourbusiness.formfusion

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.yourbusiness.formfusion.ui.components.PrimaryButton
import com.yourbusiness.formfusion.ui.components.SecondaryButton
import com.yourbusiness.formfusion.ui.components.SectionHeader
import com.yourbusiness.formfusion.ui.components.TertiaryTextButton
import com.yourbusiness.formfusion.ui.theme.Spacing
import com.yourbusiness.formfusion.network.SessionManager
import com.yourbusiness.formfusion.viewmodel.RoleEvent
import com.yourbusiness.formfusion.viewmodel.RoleViewModel

// Pure View: no JSON parsing, no SessionManager access — all delegated to RoleViewModel.
@Composable
fun RoleScreen(
    onHost: () -> Unit,
    onJoin: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel = remember { RoleViewModel() }
    var exercise by remember { mutableStateOf(SessionManager.exercise) }

    DisposableEffect(viewModel) {
        onDispose { viewModel.dispose() }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                RoleEvent.NavigateToJoinLobby -> onJoin()
                is RoleEvent.ShowError -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center
    ) {
        SectionHeader(
            title = "Choose exercise",
            subtitle = "The same selection drives biomechanics on web and mobile"
        )

        listOf(
            "squats" to "Squats",
            "deadlifts" to "Deadlifts",
            "bench_press" to "Bench press",
            "bicep_curls" to "Bicep curls",
            "shoulder_press" to "Shoulder press"
        ).forEach { (id, label) ->
            SecondaryButton(
                text = if (exercise == id) "✓ $label" else label,
                onClick = {
                    exercise = id
                    SessionManager.exercise = id
                },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
            )
        }

        PrimaryButton(
            text = "Host Session",
            onClick = onHost,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xl)
        )

        SecondaryButton(
            text = "Join Session",
            onClick = {
                launchQrScanner(
                    context = context,
                    onResult = viewModel::onQrScanned,
                    onError = viewModel::onScanFailed
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md)
        )

        TertiaryTextButton(text = "Back", onClick = onBack)
    }
}
