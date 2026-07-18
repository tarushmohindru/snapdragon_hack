package com.yourbusiness.formfusion

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Start with Other Phones")

        Button(onClick = onHost) {
            Text("Host Session")
        }

        Button(onClick = {
            launchQrScanner(
                context = context,
                onResult = viewModel::onQrScanned,
                onError = viewModel::onScanFailed
            )
        }) {
            Text("Join Session")
        }

        Button(onClick = onBack) {
            Text("Back")
        }
    }
}
