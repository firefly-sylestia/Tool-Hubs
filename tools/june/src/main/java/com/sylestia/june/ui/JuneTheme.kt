package com.sylestia.june.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Paper = Color(0xFFF5F2EC)
private val Ink = Color(0xFF343B36)
private val Surface = Color(0xFFEAE5DC)
private val Accent = Color(0xFFA38D76)
private val DarkBackground = Color(0xFF191B19)
private val DarkSurface = Color(0xFF242824)
private val DarkInk = Color(0xFFE8E5DE)
private val DarkAccent = Color(0xFFB8A38C)

private val Light = lightColorScheme(
    primary = Ink, onPrimary = Paper, secondary = Accent, onSecondary = Color.White,
    background = Paper, onBackground = Ink, surface = Paper, onSurface = Ink,
    surfaceVariant = Surface, onSurfaceVariant = Color(0xFF77786F), outline = Color(0xFFD4CEC3)
)
private val Dark = darkColorScheme(
    primary = DarkInk, onPrimary = DarkBackground, secondary = DarkAccent, onSecondary = DarkBackground,
    background = DarkBackground, onBackground = DarkInk, surface = DarkBackground, onSurface = DarkInk,
    surfaceVariant = DarkSurface, onSurfaceVariant = Color(0xFFB8B7AF), outline = Color(0xFF454A44)
)

private val JuneTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 42.sp, lineHeight = 48.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 25.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 28.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp)
)

@Composable
fun JUNETheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = JuneTypography,
        content = content
    )
}
