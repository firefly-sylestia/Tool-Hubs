package com.sylestia.toolhubs.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.CurioInk
import com.sylestia.toolhubs.designsystem.CurioRoseDark
import com.sylestia.toolhubs.designsystem.hubAccents
import kotlinx.coroutines.delay

/* ------------------------------------------------------------------------------------------------
 * Tool Hubs shared UI kit.
 *
 * Everything here is theme-aware, motion-aware and built from Material 3 primitives so the four
 * tools look and behave like one product. No component hard-codes a light-only or dark-only colour.
 * ---------------------------------------------------------------------------------------------- */

/** Slow, continuous aurora field that sits behind every screen. */
@Composable
fun HubBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val accents = MaterialTheme.hubAccents
    val scheme = MaterialTheme.colorScheme

    val drift = rememberInfiniteTransition(label = "hub_backdrop")
    val t1 by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(26_000), RepeatMode.Restart), label = "d1")
    val t2 by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(34_000), RepeatMode.Reverse), label = "d2")
    val t3 by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(42_000), RepeatMode.Reverse), label = "d3")
    val sheen by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(16_000), RepeatMode.Restart), label = "sheen")
    val breathe by drift.animateFloat(0.72f, 1f, infiniteRepeatable(tween(5_200), RepeatMode.Reverse), label = "breathe")

    Box(modifier.fillMaxSize().background(scheme.background)) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val glow = accents.glowStrength * breathe

            // Aurora bloom 1 — high-left.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accents.auroraStart.copy(alpha = glow), Color.Transparent),
                    center = Offset(w * (0.14f + 0.10f * t1), h * (0.06f + 0.05f * t2)),
                    radius = w * 0.78f,
                ),
                radius = w * 0.78f,
                center = Offset(w * (0.14f + 0.10f * t1), h * (0.06f + 0.05f * t2)),
            )

            // Aurora bloom 2 — mid-right.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accents.auroraMid.copy(alpha = glow * 0.9f), Color.Transparent),
                    center = Offset(w * (0.92f - 0.12f * t2), h * (0.30f + 0.08f * t3)),
                    radius = w * 0.66f,
                ),
                radius = w * 0.66f,
                center = Offset(w * (0.92f - 0.12f * t2), h * (0.30f + 0.08f * t3)),
            )

            // Aurora bloom 3 — low-left, warm.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accents.auroraEnd.copy(alpha = glow * 0.55f), Color.Transparent),
                    center = Offset(w * (0.22f + 0.14f * t3), h * (0.94f - 0.10f * t1)),
                    radius = w * 0.70f,
                ),
                radius = w * 0.70f,
                center = Offset(w * (0.22f + 0.14f * t3), h * (0.94f - 0.10f * t1)),
            )

            // Faint measurement grid — keeps the surface feeling like an instrument panel.
            val gap = 46f
            var y = 0f
            while (y < h) {
                drawLine(accents.hairline.copy(alpha = 0.16f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                y += gap
            }

            // Travelling diagonal sheen.
            val sx = w * (sheen * 1.5f - 0.25f)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = if (scheme.background.luminance() > 0.5f) 0.16f else 0.05f), Color.Transparent),
                    start = Offset(sx - w * 0.30f, 0f),
                    end = Offset(sx + w * 0.30f, h),
                ),
            )
        }
        content()
    }
}

private fun Color.luminance(): Float = (0.299f * red + 0.587f * green + 0.114f * blue)

/** Staggered entrance used to choreograph lists and cards as a screen opens. */
@Composable
fun HubReveal(index: Int = 0, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((index * 55L).coerceAtMost(500L))
        shown = true
    }
    val p by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = 240f), label = "reveal")
    Box(
        modifier.graphicsLayer {
            alpha = p
            translationY = (1f - p) * 30f
            val s = 0.975f + 0.025f * p
            scaleX = s
            scaleY = s
        }
    ) { content() }
}

/**
 * Kicker + title block with the tool's mark in an aurora chip.
 *
 * This is the new Aurora header. [HubScreenHeader] is deliberately left untouched below because Data
 * Warden still uses it and must not be restyled; the calculator and Music tools that also used it
 * have since been deleted.
 */
