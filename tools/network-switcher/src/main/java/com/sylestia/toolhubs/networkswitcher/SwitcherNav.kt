package com.sylestia.toolhubs.networkswitcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.hubAccents
import com.sylestia.toolhubs.ui.HubNavItem

/*
 * Network Switcher's own navigation chrome.
 *
 * This deliberately lives in the module rather than in `:core:ui`: the shared HubBottomNav is used
 * by Data Warden, which is frozen and must not be restyled. (The calculator and Music tools that
 * used it as well have been deleted from the repository.)
 */

/**
 * Bottom navigation bar for the switcher.
 *
 * The previous bar changed the *width* of the selected tab (the selected one took 1.35 weight), so
 * every tap triggered a full re-measure of five children and the bar visibly reflowed. This one
 * keeps every slot identical and slides a single accent pill between them: one animation per tap,
 * no layout change, and the label stays in place instead of expanding and collapsing.
 */
@Composable
fun SwitcherBottomNav(
    items: List<HubNavItem>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val accents = MaterialTheme.hubAccents
    val shape = RoundedCornerShape(26.dp)
    var barWidth by remember { mutableIntStateOf(0) }
    val slotWidth = if (items.isEmpty() || barWidth == 0) 0f else barWidth.toFloat() / items.size
    val position by animateFloatAsState(
        selectedIndex.toFloat(),
        spring(dampingRatio = 0.78f, stiffness = 380f),
        label = "nav_slide",
    )

    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .shadow(18.dp, shape, clip = false, ambientColor = accents.shadow, spotColor = accents.shadow)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(8.dp),
        ) {
            Box(Modifier.fillMaxSize().onSizeChanged { barWidth = it.width }) {
                if (slotWidth > 0f) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(1f / items.size)
                            .graphicsLayer { translationX = position * slotWidth }
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Row(Modifier.fillMaxSize()) {
                    items.forEachIndexed { index, item ->
                        SwitcherNavSlot(
                            item = item,
                            selected = index == selectedIndex,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitcherNavSlot(
    item: HubNavItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(220),
        label = "nav_tint",
    )
    val labelAlpha by animateFloatAsState(if (selected) 1f else 0.5f, tween(220), label = "nav_label")
    val iconScale by animateFloatAsState(
        if (selected) 1f else 0.9f,
        spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "nav_icon_scale",
    )

    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            item.icon,
            contentDescription = item.label,
            tint = tint,
            modifier = Modifier
                .size(21.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
        )
        Spacer(Modifier.height(2.dp))
        Text(
            item.label.uppercase(),
            color = tint.copy(alpha = labelAlpha),
            fontSize = 8.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
            maxLines = 1,
        )
    }
}

/**
 * The page header: a solid icon badge, a breathing title and a scroll-reactive filament.
 *
 * Two things changed here beyond styling. The icon badge used to draw its accent colour at ~22%
 * alpha straight over the animated backdrop, so the aurora shimmered through the glyph and made it
 * look glitchy; the badge is now an opaque surface colour with a crisp accent border. And the whole
 * band now reacts to the page's scroll position, which is why every page hands its [ScrollState] in:
 * the badge contracts, the title scales from its left edge, the subtitle dissolves and the filament
 * under the header fills as you scroll. No page transition or backdrop layer moves with the finger,
 * so the header is the one part of the screen that feels physically attached to the content.
 */
@Composable
fun SwitcherHeader(
    kicker: String,
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    scrollState: ScrollState? = null,
    onOpenSettings: () -> Unit,
) {
    val accents = MaterialTheme.hubAccents
    val scheme = MaterialTheme.colorScheme
    val collapse = scrollState?.let { (it.value / 260f).coerceIn(0f, 1f) } ?: 0f

    val motion = rememberInfiniteTransition(label = "header")
    val glow by motion.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(5_200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "header_glow",
    )
    val sweep by motion.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(4_400, easing = LinearEasing), RepeatMode.Restart),
        label = "header_sweep",
    )

    val titleColor = lerp(scheme.onSurface, accents.auroraMid, 0.10f + 0.22f * glow)

    /*
     * The rail.
     *
     * A page header that is the first child of a scrolling column leaves the screen with everything
     * else, so this pins it: translating it down by exactly the scroll offset cancels the scroll and
     * leaves the header where it started. What makes it read as a rail rather than a stuck header is
     * the glass that grows behind it — at the top of the page there is no panel at all and the header
     * is part of the content, and once you scroll, a frosted panel *bleeds past the header's own
     * bounds* so the status-bar strip is covered too. Content keeps sliding underneath; it simply
     * cannot be read there.
     */
    val pin = (scrollState?.value?.toFloat() ?: 0f).coerceAtLeast(0f)
    // Measured in dp, not pixels: a raw pixel threshold would make the rail take three times as
    // long to appear on a low-density screen as it does on a high-density one.
    val barrierAt = with(LocalDensity.current) { 40.dp.toPx() }
    val barrier = (pin / barrierAt).coerceIn(0f, 1f)
    val railColor = scheme.surfaceContainer.copy(alpha = 0.92f * barrier)
    val railProgress = scrollState?.let { state ->
        if (state.maxValue > 0) (state.value.toFloat() / state.maxValue).coerceIn(0f, 1f) else 0f
    } ?: 0f
    // Idle, the filament breathes; pinned, it becomes the page's scroll position.
    val filament = if (barrier > 0.02f) {
        railProgress.coerceAtLeast(0.06f * barrier)
    } else if (collapse > 0.02f) {
        collapse
    } else {
        0.16f + 0.14f * glow
    }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .graphicsLayer { translationY = pin }
            .drawBehind {
                if (barrier > 0.01f) {
                    val bleed = 64.dp.toPx()
                    drawRect(
                        color = railColor,
                        topLeft = Offset(0f, -bleed),
                        size = Size(size.width, size.height + bleed),
                    )
                }
            }
            // Tapping the pinned rail is a jump back to the top, which is the one affordance a long
            // timeline is missing. It only exists once the rail does, so it cannot be hit by accident
            // while the page is at rest.
            .clickable(enabled = barrier > 0.6f) {
                scrollState?.let { state -> scope.launch { state.animateScrollTo(0) } }
            }
            .padding(bottom = 10.dp * barrier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                SwitcherIconBadge(icon = icon, progress = collapse, sweep = sweep)
                Spacer(Modifier.width(13.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = 1f - 0.30f * collapse },
            ) {
                Text(
                    kicker.uppercase(),
                    color = scheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.4.sp,
                )
                Text(
                    title,
                    // Legibility on the rail wins over the aurora tint once the panel is behind it.
                    color = lerp(titleColor, scheme.onSurface, barrier),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer {
                        val scale = 1f - 0.09f * collapse
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    },
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = scheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = (1f - collapse * 1.6f).coerceIn(0f, 1f) },
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            HubIconActionButton(icon = Icons.Rounded.Settings, label = "Settings", onClick = onOpenSettings)
        }

        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(50))
                .background(accents.hairline.copy(alpha = 0.5f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(filament.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            listOf(scheme.primary, accents.auroraMid, accents.auroraStart),
                        ),
                    ),
            )
        }
    }
}

