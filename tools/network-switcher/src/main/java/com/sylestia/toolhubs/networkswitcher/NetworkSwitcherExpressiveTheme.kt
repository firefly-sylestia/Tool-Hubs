package com.sylestia.toolhubs.networkswitcher

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ExpressiveLight = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFFB3265E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD8E4),
    onSecondaryContainer = Color(0xFF3E001D),
    tertiary = Color(0xFF805600),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF271900),
    background = Color(0xFFF8F5FC),
    onBackground = Color(0xFF211A2B),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF211A2B),
    surfaceVariant = Color(0xFFE8DEF8),
    onSurfaceVariant = Color(0xFF494454),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF3EDF7),
    surfaceContainer = Color(0xFFECE6F0),
    surfaceContainerHigh = Color(0xFFE6DDEC),
    surfaceContainerHighest = Color(0xFFE1D7E8),
    outline = Color(0xFF817889),
    outlineVariant = Color(0xFFD0C5D8),
)

private val ExpressiveDark = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFFFB1C8),
    onSecondary = Color(0xFF601D36),
    secondaryContainer = Color(0xFF7D294A),
    onSecondaryContainer = Color(0xFFFFD8E4),
    tertiary = Color(0xFFE9C46A),
    onTertiary = Color(0xFF3D2E00),
    tertiaryContainer = Color(0xFF5A4300),
    onTertiaryContainer = Color(0xFFFFE8B3),
    background = Color(0xFF14111C),
    onBackground = Color(0xFFF1EAF8),
    surface = Color(0xFF1D1927),
    onSurface = Color(0xFFF1EAF8),
    surfaceVariant = Color(0xFF342D40),
    onSurfaceVariant = Color(0xFFCAC2D6),
    surfaceContainerLowest = Color(0xFF0F0C15),
    surfaceContainerLow = Color(0xFF17131F),
    surfaceContainer = Color(0xFF211B2A),
    surfaceContainerHigh = Color(0xFF2A2335),
    surfaceContainerHighest = Color(0xFF342B42),
    outline = Color(0xFF827A8D),
    outlineVariant = Color(0xFF443A50),
)

@Composable
internal fun NetworkSwitcherExpressiveTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) ExpressiveDark else ExpressiveLight,
        content = content,
    )
}
