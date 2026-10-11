package com.sylestia.toolhubs.networkswitcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.hubAccents
import com.sylestia.toolhubs.ui.HubHeroHeader
import com.sylestia.toolhubs.ui.HubNavItem

/*
 * Network Switcher's own navigation chrome.
 *
 * This deliberately lives in the module rather than in `:core:ui`: the shared HubBottomNav is used
 * by the calculator, Data Warden and the Music tools, which are frozen and must not be restyled.
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
 * The page header plus the settings affordance.
 *
 * Settings is no longer a navigation destination: it is a button in the header that pushes a
 * dedicated page, so the tab bar only carries the screens you actually move between.
 */
@Composable
fun SwitcherHeader(
    kicker: String,
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onOpenSettings: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            HubHeroHeader(kicker = kicker, title = title, subtitle = subtitle, icon = icon)
        }
        Spacer(Modifier.width(10.dp))
        HubIconActionButton(icon = Icons.Rounded.Settings, label = "Settings", onClick = onOpenSettings)
    }
}

/** Header for the pushed settings page: back affordance in place of the settings button. */
@Composable
fun SettingsHeader(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HubIconActionButton(icon = Icons.Rounded.ArrowBack, label = "Back", onClick = onBack)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "5G RECONNECT",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.4.sp,
            )
            Text(
                "Settings",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                "Recovery behaviour, privileged access, SIM choice and diagnostics.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
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
