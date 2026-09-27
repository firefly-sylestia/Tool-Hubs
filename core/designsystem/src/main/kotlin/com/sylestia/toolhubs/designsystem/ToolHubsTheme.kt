package com.sylestia.toolhubs.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

// Graphite canvas with cyan connectivity and amber recovery states.
val CurioRoseWood = Color(0xFF18D5E5)
val CurioRoseDark = Color(0xFF0A9EAD)
val CurioInk = Color(0xFFE7F5F6)
val CurioCream = Color(0xFF0B1115)
val CurioSurface = Color(0xFF131D22)
val CurioDarkBackground = Color(0xFF0B1115)
val CurioDarkSurface = Color(0xFF131D22)
val CurioDarkCard = Color(0xFF1A282E)
val CurioAmber = Color(0xFFFFB74D)
val CurioFailure = Color(0xFFFF6B6B)

private val CurioLightColors = lightColorScheme(
    primary = CurioInk,
    onPrimary = Color.White,
    secondary = CurioRoseWood,
    onSecondary = Color.White,
    background = CurioCream,
    onBackground = CurioInk,
    surface = CurioSurface,
    onSurface = CurioInk,
)

private val CurioDarkColors = darkColorScheme(
    primary = CurioRoseWood,
    onPrimary = Color.White,
    secondary = CurioRoseWood,
    onSecondary = Color.White,
    background = CurioDarkBackground,
    onBackground = Color.White,
    surface = CurioDarkSurface,
    onSurface = Color.White,
)

@Composable
fun ToolHubsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) CurioDarkColors else CurioLightColors,
        typography = Typography(),
        content = content,
    )
}