@Composable
fun HubHeroHeader(
    kicker: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            HubIconChip(icon)
            Spacer(Modifier.width(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                kicker.uppercase(),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.4.sp,
            )
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Rounded aurora-tinted square that holds a single icon. */
@Composable
fun HubIconChip(icon: ImageVector, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    val accents = MaterialTheme.hubAccents
    Box(
        modifier
            .size(48.dp)
            .shadow(10.dp, RoundedCornerShape(17.dp), clip = false, ambientColor = accents.shadow, spotColor = accents.shadow)
            .clip(RoundedCornerShape(17.dp))
            .background(accent.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(23.dp))
    }
}

/**
 * The primary surface of the app: a layered "glass" card with a hairline edge, a soft drop shadow
 * and an optional accent bloom in the top-left corner.
 */
@Composable
fun HubGlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accents = MaterialTheme.hubAccents
    val shape = RoundedCornerShape(26.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.982f else 1f, spring(dampingRatio = 0.55f, stiffness = 620f), label = "card_press")
    val elevation by animateDpAsState(if (pressed) 6.dp else 16.dp, spring(dampingRatio = 0.7f, stiffness = 500f), label = "card_elev")

    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elevation, shape, clip = false, ambientColor = accents.shadow, spotColor = accents.shadow)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else Modifier
            )
    ) {
        if (accent != null) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = size.width * 0.72f,
                    ),
                    radius = size.width * 0.72f,
                    center = Offset(0f, 0f),
                )
            }
        }
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/* ------------------------------------------------------------------------------------------------
 * Frozen primitives for the tools that were not part of this change.
 * ---------------------------------------------------------------------------------------------- */

