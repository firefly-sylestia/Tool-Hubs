package com.sylestia.toolhubs.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ------------------------------------------------------------------------------------------------
 * Tool Hubs design system — "Aurora Instrument"
 *
 * One opinionated palette shared by every tool: a calm porcelain canvas in daylight, a deep
 * blue-black observatory at night, and a single luminous aurora accent that carries state.
 *
 * The previous revision only declared a handful of Material 3 roles, so containers, outlines and
 * surface tints fell back to framework defaults and read as washed-out greys. Every role below is
 * now explicit so components look deliberate in both modes.
 * ---------------------------------------------------------------------------------------------- */

/** Duration of the light/dark cross-fade. Long enough to feel like a dimmer, short enough to feel immediate. */
const val ThemeTransitionMillis = 650

// ── Daylight palette ─────────────────────────────────────────────────────────────────────────────
private val DayCanvas = Color(0xFFF8F5FC)
private val DaySurface = Color(0xFFFFFFFF)
private val DayInk = Color(0xFF211A2B)
private val DayPrimary = Color(0xFF6750A4)
private val DaySecondary = Color(0xFFB3265E)
private val DayTertiary = Color(0xFF805600)

// ── Night palette ────────────────────────────────────────────────────────────────────────────────
private val NightCanvas = Color(0xFF14111C)
private val NightSurface = Color(0xFF1D1927)
private val NightInk = Color(0xFFF1EAF8)
private val NightPrimary = Color(0xFFD0BCFF)
private val NightSecondary = Color(0xFFFFB1C8)
private val NightTertiary = Color(0xFFE9C46A)

private val LightScheme = lightColorScheme(
    primary = DayPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    inversePrimary = Color(0xFFD0BCFF),

    secondary = DaySecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD8E4),
    onSecondaryContainer = Color(0xFF3E001D),

    tertiary = DayTertiary,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF271900),

    background = DayCanvas,
    onBackground = DayInk,
    surface = DaySurface,
    onSurface = DayInk,
    surfaceVariant = Color(0xFFE8DEF8),
    onSurfaceVariant = Color(0xFF494454),
    surfaceTint = DayPrimary,
    surfaceDim = Color(0xFFE7E0EC),
    surfaceBright = Color(0xFFFFFBFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3EDF7),
    surfaceContainer = Color(0xFFECE6F0),
    surfaceContainerHigh = Color(0xFFE6DDEC),
    surfaceContainerHighest = Color(0xFFE1D7E8),

    inverseSurface = Color(0xFF322F3A),
    inverseOnSurface = Color(0xFFECE6F0),

    outline = Color(0xFF8B97A3),
    outlineVariant = Color(0xFFCFD7DF),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0xFF000000),
)

private val DarkScheme = darkColorScheme(
    primary = NightPrimary,
    onPrimary = Color(0xFF00372E),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    inversePrimary = Color(0xFF6750A4),

    secondary = NightSecondary,
    onSecondary = Color(0xFF601D36),
    secondaryContainer = Color(0xFF7D294A),
    onSecondaryContainer = Color(0xFFFFD8E4),

    tertiary = NightTertiary,
    onTertiary = Color(0xFF3D2E00),
    tertiaryContainer = Color(0xFF5A4300),
    onTertiaryContainer = Color(0xFFFFE8B3),

    background = NightCanvas,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF342D40),
    onSurfaceVariant = Color(0xFFCAC2D6),
    surfaceTint = NightPrimary,
    surfaceDim = Color(0xFF100D17),
    surfaceBright = Color(0xFF393144),
    surfaceContainerLowest = Color(0xFF0F0C15),
    surfaceContainerLow = Color(0xFF17131F),
    surfaceContainer = Color(0xFF211B2A),
    surfaceContainerHigh = Color(0xFF2A2335),
    surfaceContainerHighest = Color(0xFF342B42),

    inverseSurface = Color(0xFFF1EAF8),
    inverseOnSurface = Color(0xFF1D1927),

    outline = Color(0xFF827A8D),
    outlineVariant = Color(0xFF443A50),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

/**
 * Roles Material 3 has no slot for: aurora gradients, glow colours and glass hairlines.
 * These are what make the surface feel crafted rather than stock.
 */
@Immutable
data class HubAccents(
    val auroraStart: Color,
    val auroraMid: Color,
    val auroraEnd: Color,
    val glow: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val hairline: Color,
    val shadow: Color,
    val glowStrength: Float,
    val glassStrength: Float,
)

private val LightAccents = HubAccents(
    auroraStart = Color(0xFF6750A4),
    auroraMid = Color(0xFF3B54C4),
    auroraEnd = Color(0xFFB4661A),
    glow = Color(0xFF6750A4),
    success = Color(0xFF1B7F5F),
    warning = Color(0xFFB4661A),
    danger = Color(0xFFB3261E),
    glassTop = Color(0xFFFFFFFF),
    glassBottom = Color(0xFFF1F5F9),
    hairline = Color(0xFFD8E0E8),
    shadow = Color(0xFF0D1621),
    glowStrength = 0.10f,
    glassStrength = 1f,
)

