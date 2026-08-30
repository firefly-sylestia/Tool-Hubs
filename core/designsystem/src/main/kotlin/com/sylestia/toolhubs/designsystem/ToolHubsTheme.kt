package com.sylestia.toolhubs.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ToolHubsPrimary = Color(0xFF6750A4)
private val ToolHubsOnPrimary = Color(0xFFFFFFFF)

@Composable
fun ToolHubsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = ToolHubsPrimary,
            onPrimary = ToolHubsOnPrimary,
        ),
        typography = Typography(),
        content = content,
    )
}
