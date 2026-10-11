@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sylestia.toolhubs.chime

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.chime.bell.BellVoice
import com.sylestia.toolhubs.chime.bell.TimeVoice
import com.sylestia.toolhubs.chime.ring.ScanDismissActivity
import com.sylestia.toolhubs.chime.schedule.ChimeScheduler
import com.sylestia.toolhubs.designsystem.ToolHubsTheme
import com.sylestia.toolhubs.ui.HubBackdrop
import com.sylestia.toolhubs.ui.HubBottomNav
import com.sylestia.toolhubs.ui.HubEmptyState
import com.sylestia.toolhubs.ui.HubGlassCard
import com.sylestia.toolhubs.ui.HubHeroHeader
import com.sylestia.toolhubs.ui.HubIconChip
import com.sylestia.toolhubs.ui.HubNavItem
import com.sylestia.toolhubs.ui.HubPill
import com.sylestia.toolhubs.ui.HubPrimaryButton
import com.sylestia.toolhubs.ui.HubQuietButton
import com.sylestia.toolhubs.ui.HubReveal
import com.sylestia.toolhubs.ui.HubSectionTitle
import com.sylestia.toolhubs.ui.HubSegmented
import com.sylestia.toolhubs.ui.HubStatTile
import com.sylestia.toolhubs.ui.HubToggleRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Chime's home screen.
 *
 * Everything here is built from `:core:ui` and painted by `:core:designsystem`, because the first
 * version used raw Material 3 widgets and looked like a different product standing next to Network
 * Switcher: grey system bars, letter-instead-of-icon tabs and platform dialogs. The rules this file
 * follows, in order of how often they were broken:
 *
 *  - one theme, no bare `MaterialTheme {}` root;
 *  - every screen is a header plus a scrolling column of glass cards on the same 18dp gutter;
 *  - a row that holds a label and a control gives the control a fixed width and the label the
 *    remaining space, so nothing jitters as the value changes;
 *  - 12- or 24-hour style is threaded through every string the app renders, including the bell and
 *    the spoken time.
 */

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChimeNotifications.ensureChannels(this)
        setContent {
            ToolHubsTheme {
                ChimeApp()
            }
        }
    }
}

private val ChimeTabs = listOf(
    HubNavItem(Icons.Rounded.AccessTime, "Clock"),
    HubNavItem(Icons.Rounded.Alarm, "Alarms"),
    HubNavItem(Icons.Rounded.NotificationsActive, "Reminders"),
    HubNavItem(Icons.Rounded.Tune, "Settings"),
)