/** Header for the pushed settings page: back affordance in place of the settings button. */
@Composable
fun SettingsHeader(scrollState: ScrollState? = null, onBack: () -> Unit) {
    val accents = MaterialTheme.hubAccents
    val scheme = MaterialTheme.colorScheme
    val collapse = scrollState?.let { (it.value / 260f).coerceIn(0f, 1f) } ?: 0f

    val motion = rememberInfiniteTransition(label = "settings_header")
    val glow by motion.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(5_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "settings_glow",
    )
    val sweep by motion.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(4_800, easing = LinearEasing), RepeatMode.Restart),
        label = "settings_sweep",
    )

    // Same pinned rail as the page headers, so the settings page does not behave differently: the
    // back affordance stays reachable however far down the diagnostics you are.
    val pin = (scrollState?.value?.toFloat() ?: 0f).coerceAtLeast(0f)
    // Measured in dp, not pixels: a raw pixel threshold would make the rail take three times as
    // long to appear on a low-density screen as it does on a high-density one.
    val barrierAt = with(LocalDensity.current) { 40.dp.toPx() }
    val barrier = (pin / barrierAt).coerceIn(0f, 1f)
    val railColor = scheme.surfaceContainer.copy(alpha = 0.92f * barrier)
    val railProgress = scrollState?.let { state ->
        if (state.maxValue > 0) (state.value.toFloat() / state.maxValue).coerceIn(0f, 1f) else 0f
    } ?: 0f

    Column(
        Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .graphicsLayer { translationY = pin }
            .drawBehind {
                if (barrier > 0.01f) {
                    val bleed = 64.dp.toPx()
                    drawRect(
                        color = railColor,
                        topLeft = Offset(0f, -bleed),
                        size = Size(size.width, size.height + bleed),
                    )
                }
            }
            .padding(bottom = 10.dp * barrier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HubIconActionButton(icon = Icons.Rounded.ArrowBack, label = "Back", onClick = onBack)
            Spacer(Modifier.width(13.dp))
            Column(
                Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = 1f - 0.30f * collapse },
            ) {
                Text(
                    "5G RECONNECT",
                    color = scheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.4.sp,
                )
                Text(
                    "Settings",
                    color = lerp(scheme.onSurface, accents.auroraMid, 0.10f + 0.22f * glow),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "Recovery behaviour, privileged access, SIM choice and diagnostics.",
                    color = scheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = (1f - collapse * 1.6f).coerceIn(0f, 1f) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(50))
                .background(accents.hairline.copy(alpha = 0.5f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(
                        if (barrier > 0.02f) {
                            railProgress.coerceAtLeast(0.06f * barrier)
                        } else if (collapse > 0.02f) {
                            collapse
                        } else {
                            0.16f + 0.14f * glow
                        },
                    )
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(scheme.primary, accents.auroraMid, accents.auroraStart))),
            )
        }
    }
}

