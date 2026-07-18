package com.yourbusiness.formfusion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yourbusiness.formfusion.ui.theme.FormFusionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FormFusionTheme {
                FormFusionApp()
            }
        }
    }
}

@Composable
fun FormFusionApp() {
    var currentScreen by remember { mutableStateOf(Screen.Home) }

    when (currentScreen) {
        Screen.Home -> HomeScreen(onNavigate = { currentScreen = it })
        Screen.Camera -> CameraScreen(onBack = { currentScreen = Screen.Home })
        Screen.MultiPhonePlaceholder -> MultiPhonePlaceholderScreen(onBack = { currentScreen = Screen.Home })
        Screen.Role -> RoleScreen(
            onHost = { currentScreen = Screen.Host },
            onJoin = { currentScreen = Screen.JoinLobby },
            onBack = { currentScreen = Screen.Home }
        )
        Screen.Host -> HostScreen(
            onSessionStarted = { currentScreen = Screen.Camera },
            onBack = { currentScreen = Screen.Home }
        )
        Screen.JoinLobby -> JoinLobbyScreen(
            onSessionStarted = { currentScreen = Screen.Camera },
            onLeave = { currentScreen = Screen.Home }
        )
    }
}