@Composable
private fun ChimeApp() {
    val context = LocalContext.current
    val store = remember { ChimeStore(context) }
    val scheduler = remember { ChimeScheduler(context) }

    var tab by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var alarms by remember { mutableStateOf(store.alarms()) }
    var reminders by remember { mutableStateOf(store.reminders()) }
    var settings by remember { mutableStateOf(store.settings()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { revision++ }

    // Re-reads the store whenever something changed, and every second for the clock face and the
    // permission rows, which can be changed outside the app while this screen is on top of them.
    LaunchedEffect(revision) {
        alarms = store.alarms()
        reminders = store.reminders()
        settings = store.settings()
        scheduler.refreshNextUp()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !ChimeNotifications.canPost(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    val hour24 = ChimeFormat.use24Hour(context, settings.use24Hour)
    val refresh = { revision++ }
    val saveSettings: (ChimeSettings) -> Unit = { changed ->
        store.saveSettings(changed)
        scheduler.scheduleNextBell()
        refresh()
    }

    HubBackdrop {
        androidx.compose.material3.Scaffold(
            containerColor = Color.Transparent,
            // Each page adds its own insets, so the scaffold must not add them a second time.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                HubBottomNav(items = ChimeTabs, selectedIndex = tab, onSelect = { tab = it })
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> ClockPage(
                        context = context,
                        settings = settings,
                        now = now,
                        hour24 = hour24,
                        nextUp = scheduler.nextUp(now),
                        onSettings = saveSettings,
                        onSpeak = { millis -> TimeVoice.preview(context, millis, hour24) },
                    )

                    1 -> AlarmsPage(
                        alarms = alarms,
                        settings = settings,
                        hour24 = hour24,
                        store = store,
                        scheduler = scheduler,
                        onChanged = refresh,
                    )

                    2 -> RemindersPage(
                        reminders = reminders,
                        settings = settings,
                        hour24 = hour24,
                        store = store,
                        scheduler = scheduler,
                        onChanged = refresh,
                    )

                    else -> SettingsPage(
                        context = context,
                        settings = settings,
                        hour24 = hour24,
                        store = store,
                        scheduler = scheduler,
                        onSettings = saveSettings,
                        onChanged = refresh,
                    )
                }
            }
        }
    }
}

// ── page 1: the clock ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ClockPage(
    context: Context,
    settings: ChimeSettings,
    now: Long,
    hour24: Boolean,
    nextUp: DueItem?,
    onSettings: (ChimeSettings) -> Unit,
    onSpeak: suspend (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    var bellSheet by remember { mutableStateOf(false) }

    val zoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
    val quiet = settings.isQuietHour(zoned.hour)

    PageColumn {
        HubHeroHeader(
            kicker = "Chime",
            title = "Clock",
            subtitle = ChimeFormat.longDate(now) + " · " + (
                if (quiet) "quiet until ${clockLabel(settings.quietToHour, 0, hour24)}"
                else "the bell strikes ${ChimeTimes.strikesFor(zoned.hour)} times on the hour"
                ),
            icon = Icons.Rounded.AccessTime,
        )

        HubReveal(0) {
            HubGlassCard(accent = MaterialTheme.colorScheme.tertiary) {
                Text(
                    ChimeFormat.clockWithSeconds(now, hour24),
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    ChimeFormat.longDate(now),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubStatTile(
                        label = "Next hour",
                        value = clockLabel((zoned.hour + 1) % 24, 0, hour24),
                        accent = MaterialTheme.colorScheme.primary,
                    )
                    HubStatTile(
                        label = "Strikes",
                        value = if (quiet) "Silent" else ChimeTimes.strikesFor(zoned.hour).toString(),
                        accent = if (quiet) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.tertiary,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubQuietButton(
                        text = "Speak the time",
                        icon = Icons.Rounded.RecordVoiceOver,
                        modifier = Modifier.weight(1f),
                        onClick = { scope.launch { onSpeak(now) } },
                    )
                    HubQuietButton(
                        text = "Test the bell",
                        icon = Icons.Rounded.MusicNote,
                        modifier = Modifier.weight(1f),
                        onClick = { scope.launch { BellVoice.strike(context, 3) } },
                    )
                }
            }
        }

        HubReveal(1) {
            HubGlassCard {
                CardHead(Icons.Rounded.Schedule, "Next up")
                if (nextUp == null) {
                    Text(
                        "Nothing is scheduled yet. Add an alarm or a reminder and it will appear here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text(
                        nextUp.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "${ChimeFormat.clock(nextUp.triggerAt, hour24)} · " +
                            "${ChimeTimes.untilLabel(nextUp.triggerAt, now)} · ${nextUp.subtitle}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        HubReveal(2) {
            HubGlassCard {
                CardHead(Icons.Rounded.MusicNote, "The clock itself")
                Text(
                    "The bell and the spoken time are separate switches: strike only, speak only, or " +
                        "both. The schedule below decides when either happens.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(12.dp))
                HubSegmented(
                    options = listOf("Off", "Every hour", "Set times"),
                    selectedIndex = settings.bellMode.ordinal,
                    onSelect = { index ->
                        val mode = BellMode.entries[index]
                        onSettings(
                            settings.copy(
                                bellMode = mode,
                                // "Set times" with nothing chosen would be silently dead, so the
                                // first time it is opened it offers a sensible hour to remove.
                                bellTimes = if (mode == BellMode.SET_TIMES && settings.bellTimes.isEmpty()) {
                                    listOf(8 * 60)
                                } else {
                                    settings.bellTimes
                                },
                            )
                        )
                    },
                )
                Spacer(Modifier.height(6.dp))
                HubToggleRow(
                    title = "Strike the bell",
                    subtitle = "A pendulum strike for each hour, or a single chime at a set time.",
                    checked = settings.bellEnabled,
                    onCheckedChange = { onSettings(settings.copy(bellEnabled = it)) },
                )
                HubToggleRow(
                    title = "Speak the time",
                    subtitle = "Says the time out loud, in words rather than digits.",
                    checked = settings.speakTime,
                    onCheckedChange = { onSettings(settings.copy(speakTime = it)) },
                )
                if (!settings.bellEnabled && !settings.speakTime) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Both are off, so the clock is silent whatever the schedule says.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
                if (settings.bellMode == BellMode.SET_TIMES) {
                    Spacer(Modifier.height(12.dp))
                    HubSectionTitle("Rings at")
                    Spacer(Modifier.height(8.dp))
                    if (settings.bellTimes.isEmpty()) {
                        Text(
                            "No times yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            settings.bellTimes.sorted().forEach { minuteOfDay ->
                                BellTimeRow(
                                    label = clockLabel(minuteOfDay / 60, minuteOfDay % 60, hour24),
                                    onRemove = {
                                        onSettings(settings.copy(bellTimes = settings.bellTimes - minuteOfDay))
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HubQuietButton(
                            text = "Add a time",
                            icon = Icons.Rounded.Add,
                            modifier = Modifier.weight(1f),
                            onClick = { bellSheet = true },
                        )
                        if (settings.bellTimes.isNotEmpty()) {
                            HubQuietButton(
                                text = "Clear all",
                                icon = Icons.Rounded.Delete,
                                modifier = Modifier.weight(1f),
                                onClick = { onSettings(settings.copy(bellTimes = emptyList())) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (bellSheet) {
        ChimeTimeSheet(
            title = "Add a ring time",
            initialHour = 8,
            initialMinute = 0,
            hour24 = hour24,
            onDismiss = { bellSheet = false },
            onConfirm = { hour, minute ->
                val entry = hour * 60 + minute
                onSettings(
                    settings.copy(
                        bellTimes = (settings.bellTimes + entry).distinct().sorted(),
                        bellMode = if (settings.bellMode == BellMode.OFF) BellMode.SET_TIMES else settings.bellMode,
                    )
                )
                bellSheet = false
            },
        )
    }
}

@Composable
private fun BellTimeRow(label: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
        Text(
            "Remove",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onRemove).padding(4.dp),
        )
    }
}

// ── page 2: alarms ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun AlarmsPage(
    alarms: List<AlarmItem>,
    settings: ChimeSettings,
    hour24: Boolean,
    store: ChimeStore,
    scheduler: ChimeScheduler,
    onChanged: () -> Unit,
) {
    var editing by remember { mutableStateOf<AlarmItem?>(null) }
    var creating by remember { mutableStateOf(false) }

    PageColumn {
        HubHeroHeader(
            kicker = "Chime",
            title = "Alarms",
            subtitle = when (alarms.count { it.enabled }) {
                0 -> "Nothing armed. Add one and it wakes the screen itself."
                1 -> "One alarm armed."
                else -> "${alarms.count { it.enabled }} alarms armed."
            },
            icon = Icons.Rounded.Alarm,
        )

        if (alarms.isEmpty()) {
            HubReveal(0) {
                HubGlassCard {
                    HubEmptyState(
                        title = "No alarms yet",
                        message = "An alarm rings full screen and can be answered with a button, a " +
                            "shake or a scannable code.",
                        icon = Icons.Rounded.Alarm,
                    )
                }
            }
        }

        alarms.forEachIndexed { index, alarm ->
            HubReveal(index + 1) {
                HubGlassCard(
                    accent = if (alarm.enabled) MaterialTheme.colorScheme.tertiary else null,
                    contentPadding = 16.dp,
                    onClick = { editing = alarm },
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                alarm.timeLabel(hour24),
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                color = if (alarm.enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if (alarm.label.isNotBlank()) {
                                Text(
                                    alarm.label,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                HubPill(alarm.dayLabel(), active = alarm.enabled)
                                HubPill(dismissLabel(alarm.dismissMode, alarm.scanCode), active = false)
                                HubPill("${alarm.snoozeMinutes}m snooze", active = false)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = alarm.enabled,
                            onCheckedChange = { on ->
                                store.setAlarmEnabled(alarm.id, on)
                                if (on) scheduler.scheduleAlarm(alarm.copy(enabled = true))
                                else scheduler.cancel(DueItem.Kind.ALARM, alarm.id)
                                onChanged()
                            },
                            colors = hubSwitchColors(),
                        )
                    }
                    val next = scheduler.nextAlarmTrigger(alarm)
                    if (alarm.enabled && next != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Next ${ChimeFormat.longDate(next)} at ${ChimeFormat.clock(next, hour24)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }

        HubReveal(alarms.size + 1) {
            HubPrimaryButton(
                text = "Add an alarm",
                icon = Icons.Rounded.Add,
                onClick = { creating = true },
            )
        }
    }

    if (creating || editing != null) {
        AlarmEditor(
            initial = editing,
            settings = settings,
            hour24 = hour24,
            onDismiss = { creating = false; editing = null },
            onSave = { item ->
                store.saveAlarm(item)
                scheduler.scheduleAlarm(item)
                creating = false
                editing = null
                onChanged()
            },
            onDelete = { item ->
                store.deleteAlarm(item.id)
                scheduler.cancel(DueItem.Kind.ALARM, item.id)
                editing = null
                onChanged()
            },
        )
    }
}

@Composable
private fun AlarmEditor(
    initial: AlarmItem?,
    settings: ChimeSettings,
    hour24: Boolean,
    onDismiss: () -> Unit,
    onSave: (AlarmItem) -> Unit,
    onDelete: (AlarmItem) -> Unit,
) {
    var hour by remember { mutableIntStateOf(initial?.hour ?: 7) }
    var minute by remember { mutableIntStateOf(initial?.minute ?: 0) }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var days by remember { mutableStateOf(initial?.days ?: emptySet<Int>()) }
    var mode by remember { mutableIntStateOf((initial?.dismissMode ?: settings.defaultDismissMode).ordinal) }
    var scanCode by remember { mutableStateOf(initial?.scanCode) }
    var vibrate by remember { mutableStateOf(initial?.vibrate ?: true) }
    var snooze by remember { mutableIntStateOf(initial?.snoozeMinutes ?: settings.defaultSnoozeMinutes) }
    var timeSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val needsScan = DismissMode.entries[mode] == DismissMode.SCAN ||
        DismissMode.entries[mode] == DismissMode.SHAKE_OR_SCAN

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val scanned = result.data?.getStringExtra(ScanDismissActivity.EXTRA_SCANNED)
        if (result.resultCode == android.app.Activity.RESULT_OK && !scanned.isNullOrBlank()) {
            scanCode = scanned
        }
    }

    EditorSheet(
        title = if (initial == null) "New alarm" else "Edit alarm",
        onDismiss = onDismiss,
    ) {
        TimeRow(
            label = "Time",
            value = clockLabel(hour, minute, hour24),
            onClick = { timeSheet = true },
        )
        Spacer(Modifier.height(12.dp))
        HubField(value = label, onValueChange = { label = it }, label = "Label (optional)")
        Spacer(Modifier.height(16.dp))
        HubSectionTitle("Repeat")
        Spacer(Modifier.height(8.dp))
        DayStrip(days = days, onToggle = { value -> days = if (value in days) days - value else days + value })
        Spacer(Modifier.height(8.dp))
        Text(
            alarmRepeatSummary(days),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(16.dp))
        HubSectionTitle("Dismiss with")
        Spacer(Modifier.height(8.dp))
        HubSegmented(
            options = listOf("Button", "Shake", "Scan", "Either"),
            selectedIndex = mode,
            onSelect = { mode = it },
        )
        if (needsScan) {
            Spacer(Modifier.height(12.dp))
            ScanCodeCard(
                code = scanCode,
                onScan = { scanLauncher.launch(ScanDismissActivity.registerIntent(context)) },
                onClear = { scanCode = null },
            )
        }
        Spacer(Modifier.height(16.dp))
        HubToggleRow(
            title = "Vibrate",
            subtitle = "Pulses in step with the ringing, on top of the sound.",
            checked = vibrate,
            onCheckedChange = { vibrate = it },
        )
        StepperRow(
            title = "Snooze length",
            value = "$snooze min",
            onMinus = { snooze = (snooze - 1).coerceAtLeast(1) },
            onPlus = { snooze = (snooze + 1).coerceAtMost(60) },
        )
        Spacer(Modifier.height(18.dp))
        HubPrimaryButton(
            text = if (initial == null) "Add the alarm" else "Save changes",
            icon = Icons.Rounded.Alarm,
            onClick = {
                onSave(
                    AlarmItem(
                        id = initial?.id ?: System.currentTimeMillis(),
                        hour = hour,
                        minute = minute,
                        label = label.trim(),
                        days = days,
                        enabled = true,
                        dismissMode = DismissMode.entries[mode],
                        scanCode = if (needsScan) scanCode else null,
                        snoozeMinutes = snooze,
                        vibrate = vibrate,
                    )
                )
            },
        )
        if (initial != null) {
            Spacer(Modifier.height(10.dp))
            HubQuietButton(
                text = "Delete this alarm",
                icon = Icons.Rounded.Delete,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDelete(initial) },
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (timeSheet) {
        ChimeTimeSheet(
            title = "Alarm time",
            initialHour = hour,
            initialMinute = minute,
            hour24 = hour24,
            onDismiss = { timeSheet = false },
            onConfirm = { h, m -> hour = h; minute = m; timeSheet = false },
        )
    }
}

/**
 * The code that has to be scanned before the alarm gives up.
 *
 * It is captured by pointing the camera at the sticker, not by typing its text on a phone held in
 * the same hand as the alarm: registering the code from memory is what produced codes nobody could
 * reproduce at 06:30, and the scanned value is what the alarm compares against later.
 */
@Composable
private fun ScanCodeCard(code: String?, onScan: () -> Unit, onClear: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.QrCodeScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (code == null) "Any code will do" else "Locked to one code",
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
        Text(
            if (code == null) {
                "Point the camera at a barcode or QR code and it becomes the one this alarm accepts."
            } else {
                "Read: $code"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HubQuietButton(
                text = if (code == null) "Scan a code" else "Scan again",
                icon = Icons.Rounded.CameraAlt,
                modifier = Modifier.weight(1f),
                onClick = onScan,
            )
            if (code != null) {
                HubQuietButton(
                    text = "Accept any",
                    modifier = Modifier.weight(1f),
                    onClick = onClear,
                )
            }
        }
    }
}

// ── page 3: reminders ────────────────────────────────────────────────────────────────────────────

@Composable
private fun RemindersPage(
    reminders: List<ReminderItem>,
    settings: ChimeSettings,
    hour24: Boolean,
    store: ChimeStore,
    scheduler: ChimeScheduler,
    onChanged: () -> Unit,
) {
    var editing by remember { mutableStateOf<ReminderItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    val context = LocalContext.current

    PageColumn {
        HubHeroHeader(
            kicker = "Chime",
            title = "Reminders",
            subtitle = if (canUseFullScreen(context)) {
                "A reminder takes over the screen exactly like an alarm, whenever it arrives."
            } else {
                "Full-screen alerts are switched off, so a reminder arrives as a banner for now."
            },
            icon = Icons.Rounded.NotificationsActive,
        )

        if (!canUseFullScreen(context)) {
            HubReveal(0) {
                PermissionCard(
                    icon = Icons.Rounded.Fullscreen,
                    title = "Reminders can't take the screen yet",
                    message = "Android is holding back full-screen alerts for Chime, so a reminder " +
                        "shows up as a banner. Allowing it makes a reminder behave like an alarm.",
                    actionLabel = "Allow full-screen alerts",
                    onAction = { openFullScreenSettings(context) },
                )
            }
        }

        if (reminders.isEmpty()) {
            HubReveal(1) {
                HubGlassCard {
                    HubEmptyState(
                        title = "No reminders yet",
                        message = "A reminder is a one-off or repeating nudge that needs an answer, " +
                            "not a nap.",
                        icon = Icons.Rounded.NotificationsActive,
                    )
                }
            }
        }

        reminders.forEachIndexed { index, reminder ->
            HubReveal(index + 2) {
                HubGlassCard(
                    accent = if (reminder.enabled) MaterialTheme.colorScheme.primary else null,
                    contentPadding = 16.dp,
                    onClick = { editing = reminder },
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                reminder.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${ChimeFormat.longDate(reminder.atMillis)} · " +
                                    ChimeFormat.clock(reminder.atMillis, hour24),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                HubPill(reminder.repeatLabel(), active = reminder.enabled)
                                HubPill(dismissLabel(reminder.dismissMode, reminder.scanCode), active = false)
                            }
                            if (reminder.note.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    reminder.note,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = reminder.enabled,
                            onCheckedChange = { on ->
                                store.setReminderEnabled(reminder.id, on)
                                if (on) scheduler.scheduleReminder(reminder.copy(enabled = true))
                                else scheduler.cancel(DueItem.Kind.REMINDER, reminder.id)
                                onChanged()
                            },
                            colors = hubSwitchColors(),
                        )
                    }
                }
            }
        }

        HubReveal(reminders.size + 2) {
            HubPrimaryButton(
                text = "Add a reminder",
                icon = Icons.Rounded.Add,
                onClick = { creating = true },
            )
        }
    }

    if (creating || editing != null) {
        ReminderEditor(
            initial = editing,
            settings = settings,
            hour24 = hour24,
            onDismiss = { creating = false; editing = null },
            onSave = { item ->
                store.saveReminder(item)
                scheduler.scheduleReminder(item)
                creating = false
                editing = null
                onChanged()
            },
            onDelete = { item ->
                store.deleteReminder(item.id)
                scheduler.cancel(DueItem.Kind.REMINDER, item.id)
                editing = null
                onChanged()
            },
        )
    }
}

@Composable
private fun ReminderEditor(
    initial: ReminderItem?,
    settings: ChimeSettings,
    hour24: Boolean,
    onDismiss: () -> Unit,
    onSave: (ReminderItem) -> Unit,
    onDelete: (ReminderItem) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var atMillis by remember {
        mutableLongStateOf(initial?.atMillis ?: (System.currentTimeMillis() + 60 * 60_000L))
    }
    var repeatIndex by remember { mutableIntStateOf((initial?.repeat ?: RepeatRule.ONCE).ordinal) }
    var modeIndex by remember {
        mutableIntStateOf((initial?.dismissMode ?: settings.defaultDismissMode).ordinal)
    }
    var dateSheet by remember { mutableStateOf(false) }
    var timeSheet by remember { mutableStateOf(false) }

    val moment = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
    val needsScan = DismissMode.entries[modeIndex] == DismissMode.SCAN ||
        DismissMode.entries[modeIndex] == DismissMode.SHAKE_OR_SCAN

    EditorSheet(
        title = if (initial == null) "New reminder" else "Edit reminder",
        onDismiss = onDismiss,
    ) {
        HubField(value = title, onValueChange = { title = it }, label = "What is it for?")
        Spacer(Modifier.height(10.dp))
        HubField(value = note, onValueChange = { note = it }, label = "Note (optional)")
        Spacer(Modifier.height(16.dp))
        HubSectionTitle("When")
        Spacer(Modifier.height(8.dp))
        TimeRow(
            label = "Date",
            value = moment.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())),
            onClick = { dateSheet = true },
        )
        Spacer(Modifier.height(8.dp))
        TimeRow(
            label = "Time",
            value = clockLabel(moment.hour, moment.minute, hour24),
            onClick = { timeSheet = true },
        )
        Spacer(Modifier.height(16.dp))
        HubSectionTitle("Repeat")
        Spacer(Modifier.height(8.dp))
        HubSegmented(
            options = listOf("Once", "Daily", "Weekly"),
            selectedIndex = repeatIndex,
            onSelect = { repeatIndex = it },
        )
        Spacer(Modifier.height(16.dp))
        HubSectionTitle("Dismiss with")
        Spacer(Modifier.height(8.dp))
        HubSegmented(
            options = listOf("Button", "Shake", "Scan", "Either"),
            selectedIndex = modeIndex,
            onSelect = { modeIndex = it },
        )
        Spacer(Modifier.height(18.dp))
        HubPrimaryButton(
            text = if (initial == null) "Add the reminder" else "Save changes",
            icon = Icons.Rounded.Check,
            onClick = {
                onSave(
                    ReminderItem(
                        id = initial?.id ?: System.currentTimeMillis(),
                        title = title.trim().ifBlank { "Reminder" },
                        note = note.trim(),
                        atMillis = atMillis,
                        repeat = RepeatRule.entries[repeatIndex],
                        enabled = true,
                        dismissMode = DismissMode.entries[modeIndex],
                        scanCode = if (needsScan) initial?.scanCode else null,
                        snoozeMinutes = initial?.snoozeMinutes ?: settings.defaultSnoozeMinutes,
                    )
                )
            },
        )
        if (initial != null) {
            Spacer(Modifier.height(10.dp))
            HubQuietButton(
                text = "Delete this reminder",
                icon = Icons.Rounded.Delete,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDelete(initial) },
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (dateSheet) {
        ChimeDateSheet(
            title = "Reminder date",
            initial = moment.toLocalDate(),
            onDismiss = { dateSheet = false },
            onConfirm = { date -> atMillis = withDate(atMillis, date, zone); dateSheet = false },
        )
    }
    if (timeSheet) {
        ChimeTimeSheet(
            title = "Reminder time",
            initialHour = moment.hour,
            initialMinute = moment.minute,
            hour24 = hour24,
            onDismiss = { timeSheet = false },
            onConfirm = { hour, minute -> atMillis = withTime(atMillis, hour, minute, zone); timeSheet = false },
        )
    }
}

/** Keeps the date's own zone: converting through UTC here is how a reminder lands a day early. */
private fun withDate(millis: Long, date: LocalDate, zone: ZoneId): Long {
    val current = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
    return date.atTime(current.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
}

private fun withTime(millis: Long, hour: Int, minute: Int, zone: ZoneId): Long =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
        .withHour(hour)
        .withMinute(minute)
        .withSecond(0)
        .withNano(0)
        .toInstant()
        .toEpochMilli()

// ── page 4: settings ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsPage(
    context: Context,
    settings: ChimeSettings,
    hour24: Boolean,
    store: ChimeStore,
    scheduler: ChimeScheduler,
    onSettings: (ChimeSettings) -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val notifications = ChimeNotifications.canPost(context)
    val fullScreen = canUseFullScreen(context)
    val camera = androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val exact = scheduler.canScheduleExact()

    var quietFrom by remember { mutableStateOf(false) }
    var quietTo by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onChanged() }

    PageColumn {
        HubHeroHeader(
            kicker = "Chime",
            title = "Settings",
            subtitle = "Clock style, the bell and the spoken time, and the permissions Chime needs.",
            icon = Icons.Rounded.Tune,
        )

        HubReveal(0) {
            HubGlassCard {
                CardHead(Icons.Rounded.AccessTime, "Clock style")
                Text(
                    "Applies to the clock face, every alarm and reminder, and the spoken time.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(12.dp))
                HubSegmented(
                    options = listOf("System", "12-hour", "24-hour"),
                    selectedIndex = when (settings.use24Hour) {
                        null -> 0
                        false -> 1
                        true -> 2
                    },
                    onSelect = { index ->
                        onSettings(
                            settings.copy(
                                use24Hour = when (index) {
                                    0 -> null
                                    1 -> false
                                    else -> true
                                }
                            )
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Currently showing ${if (hour24) "24-hour" else "12-hour"} times.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }

        HubReveal(1) {
            HubGlassCard {
                CardHead(Icons.Rounded.MusicNote, "Bell and voice")
                HubToggleRow(
                    title = "Strike the bell",
                    subtitle = "A synthesized pendulum strike, not a recording.",
                    checked = settings.bellEnabled,
                    onCheckedChange = { onSettings(settings.copy(bellEnabled = it)) },
                )
                HubToggleRow(
                    title = "Speak the time",
                    subtitle = "Says the time in words, following the clock style above.",
                    checked = settings.speakTime,
                    onCheckedChange = { onSettings(settings.copy(speakTime = it)) },
                )
                Spacer(Modifier.height(12.dp))
                HubSectionTitle("Quiet hours")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Inside this window the bell stays silent and the time is not spoken, so an " +
                        "hourly clock cannot wake anyone up.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                HubToggleRow(
                    title = "Enabled",
                    checked = settings.quietHoursEnabled,
                    onCheckedChange = { onSettings(settings.copy(quietHoursEnabled = it)) },
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubQuietButton(
                        text = "From ${clockLabel(settings.quietFromHour, 0, hour24)}",
                        modifier = Modifier.weight(1f),
                        onClick = { quietFrom = true },
                    )
                    HubQuietButton(
                        text = "To ${clockLabel(settings.quietToHour, 0, hour24)}",
                        modifier = Modifier.weight(1f),
                        onClick = { quietTo = true },
                    )
                }
                Spacer(Modifier.height(14.dp))
                HubQuietButton(
                    text = "Test what is switched on",
                    icon = Icons.Rounded.GraphicEq,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            if (settings.bellEnabled) {
                                BellVoice.strike(context, ChimeTimes.strikesFor(LocalTime.now().hour))
                            }
                            if (settings.speakTime) {
                                TimeVoice.preview(context, System.currentTimeMillis(), hour24)
                            }
                        }
                    },
                )
                if (!settings.bellEnabled && !settings.speakTime) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Both switches are off, so the test is silent.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        HubReveal(2) {
            HubGlassCard {
                CardHead(Icons.Rounded.Bolt, "Ringing")
                HubSliderRow(
                    title = "Volume ramp",
                    valueLabel = "${settings.rampSeconds}s",
                    value = settings.rampSeconds.toFloat(),
                    range = 0f..120f,
                    steps = 23,
                    onValueChange = { onSettings(settings.copy(rampSeconds = it.toInt())) },
                )
                StepperRow(
                    title = "Stop ringing after",
                    value = "${settings.ringTimeoutMinutes} min",
                    onMinus = {
                        onSettings(settings.copy(ringTimeoutMinutes = (settings.ringTimeoutMinutes - 1).coerceAtLeast(1)))
                    },
                    onPlus = {
                        onSettings(settings.copy(ringTimeoutMinutes = (settings.ringTimeoutMinutes + 1).coerceAtMost(60)))
                    },
                )
                StepperRow(
                    title = "Default snooze",
                    value = "${settings.defaultSnoozeMinutes} min",
                    onMinus = {
                        onSettings(settings.copy(defaultSnoozeMinutes = (settings.defaultSnoozeMinutes - 1).coerceAtLeast(1)))
                    },
                    onPlus = {
                        onSettings(settings.copy(defaultSnoozeMinutes = (settings.defaultSnoozeMinutes + 1).coerceAtMost(60)))
                    },
                )
                Spacer(Modifier.height(8.dp))
                HubSectionTitle("New items dismiss with")
                Spacer(Modifier.height(8.dp))
                HubSegmented(
                    options = listOf("Button", "Shake", "Scan", "Either"),
                    selectedIndex = settings.defaultDismissMode.ordinal,
                    onSelect = { index ->
                        onSettings(settings.copy(defaultDismissMode = DismissMode.entries[index]))
                    },
                )
            }
        }

        HubReveal(3) {
            HubGlassCard {
                CardHead(Icons.Rounded.Notifications, "Alerts and permissions")
                PermissionRow(
                    title = "Notifications",
                    detail = if (notifications) {
                        "Allowed. The alarm notification and the countdown can be posted."
                    } else {
                        "Blocked. Without this, an alarm cannot tell you anything at all."
                    },
                    ok = notifications,
                    actionLabel = if (notifications) null else "Allow",
                    onAction = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openAppNotificationSettings(context)
                        }
                    },
                )
                HubToggleRow(
                    title = "Full-screen alarms",
                    subtitle = if (fullScreen) {
                        "Allowed. A ringing alarm takes the screen, even when it is locked."
                    } else {
                        "Off. Android is showing choruses as banners instead of taking the screen."
                    },
                    checked = fullScreen,
                    onCheckedChange = { openFullScreenSettings(context) },
                )
                PermissionRow(
                    title = "Exact alarms",
                    detail = if (exact) {
                        "Allowed. An alarm rings at the minute you set."
                    } else {
                        "Withheld. Alarms still ring, but they can drift by a few minutes."
                    },
                    ok = exact,
                    actionLabel = if (exact) null else "Allow",
                    onAction = { openExactAlarmSettings(context) },
                )
                PermissionRow(
                    title = "Camera",
                    detail = if (camera) {
                        "Allowed. A scan can dismiss an alarm."
                    } else {
                        "Not granted, so scan-to-dismiss and registering a code cannot work yet."
                    },
                    ok = camera,
                    actionLabel = if (camera) null else "Allow",
                    onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                )
            }
        }

        HubReveal(4) {
            HubGlassCard {
                CardHead(Icons.Rounded.Event, "About")
                Text(
                    "Chime ${BuildConfig.VERSION_NAME}: alarms, reminders and a chiming clock, with " +
                        "no account and no network.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    if (quietFrom) {
        ChimeTimeSheet(
            title = "Quiet hours start",
            initialHour = settings.quietFromHour,
            initialMinute = 0,
            hour24 = hour24,
            onDismiss = { quietFrom = false },
            onConfirm = { hour, _ ->
                store.saveSettings(settings.copy(quietFromHour = hour))
                scheduler.scheduleNextBell()
                quietFrom = false
                onChanged()
            },
        )
    }
    if (quietTo) {
        ChimeTimeSheet(
            title = "Quiet hours end",
            initialHour = settings.quietToHour,
            initialMinute = 0,
            hour24 = hour24,
            onDismiss = { quietTo = false },
            onConfirm = { hour, _ ->
                store.saveSettings(settings.copy(quietToHour = hour))
                scheduler.scheduleNextBell()
                quietTo = false
                onChanged()
            },
        )
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    HubGlassCard(accent = MaterialTheme.colorScheme.error) {
        CardHead(icon, title, accent = MaterialTheme.colorScheme.error)
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(12.dp))
        HubPrimaryButton(text = actionLabel, icon = icon, onClick = onAction)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    ok: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                detail,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
            )
        }
        if (actionLabel != null) {
            HubQuietButton(text = actionLabel, onClick = onAction)
        } else {
            HubPill("On", active = true)
        }
    }
}

// ── shared pieces ────────────────────────────────────────────────────────────────────────────────

/** The one gutter and rhythm every page uses, so cards line up across tabs. */
@Composable
private fun PageColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 20.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** Card heading with its own icon, which is what stops a wall of cards becoming a wall of text. */
@Composable
private fun CardHead(icon: ImageVector, title: String, accent: Color = MaterialTheme.colorScheme.primary) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HubIconChip(icon, accent = accent)
        Spacer(Modifier.width(12.dp))
        HubSectionTitle(title, Modifier.weight(1f))
    }
}

/** A labelled value that opens a picker when tapped. */
@Composable
private fun TimeRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Black,
            fontSize = 18.sp,
        )
    }
}

/** Label on the left, a fixed-width control on the right: the row must not jitter as it changes. */
@Composable
private fun StepperRow(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        Spacer(Modifier.width(10.dp))
        HubQuietButton(text = "−", onClick = onMinus)
        Text(
            value,
            Modifier.widthIn(min = 54.dp),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Black,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        HubQuietButton(text = "+", onClick = onPlus)
    }
}

@Composable
private fun DayStrip(days: Set<Int>, onToggle: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            val active = day.value in days
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable { onToggle(day.value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.name.take(1),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    color = if (active) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HubField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = false,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** The editor body for alarms and reminders: one sheet, one scroll, one gutter. */
@Composable
private fun EditorSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 22.dp),
        ) {
            HubSectionTitle(title)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun hubSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
)

// ── formatting and platform helpers ──────────────────────────────────────────────────────────────

/** A wall-clock time in the user's own style, for values that never were a timestamp. */
private fun clockLabel(hour: Int, minute: Int, hour24: Boolean): String = if (hour24) {
    "%02d:%02d".format(hour, minute)
} else {
    val clockHour = if (hour % 12 == 0) 12 else hour % 12
    "%d:%02d %s".format(clockHour, minute, if (hour < 12) "AM" else "PM")
}

private fun dismissLabel(mode: DismissMode, scanCode: String?): String = when (mode) {
    DismissMode.BUTTON -> "Tap to dismiss"
    DismissMode.SHAKE -> "Shake to dismiss"
    DismissMode.SCAN -> if (scanCode == null) "Scan anything" else "Scan to dismiss"
    DismissMode.SHAKE_OR_SCAN -> "Shake or scan"
}

private fun alarmRepeatSummary(days: Set<Int>): String = when {
    days.isEmpty() -> "Rings once, the next time this clock time comes round."
    days.size == 7 -> "Rings every day."
    days == setOf(1, 2, 3, 4, 5) -> "Rings on weekdays."
    days == setOf(6, 7) -> "Rings at the weekend."
    else -> "Rings on the days you picked."
}

/** Android 14 keeps full-screen alerts behind a special grant that has to be checked, not assumed. */
private fun canUseFullScreen(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 34) return true
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return manager.canUseFullScreenIntent()
}

/** The ringing screen only exists while the full-screen grant does, so send people to it. */
private fun openFullScreenSettings(context: Context) {
    if (Build.VERSION.SDK_INT < 34) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun openAppNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < 31) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