/** Original berry/cream screen header. Unchanged so the other tools render exactly as before. */
@Composable
fun HubScreenHeader(kicker: String, title: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(kicker.uppercase(), color = CurioRoseDark, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(title, color = CurioInk, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
    }
}

/** Original berry/cream metric tile. Unchanged so the other tools render exactly as before. */
@Composable
fun HubMetricTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(14.dp)) {
            Text(value, color = accent, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text(label.uppercase(), color = CurioInk.copy(alpha = .65f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

/** Small labelled value used inside cards. */
@Composable
fun HubStatTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(value, color = accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Text(
            label.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
    }
}

/** Status chip with a live dot. */
@Composable
fun HubPill(label: String, active: Boolean, accent: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    val accents = MaterialTheme.hubAccents
    val tint = if (active) accent else MaterialTheme.colorScheme.outline
    Row(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(tint.copy(alpha = if (active) 0.22f else 0.14f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HubStatusDot(color = if (active) accents.success else tint, pulsing = active, size = 7.dp)
        Spacer(Modifier.width(7.dp))
        Text(
            label.uppercase(),
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.1.sp,
            color = if (active) tint else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Breathing dot used to signal live state without a spinner. */
@Composable
fun HubStatusDot(color: Color, pulsing: Boolean = false, size: Dp = 9.dp) {
    val transition = rememberInfiniteTransition(label = "dot")
    val pulse by transition.animateFloat(
        if (pulsing) 0.55f else 1f,
        1f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dot_pulse",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size * 2.2f)) {
        if (pulsing) {
            Box(
                Modifier
                    .size(size * (1.3f + pulse * 0.7f))
                    .background(color.copy(alpha = 0.24f * (1f - pulse * 0.5f)), CircleShape)
            )
        }
        Box(Modifier.size(size).background(color, CircleShape))
    }
}

/** Primary action: aurora gradient, inner highlight, spring press. */
@Composable
fun HubPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val accents = MaterialTheme.hubAccents
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, spring(dampingRatio = 0.48f, stiffness = 760f), label = "btn_press")
    val start = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val end by animateColorAsState(
        if (enabled) accents.auroraMid else MaterialTheme.colorScheme.outline,
        tween(300),
        label = "btn_end",
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(14.dp, RoundedCornerShape(20.dp), clip = false, ambientColor = accents.shadow, spotColor = accents.shadow)
            .clip(RoundedCornerShape(20.dp))
            .background(if (enabled) start else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 22.dp)
                .background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(19.dp),
                    strokeWidth = 2.2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.4.sp,
            )
        }
    }
}

/** Lower-emphasis action that still feels intentional. */
@Composable
fun HubQuietButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "quiet_press")
    val accents = MaterialTheme.hubAccents
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Animated segmented control. */
@Composable
fun HubSegmented(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val accents = MaterialTheme.hubAccents
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, accents.hairline.copy(alpha = 0.7f), RoundedCornerShape(17.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                tween(260, easing = FastOutSlowInEasing),
                label = "seg_fill",
            )
            val fg by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(260),
                label = "seg_fg",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(fill)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** A settings row with a title, supporting copy and a right-aligned switch. */
@Composable
fun HubToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (subtitle != null) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
    }
}

/** A settings row with a slider and its current value. */
@Composable
fun HubSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(valueLabel, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, fontSize = 14.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        )
    }
}

/** A section label with an optional trailing action. */
@Composable
fun HubSectionTitle(text: String, modifier: Modifier = Modifier, action: (() -> Unit)? = null, actionLabel: String = "View all") {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.6.sp,
        )
        if (action != null) {
            Text(
                actionLabel,
                Modifier.clickable(onClick = action).padding(4.dp),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** One item in a [HubBottomNav]. */
data class HubNavItem(val icon: ImageVector, val label: String)

/** Floating navigation bar with an expanding selected tab. */
@Composable
fun HubBottomNav(
    items: List<HubNavItem>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val accents = MaterialTheme.hubAccents
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(74.dp)
                .shadow(20.dp, shape, clip = false, ambientColor = accents.shadow, spotColor = accents.shadow)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEachIndexed { index, item ->
                HubNavButton(
                    item = item,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(if (index == selectedIndex) 1.35f else 0.9f),
                )
            }
        }
    }
}

@Composable
private fun RowScope.HubNavButton(item: HubNavItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (selected) 1f else 0.93f, spring(dampingRatio = 0.6f, stiffness = 620f), label = "nav_scale")
    val lift by animateDpAsState(if (selected) 0.dp else 2.dp, spring(dampingRatio = 0.65f, stiffness = 650f), label = "nav_lift")
    val safeLift = lift.coerceAtLeast(0.dp)
    val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(280),
        label = "nav_fill",
    )
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(280),
        label = "nav_tint",
    )
    val iconSize by animateDpAsState(if (selected) 24.dp else 20.dp, spring(dampingRatio = 0.55f, stiffness = 640f), label = "nav_icon")

    Box(
        modifier
            .fillMaxHeight()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .padding(top = safeLift)
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(iconSize))
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(tween(140)) + expandVertically(tween(180)),
                exit = fadeOut(tween(90)) + shrinkVertically(tween(140)),
            ) {
                Text(
                    item.label.uppercase(),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    color = tint,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/** Friendly placeholder for screens with no data yet. */
@Composable
fun HubEmptyState(title: String, message: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.Info) {
    val accents = MaterialTheme.hubAccents
    Column(
        modifier.fillMaxWidth().padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(accents.auroraStart.copy(alpha = 0.20f), accents.auroraMid.copy(alpha = 0.12f))))
                .border(1.dp, accents.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Thin horizontal rule that matches the glass vocabulary. */
@Composable
fun HubHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.hubAccents.hairline.copy(alpha = 0.6f))
    )
}

/** Circular progress ring whose colour follows the theme accent. */
@Composable
fun HubProgressRing(progress: Float, modifier: Modifier = Modifier, strokeWidth: Dp = 4.dp, accent: Color = MaterialTheme.colorScheme.primary) {
    val accents = MaterialTheme.hubAccents
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), spring(dampingRatio = 0.8f, stiffness = 180f), label = "ring")
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        drawArc(
            color = accents.hairline.copy(alpha = 0.5f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawArc(
            brush = Brush.sweepGradient(listOf(accent, accents.auroraMid, accent)),
            startAngle = -90f,
            sweepAngle = 360f * animated,
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** Thin labelled meter bar. */
@Composable
fun HubMeter(fraction: Float, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary, height: Dp = 8.dp) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(dampingRatio = 0.85f, stiffness = 200f), label = "meter")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(accent, MaterialTheme.hubAccents.auroraMid)))
        )
    }
}

/** Full-bleed divider used between list rows. */
@Composable
fun HubRowDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.hubAccents.hairline.copy(alpha = 0.45f))
    )
}
