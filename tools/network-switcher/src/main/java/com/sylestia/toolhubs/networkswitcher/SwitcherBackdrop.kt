package com.sylestia.toolhubs.networkswitcher

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import com.sylestia.toolhubs.designsystem.hubAccents

/*
 * Network Switcher's own backdrop.
 *
 * This is deliberately module-local. The shared HubBackdrop in `:core:ui` is still used by Data
 * Warden, which is frozen and must render exactly as before. The calculator and Music tools that
 * also used it have been deleted from the repository.
 *
 * Everything here is driven by continuous infinite transitions rather than one-off animations, so the
 * surface never settles: light orbits the upper third, a grid drifts upward forever, a particle field
 * rises with it, and a sheen crosses the panel on its own schedule. All of the layers move at
 * different periods, which is what stops the motion from reading as a single repeating loop.
 */
@Composable
fun SwitcherBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val accents = MaterialTheme.hubAccents
    val scheme = MaterialTheme.colorScheme

    val motion = rememberInfiniteTransition(label = "switcher_backdrop")
    val orbit by motion.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(52_000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbit",
    )
    val driftA by motion.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(19_000, easing = LinearEasing), RepeatMode.Restart),
        label = "drift_a",
    )
    val driftB by motion.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(27_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift_b",
    )
    val rise by motion.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rise",
    )
    val breathe by motion.animateFloat(
        0.45f, 1f,
        infiniteRepeatable(tween(4_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val sheen by motion.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(9_500, easing = LinearEasing), RepeatMode.Restart),
        label = "sheen",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(scheme.surfaceContainerLowest, scheme.background, scheme.surfaceContainerLow),
                ),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val glow = accents.glowStrength * breathe
            val light = scheme.background.luminance() > 0.5f

            // 1. Orbiting soft light. One slow sweep around the upper third is what makes the field
            //    feel alive even when nothing else in the app is moving.
            rotate(orbit, pivot = Offset(w * 0.5f, h * 0.24f)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accents.auroraMid.copy(alpha = glow * 0.22f),
                            accents.auroraStart.copy(alpha = glow * 0.08f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.5f, h * 0.24f),
                        radius = w * 0.62f,
                    ),
                    radius = w * 0.62f,
                    center = Offset(w * 0.5f, h * 0.24f),
                )
            }

            // 2. Three aurora blooms on independent periods.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accents.auroraStart.copy(alpha = glow * 0.34f), Color.Transparent),
                    center = Offset(w * (0.10f + 0.16f * driftA), h * (0.04f + 0.07f * driftB)),
                    radius = w * 0.74f,
                ),
                radius = w * 0.74f,
                center = Offset(w * (0.10f + 0.16f * driftA), h * (0.04f + 0.07f * driftB)),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accents.auroraMid.copy(alpha = glow * 0.30f), Color.Transparent),
                    center = Offset(w * (0.94f - 0.18f * driftB), h * (0.34f + 0.10f * driftA)),
                    radius = w * 0.66f,
                ),
                radius = w * 0.66f,
                center = Offset(w * (0.94f - 0.18f * driftB), h * (0.34f + 0.10f * driftA)),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accents.auroraEnd.copy(alpha = glow * 0.22f), Color.Transparent),
                    center = Offset(w * (0.18f + 0.18f * driftB), h * (0.96f - 0.12f * driftA)),
                    radius = w * 0.72f,
                ),
                radius = w * 0.72f,
                center = Offset(w * (0.18f + 0.18f * driftB), h * (0.96f - 0.12f * driftA)),
            )

            // 3. Grid drifting upward forever. The offset wraps on the gap, so the pattern is seamless
            //    and the eye reads it as continuous travel rather than a loop restart.
            val gap = 54f
            val travel = rise * gap
            var y = travel - gap
            while (y < h + gap) {
                drawLine(
                    color = accents.hairline.copy(alpha = 0.20f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                )
                y += gap
            }
            var x = 0f
            while (x < w) {
                drawLine(
                    color = accents.hairline.copy(alpha = 0.10f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f,
                )
                x += gap
            }

            // 4. Rising particle field, wrapped so it never pops.
            drawParticles(
                width = w,
                height = h,
                rise = rise,
                accent = accents.auroraStart,
                hairline = accents.hairline,
            )

            // 5. Travelling diagonal sheen.
            val sx = w * (sheen * 1.6f - 0.3f)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = if (light) 0.18f else 0.055f),
                        Color.Transparent,
                    ),
                    start = Offset(sx - w * 0.34f, 0f),
                    end = Offset(sx + w * 0.34f, h),
                ),
            )

            // 6. A slow scanline that sweeps the panel and fades at both ends.
            val scanY = h * (driftA * 1.25f - 0.15f)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, accents.auroraMid.copy(alpha = glow * 0.07f), Color.Transparent),
                    startY = scanY - h * 0.10f,
                    endY = scanY + h * 0.10f,
                ),
                topLeft = Offset(0f, scanY - h * 0.10f),
                size = androidx.compose.ui.geometry.Size(w, h * 0.20f),
            )

            // 7. Vignette so the corners stay quiet behind content.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, scheme.background.copy(alpha = 0.55f)),
                    center = Offset(w * 0.5f, h * 0.42f),
                    radius = h * 0.78f,
                ),
            )
        }
        content()
    }
}

/**
 * A field of small points that rises and wraps. Positions are derived from the index rather than
 * random numbers, so the pattern shifts every frame but stays stable across recompositions.
 */
private fun DrawScope.drawParticles(
    width: Float,
    height: Float,
    rise: Float,
    accent: Color,
    hairline: Color,
) {
    val columns = 9
    val rows = 16
    val stepX = width / columns
    val stepY = height / rows
    val travel = rise * stepY * rows

    for (row in 0..rows) {
        for (column in 0..columns) {
            // Deterministic jitter keeps the grid from reading as a chessboard.
            val wobble = ((row * 7 + column * 13) % 11) / 11f
            val x = column * stepX + wobble * stepX * 0.6f
            val rawY = row * stepY + wobble * stepY * 0.5f - travel
            val y = ((rawY % (height + stepY)) + height + stepY) % (height + stepY) - stepY

            val depth = 0.25f + wobble * 0.75f
            val radius = 0.8f + depth * 1.5f
            val color = if (wobble > 0.72f) accent.copy(alpha = 0.20f * depth) else hairline.copy(alpha = 0.22f * depth)
            drawCircle(color = color, radius = radius, center = Offset(x, y))
        }
    }
}

/** Rough perceived brightness, used to decide how strong the light sheen should be. */
private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