/**
 * Square badge behind a page's mark.
 *
 * Fully opaque on purpose: an accent wash over the animated backdrop is what made the previous chip
 * shimmer through its own icon. The sweeping highlight is drawn *inside* the opaque fill, so it
 * brightens the badge without ever letting the backdrop show through.
 */
@Composable
private fun SwitcherIconBadge(icon: ImageVector, progress: Float, sweep: Float) {
    val accents = MaterialTheme.hubAccents
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(17.dp - 4.dp * progress)
    // Named `badgeSize` rather than `size`, because inside the Canvas below `size` must resolve to
    // DrawScope.size and a local of the same name would shadow it.
    val badgeSize = 48.dp - 9.dp * progress

    Box(
        Modifier
            .size(badgeSize)
            .shadow(12.dp, shape, clip = false, ambientColor = accents.shadow, spotColor = accents.shadow)
            .clip(shape)
            .background(scheme.surfaceContainerHighest)
            .border(1.5.dp, scheme.primary.copy(alpha = 0.55f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // A soft highlight that travels across the fill on its own loop.
            val travel = sweep * 2f - 0.5f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        accents.auroraMid.copy(alpha = 0.30f),
                        Color.Transparent,
                    ),
                    start = Offset(size.width * (travel - 0.4f), 0f),
                    end = Offset(size.width * (travel + 0.4f), size.height),
                ),
            )
        }
        Icon(
            icon,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(22.dp - 4.dp * progress),
        )
    }
}

/** Square icon button used for settings and back, matching the glass card vocabulary. */
@Composable
fun HubIconActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accents = MaterialTheme.hubAccents
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .size(44.dp)
            .shadow(10.dp, shape, clip = false, ambientColor = accents.shadow, spotColor = accents.shadow)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, accents.hairline.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}
