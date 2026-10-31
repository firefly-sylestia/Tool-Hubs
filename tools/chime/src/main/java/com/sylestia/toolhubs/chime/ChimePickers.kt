@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sylestia.toolhubs.chime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.ui.HubPrimaryButton
import com.sylestia.toolhubs.ui.HubSectionTitle
import com.sylestia.toolhubs.ui.HubSegmented
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch

/*
 * Chime's own pickers.
 *
 * The platform's TimePickerDialog is a Material 2 dialog that ignores the app's theme and floats a
 * clock face over the aurora backdrop, which is why it read as a different product. These are built
 * from the shared Hub parts instead: wheels that snap and dim with distance from the centre line,
 * a calendar that follows the locale's own first day of the week, and the same glass, pill and
 * button vocabulary as every other screen.
 *
 * Both are bottom sheets rather than dialogs: a wheel wants vertical room, and a sheet grows with
 * its content instead of scrolling a small dialog body.
 */

/** One snapping wheel of values, styled like an instrument drum. */
@Composable
private fun Wheel(
    values: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    visibleRows: Int = 5,
    rowHeight: Dp = 46.dp,
    onSelected: (Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val half = visibleRows / 2
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (selectedIndex - half).coerceAtLeast(0),
    )
    val fling = rememberSnapFlingBehavior(lazyListState = listState)
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }

    // With padding of exactly half a viewport, the row sitting under the centre line is the first
    // visible one; once more than half a row has scrolled past, it is the next row instead.
    val centred by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex +
                if (listState.firstVisibleItemScrollOffset > rowPx / 2f) 1 else 0
        }
    }
    val fractional by remember {
        derivedStateOf { listState.firstVisibleItemIndex + listState.firstVisibleItemScrollOffset / rowPx }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val index = centred.coerceIn(0, values.lastIndex)
                if (index != selectedIndex) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelected(index)
                }
            }
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = rowHeight * half),
        ) {
            items(values.size) { index ->
                val distance = abs(index - fractional)
                val active = distance < 0.5f
                Text(
                    values[index],
                    color = if (active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = if (active) 27.sp else 21.sp,
                    fontWeight = if (active) FontWeight.Black else FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .graphicsLayer {
                            alpha = (1f - distance * 0.33f).coerceIn(0.2f, 1f)
                            val scale = (1f - distance * 0.11f).coerceAtLeast(0.72f)
                            scaleX = scale
                            scaleY = scale
                        }
                        .clickable {
                            scope.launch { listState.animateScrollToItem((index - half).coerceAtLeast(0)) }
                        },
                )
            }
        }
        // Fades so rows leave the drum instead of being clipped by its edge.
        Box(
            Modifier
                .fillMaxWidth()
                .height(rowHeight * 1.4f)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceContainer, Color.Transparent))),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(rowHeight * 1.4f)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer))),
        )
    }
}

/**
 * Hour and minute wheels, with an AM/PM pair when the clock is running in 12-hour style.
 *
 * The 12-hour wheels are labelled 1–12 rather than 0–23, because a wheel that says "17" is a
 * 24-hour control no matter what the clock face does with it afterwards.
 */
