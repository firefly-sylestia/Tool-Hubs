package com.sylestia.toolhubs.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

// Refreshed neutral canvas with crisp berry actions and high-contrast ink.
val CurioRoseWood = Color(0xFFB6415D)
val CurioRoseDark = Color(0xFF76364A)
val CurioInk = Color(0xFF24111A)
val CurioCream = Color(0xFFF7F7FB)
val CurioSurface = Color(0xFFFFFFFF)
val CurioDarkBackground = Color(0xFF11131C)
val CurioDarkSurface = Color(0xFF1A1E2B)
val CurioDarkCard = Color(0xFF222839)

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
