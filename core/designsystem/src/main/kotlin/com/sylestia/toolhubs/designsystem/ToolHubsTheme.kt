package com.sylestia.toolhubs.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Curio-inspired palette: warm paper, plum ink, and rose-wood accents.
val CurioRoseWood = Color(0xFFC46B7C)
val CurioRoseDark = Color(0xFF8B5E6B)
val CurioInk = Color(0xFF3B0A17)
val CurioCream = Color(0xFFF7F0E4)
val CurioSurface = Color(0xFFFFFBF5)
val CurioDarkBackground = Color(0xFF1A1A2E)
val CurioDarkSurface = Color(0xFF252535)
val CurioDarkCard = Color(0xFF2A2A3A)

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
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) CurioDarkColors else CurioLightColors,
        typography = Typography(),
        content = content,
    )
}