private val DarkAccents = HubAccents(
    auroraStart = Color(0xFFD0BCFF),
    auroraMid = Color(0xFF8FA4FF),
    auroraEnd = Color(0xFFF2B573),
    glow = Color(0xFFD0BCFF),
    success = Color(0xFF63D9A6),
    warning = Color(0xFFF2B573),
    danger = Color(0xFFFF8F80),
    glassTop = Color(0xFF1A2431),
    glassBottom = Color(0xFF0F1720),
    hairline = Color(0xFF443A50),
    shadow = Color(0xFF000000),
    glowStrength = 0.22f,
    glassStrength = 1f,
)

val LocalHubAccents = staticCompositionLocalOf { LightAccents }

/** `MaterialTheme.hubAccents.auroraStart` — aurora/glass tokens that Material 3 does not model. */
val MaterialTheme.hubAccents: HubAccents
    @Composable @ReadOnlyComposable get() = LocalHubAccents.current

val HubTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp),
    )
}

val HubShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

/**
 * Wraps the app in the Aurora Instrument theme.
 *
 * [darkTheme] defaults to the system setting. Canvas-level roles are animated so toggling the
 * appearance dims the whole app instead of hard-cutting it.
 */
@Composable
fun ToolHubsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val target = if (darkTheme) DarkScheme else LightScheme
    val spec = tween<Color>(ThemeTransitionMillis, easing = FastOutSlowInEasing)

    val scheme = target.copy(
        primary = animateColorAsState(target.primary, spec, label = "theme_primary").value,
        onPrimary = animateColorAsState(target.onPrimary, spec, label = "theme_on_primary").value,
        primaryContainer = animateColorAsState(target.primaryContainer, spec, label = "theme_primary_container").value,
        secondary = animateColorAsState(target.secondary, spec, label = "theme_secondary").value,
        tertiary = animateColorAsState(target.tertiary, spec, label = "theme_tertiary").value,
        background = animateColorAsState(target.background, spec, label = "theme_canvas").value,
        onBackground = animateColorAsState(target.onBackground, spec, label = "theme_on_canvas").value,
        surface = animateColorAsState(target.surface, spec, label = "theme_surface").value,
        onSurface = animateColorAsState(target.onSurface, spec, label = "theme_ink").value,
        surfaceVariant = animateColorAsState(target.surfaceVariant, spec, label = "theme_surface_variant").value,
        onSurfaceVariant = animateColorAsState(target.onSurfaceVariant, spec, label = "theme_on_surface_variant").value,
        surfaceContainer = animateColorAsState(target.surfaceContainer, spec, label = "theme_container").value,
        surfaceContainerHigh = animateColorAsState(target.surfaceContainerHigh, spec, label = "theme_container_high").value,
        outline = animateColorAsState(target.outline, spec, label = "theme_outline").value,
        outlineVariant = animateColorAsState(target.outlineVariant, spec, label = "theme_outline_variant").value,
        error = animateColorAsState(target.error, spec, label = "theme_error").value,
    )

    val base = if (darkTheme) DarkAccents else LightAccents
    val accents = base.copy(
        auroraStart = animateColorAsState(base.auroraStart, spec, label = "aurora_start").value,
        auroraMid = animateColorAsState(base.auroraMid, spec, label = "aurora_mid").value,
        auroraEnd = animateColorAsState(base.auroraEnd, spec, label = "aurora_end").value,
        glow = animateColorAsState(base.glow, spec, label = "aurora_glow").value,
        glassTop = animateColorAsState(base.glassTop, spec, label = "glass_top").value,
        glassBottom = animateColorAsState(base.glassBottom, spec, label = "glass_bottom").value,
        hairline = animateColorAsState(base.hairline, spec, label = "hairline").value,
    )

    CompositionLocalProvider(LocalHubAccents provides accents) {
        MaterialTheme(
            colorScheme = scheme,
            typography = HubTypography,
            shapes = HubShapes,
            content = content,
        )
    }
}

/* ------------------------------------------------------------------------------------------------
 * Legacy berry/cream tokens.
 *
 * Data Warden still references these, so their values are frozen exactly as they were: introducing
 * the Aurora palette must not restyle software that was not part of this change. (The calculator and
 * Music tools that also used them have since been deleted.) New code should read
 * `MaterialTheme.colorScheme` or `MaterialTheme.hubAccents`.
 * ---------------------------------------------------------------------------------------------- */

val CurioRoseWood = Color(0xFFB6415D)
val CurioRoseDark = Color(0xFF76364A)
val CurioInk = Color(0xFF24111A)
val CurioCream = Color(0xFFF7F7FB)
val CurioSurface = Color(0xFFFFFFFF)
val CurioDarkBackground = Color(0xFF11131C)
val CurioDarkSurface = Color(0xFF1A1E2B)
val CurioDarkCard = Color(0xFF222839)