@Composable
fun ChimeTimeSheet(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    hour24: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    var hour by remember { mutableIntStateOf(initialHour.coerceIn(0, 23)) }
    var minute by remember { mutableIntStateOf(initialMinute.coerceIn(0, 59)) }

    val hours = remember(hour24) {
        if (hour24) (0..23).map { "%02d".format(it) } else (1..12).map { it.toString() }
    }
    val minutes = remember { (0..59).map { "%02d".format(it) } }
    val clockHour = if (hour % 12 == 0) 12 else hour % 12
    val hourIndex = if (hour24) hour else clockHour - 1
    val minuteIndex = minute

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 22.dp),
        ) {
            HubSectionTitle(title)
            Spacer(Modifier.height(6.dp))
            Text(
                previewClock(hour, minute, hour24),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                if (hour24) "24-hour style" else "12-hour style",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    HubSectionTitle("Hour")
                    Wheel(
                        values = hours,
                        selectedIndex = hourIndex,
                        modifier = Modifier.fillMaxWidth().height(230.dp),
                        onSelected = { index ->
                            hour = if (hour24) index else (index + 1) % 12 + if (hour >= 12) 12 else 0
                        },
                    )
                }
                Column(Modifier.weight(1f)) {
                    HubSectionTitle("Minute")
                    Wheel(
                        values = minutes,
                        selectedIndex = minuteIndex,
                        modifier = Modifier.fillMaxWidth().height(230.dp),
                        onSelected = { index -> minute = index },
                    )
                }
            }
            if (!hour24) {
                Spacer(Modifier.height(14.dp))
                HubSegmented(
                    options = listOf("AM", "PM"),
                    selectedIndex = if (hour < 12) 0 else 1,
                    onSelect = { index ->
                        hour = if (index == 0) hour % 12 else hour % 12 + 12
                    },
                )
            }
            Spacer(Modifier.height(18.dp))
            HubPrimaryButton(text = "Set the time", onClick = { onConfirm(hour, minute) })
        }
    }
}

/** 0:00 on an arbitrary fixed date: the wheels are showing a clock face, not a moment in time. */
private fun previewClock(hour: Int, minute: Int, hour24: Boolean): String = ChimeFormat.clock(
    LocalDateTime.of(2000, 1, 1, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    hour24,
)

/** Month calendar for picking a reminder's date. */
@Composable
fun ChimeDateSheet(
    title: String,
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    var picked by remember { mutableStateOf(initial) }
    val today = remember { LocalDate.now() }
    val locale = Locale.getDefault()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 22.dp),
        ) {
            HubSectionTitle(title)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Rounded.ChevronLeft) { month = month.minusMonths(1) }
                Text(
                    month.month.getDisplayName(TextStyle.FULL, locale) + " " + month.year,
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                )
                RoundIcon(Icons.Rounded.ChevronRight) { month = month.plusMonths(1) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                weekdayLabels(locale).forEach { label ->
                    Text(
                        label,
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            MonthGrid(month = month, picked = picked, today = today) { day -> picked = day }
            Spacer(Modifier.height(18.dp))
            HubPrimaryButton(text = "Use this date", onClick = { onConfirm(picked) })
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

/** Weekday initials in the locale's own week order, which is what a calendar has to follow. */
private fun weekdayLabels(locale: Locale): List<String> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return (0..6).map { offset ->
        first.plus(offset.toLong()).getDisplayName(TextStyle.NARROW, locale).uppercase(locale)
    }
}

@Composable
private fun MonthGrid(month: YearMonth, picked: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    val locale = Locale.getDefault()
    val first = WeekFields.of(locale).firstDayOfWeek
    // `DayOfWeek.value` is Monday-based, so shift it onto whichever day this locale starts with.
    val leading = (month.atDay(1).dayOfWeek.value - first.value + 7) % 7
    val weeks = (leading + month.lengthOfMonth() + 6) / 7

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(weeks) { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(7) { slot ->
                    val dayNumber = week * 7 + slot - leading + 1
                    if (dayNumber in 1..month.lengthOfMonth()) {
                        val date = month.atDay(dayNumber)
                        DayCell(
                            day = dayNumber,
                            selected = date == picked,
                            isToday = date == today,
                            modifier = Modifier.weight(1f),
                            onClick = { onPick(date) },
                        )
                    } else {
                        Spacer(Modifier.weight(1f).height(42.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, selected: Boolean, isToday: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primary
                    isToday -> MaterialTheme.colorScheme.surfaceContainerHighest
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.toString(),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected || isToday) FontWeight.Black else FontWeight.Medium,
            fontSize = 14.sp,
        )
    }
}
