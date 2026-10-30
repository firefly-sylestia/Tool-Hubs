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
import androidx.compose.ui.geometry.Offset
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
 * Quiet, stable bottom navigation: no oversized selected tile, no animated width changes,
 * just a clear selected state and equal-sized destinations.
 */
@Composable
fun SwitcherBottomNav(
    items: List<HubNavItem>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant.copy(alpha = 0.55f)))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val tint by animateColorAsState(
                    if (selected) scheme.primary else scheme.onSurfaceVariant,
                    tween(160),
                    label = "nav_tint",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .height(54.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .width(26.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) scheme.primary else Color.Transparent),
                    )
                    Spacer(Modifier.height(4.dp))
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(21.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.label,
                        color = tint,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
 * Plain, content-first page header. It scrolls with the page rather than becoming a floating pill.
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
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    kicker.uppercase(),
                    color = scheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    title,
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(scheme.surfaceContainerHigh)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    tint = scheme.onSurface,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(
                subtitle,
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outlineVariant.copy(alpha = 0.6f)),
        )
    }
}

/** Compact settings header with a conventional back affordance. */
@Composable
fun SettingsHeader(scrollState: ScrollState? = null, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.surfaceContainerHigh)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = scheme.onSurface,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "5G RECONNECT",
                    color = scheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Settings",
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outlineVariant.copy(alpha = 0.6f)),
        )
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
