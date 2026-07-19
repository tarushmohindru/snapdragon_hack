package com.yourbusiness.formfusion

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yourbusiness.formfusion.ui.components.PrimaryButton
import com.yourbusiness.formfusion.ui.components.SummaryMetric
import com.yourbusiness.formfusion.ui.theme.Spacing
import com.yourbusiness.formfusion.network.SessionManager
import com.yourbusiness.formfusion.ui.theme.Success
import com.yourbusiness.formfusion.user.UserProfile

/** "42 s" under a minute; "1m 05s" at or above it. */
fun formatSessionDuration(durationSeconds: Long): String {
    return if (durationSeconds < 60) {
        "$durationSeconds s"
    } else {
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        "${minutes}m %02ds".format(seconds)
    }
}

@Composable
fun SessionSummaryScreen(durationSeconds: Long, onDone: () -> Unit) {
    val name = UserProfile.name.ifBlank { "there" }

    var visible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { visible = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = Success,
                    modifier = Modifier.padding(bottom = Spacing.md)
                )

                Text(
                    text = "Great work, $name!",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                SummaryMetric(
                    label = "Session duration",
                    value = formatSessionDuration(durationSeconds),
                    modifier = Modifier.padding(top = Spacing.xxl)
                )

                SessionManager.aiSummary.value?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = Spacing.lg)
                    )
                }

                PrimaryButton(
                    text = "Done",
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xxl)
                )
            }
        }
    }
}
