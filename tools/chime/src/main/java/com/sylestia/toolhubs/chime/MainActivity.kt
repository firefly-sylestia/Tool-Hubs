package com.sylestia.toolhubs.chime

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.chime.bell.TimeVoice
import com.sylestia.toolhubs.chime.schedule.ChimeScheduler
import com.sylestia.toolhubs.ui.HubBackdrop
import com.sylestia.toolhubs.ui.HubGlassCard
import com.sylestia.toolhubs.ui.HubPrimaryButton
import com.sylestia.toolhubs.ui.HubQuietButton
import com.sylestia.toolhubs.ui.HubSegmented
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChimeNotifications.ensureChannels(this)
        setContent {
            MaterialTheme {
                ChimeApp()
            }
        }
    }
}

private enum class ChimeTab(val label: String) {
    CLOCK("Clock"),
    ALARMS("Alarms"),
    REMINDERS("Reminders"),
    SETTINGS("Settings"),
}

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

    // Re-reads the store whenever something changed, and every second for the clock face.
    LaunchedEffect(revision) {
        alarms = store.alarms()
        reminders = store.reminders()
        settings = store.settings()
        scheduler.refreshNextUp()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !ChimeNotifications.canPost(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    HubBackdrop {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            bottomBar = {
                NavigationBar {
                    ChimeTab.entries.forEachIndexed { index, entry ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = {
                                Text(entry.label.take(1), fontWeight = FontWeight.Black)
                            },
                            label = { Text(entry.label, fontSize = 10.sp) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (ChimeTab.entries[tab]) {
                    ChimeTab.CLOCK -> ClockScreen(
                        settings = settings,
                        now = now,
                        nextUp = scheduler.nextUp(now),
                        onSpeakPreview = { TimeVoice.preview(it, now) },
                        onSettings = { changed -> store.saveSettings(changed); revision++ },
                    )

                    ChimeTab.ALARMS -> AlarmsScreen(
                        alarms = alarms,
                        settings = settings,
                        onChanged = { revision++ },
                        store = store,
                        scheduler = scheduler,
                    )

                    ChimeTab.REMINDERS -> RemindersScreen(
                        reminders = reminders,
                        settings = settings,
                        store = store,
                        scheduler = scheduler,
                        onChanged = { revision++ },
                    )

                    ChimeTab.SETTINGS -> SettingsScreen(
                        settings = settings,
                        store = store,
                        scheduler = scheduler,
                        onChanged = { revision++ },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClockScreen(
    settings: ChimeSettings,
    now: Long,
    nextUp: DueItem?,
    onSpeakPreview: suspend (Long) -> Unit,
    onSettings: (ChimeSettings) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val zoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
    val hourNow = zoned.hour

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp),
    ) {
        item {
            HubGlassCard(accent = MaterialTheme.colorScheme.tertiary) {
                Text(
                    ChimeFormat.clockWithSeconds(now),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    ChimeFormat.longDate(now),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (settings.isQuietHour(hourNow)) {
                        "Quiet hours: the bell stays silent until ${"%02d:00".format(settings.quietToHour)}."
                    } else {
                        "The bell strikes ${ChimeTimes.strikesFor(hourNow)} times at " +
                            "${(hourNow + 1) % 24}:00."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))
                HubQuietButton(
                    text = "Speak the time now",
                    onClick = { scope.launch { onSpeakPreview(now) } },
                )
            }
        }

        item {
            HubGlassCard {
                Text("Next up", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                if (nextUp == null) {
                    Text(
                        "Nothing is scheduled yet. Add an alarm or a reminder.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    Text(
                        "${nextUp.title} · ${ChimeFormat.clock(nextUp.triggerAt)}",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "${ChimeTimes.untilLabel(nextUp.triggerAt, now)} · ${nextUp.subtitle}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        item {
            HubGlassCard {
                Text("Pendulum bell", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text(
                    "Strikes the hour as many times as the hour, and stays silent through quiet hours. " +
                        "Set times rings only when you chose them.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                HubSegmented(
                    options = listOf("Off", "Every hour", "Set times"),
                    selectedIndex = settings.bellMode.ordinal,
                    onSelect = { index ->
                        val mode = BellMode.entries[index]
                        onSettings(settings.copy(bellMode = mode))
                    },
                )
                Spacer(Modifier.height(10.dp))
                BellTimesRow(settings, onSettings)
            }
        }
    }
}

@Composable
private fun BellTimesRow(settings: ChimeSettings, onSettings: (ChimeSettings) -> Unit) {
    val context = LocalContext.current
    Column {
        Text(
            if (settings.bellTimes.isEmpty()) {
                "No set times yet."
            } else {
                "Rings at " + settings.bellTimes.sorted().joinToString(", ") { ChimeTimes.minuteLabel(it) }
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HubQuietButton(
                text = "Add a time",
                modifier = Modifier.weight(1f),
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            onSettings(
                                settings.copy(
                                    bellMode = if (settings.bellMode == BellMode.OFF) {
                                        BellMode.SET_TIMES
                                    } else {
                                        settings.bellMode
                                    },
                                    bellTimes = (settings.bellTimes + hour * 60 + minute).distinct().sorted(),
                                )
                            )
                        },
                        8,
                        0,
                        true,
                    ).show()
                },
            )
            if (settings.bellTimes.isNotEmpty()) {
                HubQuietButton(
                    text = "Clear",
                    modifier = Modifier.weight(1f),
                    onClick = { onSettings(settings.copy(bellTimes = emptyList())) },
                )
            }
        }
    }
}

@Composable
private fun AlarmsScreen(
    alarms: List<AlarmItem>,
    settings: ChimeSettings,
    store: ChimeStore,
    scheduler: ChimeScheduler,
    onChanged: () -> Unit,
) {
    var editing by remember { mutableStateOf<AlarmItem?>(null) }
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp),
        ) {
            item {
                HubGlassCard {
                    Text("Alarms", fontWeight = FontWeight.Black, fontSize = 22.sp)
                    Text(
                        "Dismiss with a button, a shake streak or a scanned code.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            items(alarms, key = { it.id }) { alarm ->
                HubGlassCard(
                    accent = if (alarm.enabled) MaterialTheme.colorScheme.tertiary else null,
                    onClick = { editing = alarm },
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                alarm.timeLabel(),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black,
                                color = if (alarm.enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                listOfNotNull(
                                    alarm.label.takeIf { it.isNotBlank() },
                                    alarm.dayLabel(),
                                    dismissLabel(alarm.dismissMode),
                                ).joinToString(" · "),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                            val next = scheduler.nextAlarmTrigger(alarm)
                            if (alarm.enabled && next != null) {
                                Text(
                                    "Next ${ChimeFormat.longDate(next)} ${ChimeFormat.clock(next)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        Switch(
                            checked = alarm.enabled,
                            onCheckedChange = { on ->
                                store.setAlarmEnabled(alarm.id, on)
                                if (on) scheduler.scheduleAlarm(alarm.copy(enabled = true))
                                else scheduler.cancel(DueItem.Kind.ALARM, alarm.id)
                                onChanged()
                            },
                        )
                    }
                }
            }
            item {
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
}

private fun dismissLabel(mode: DismissMode): String = when (mode) {
    DismissMode.BUTTON -> "Button"
    DismissMode.SHAKE -> "Shake"
    DismissMode.SCAN -> "Scan"
    DismissMode.SHAKE_OR_SCAN -> "Shake or scan"
}

@Composable
private fun AlarmEditor(
    initial: AlarmItem?,
    settings: ChimeSettings,
    onDismiss: () -> Unit,
    onSave: (AlarmItem) -> Unit,
    onDelete: (AlarmItem) -> Unit,
) {
    val context = LocalContext.current
    var hour by remember { mutableIntStateOf(initial?.hour ?: 7) }
    var minute by remember { mutableIntStateOf(initial?.minute ?: 0) }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var days by remember { mutableStateOf(initial?.days ?: emptySet<Int>()) }
    var mode by remember { mutableIntStateOf((initial?.dismissMode ?: settings.defaultDismissMode).ordinal) }
    var scanCode by remember { mutableStateOf(initial?.scanCode ?: "") }
    var vibrate by remember { mutableStateOf(initial?.vibrate ?: true) }
    var snooze by remember { mutableIntStateOf(initial?.snoozeMinutes ?: settings.defaultSnoozeMinutes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New alarm" else "Edit alarm") },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    AlarmItem(
                        id = initial?.id ?: System.currentTimeMillis(),
                        hour = hour,
                        minute = minute,
                        label = label,
                        days = days,
                        enabled = true,
                        dismissMode = DismissMode.entries[mode],
                        scanCode = scanCode.ifBlank { null },
                        snoozeMinutes = snooze,
                        vibrate = vibrate,
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { onDelete(initial) }) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("%02d:%02d".format(hour, minute), fontSize = 34.sp, fontWeight = FontWeight.Black)
                    HubQuietButton(
                        text = "Change",
                        onClick = {
                            TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, true).show()
                        },
                    )
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text("Repeat", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        val active = day.value in days
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    }
                                )
                                .clickable {
                                    days = if (active) days - day.value else days + day.value
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                day.name.take(1),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (active) {
                                    MaterialTheme.colorScheme.onTertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Dismiss with", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HubSegmented(
                    options = listOf("Button", "Shake", "Scan", "Either"),
                    selectedIndex = mode,
                    onSelect = { mode = it },
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (DismissMode.entries[mode] == DismissMode.SCAN || DismissMode.entries[mode] == DismissMode.SHAKE_OR_SCAN) {
                    OutlinedTextField(
                        value = scanCode,
                        onValueChange = { scanCode = it },
                        label = { Text("Code to accept (blank accepts any)") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vibrate", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = vibrate, onCheckedChange = { vibrate = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Snooze ${snooze} min", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HubQuietButton(text = "−", onClick = { snooze = (snooze - 1).coerceAtLeast(1) })
                        HubQuietButton(text = "+", onClick = { snooze = (snooze + 1).coerceAtMost(60) })
                    }
                }
            }
        },
    )
}

@Composable
private fun RemindersScreen(
    reminders: List<ReminderItem>,
    settings: ChimeSettings,
    store: ChimeStore,
    scheduler: ChimeScheduler,
    onChanged: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp),
        ) {
            item {
                HubGlassCard {
                    Text("Reminders", fontWeight = FontWeight.Black, fontSize = 22.sp)
                    Text(
                        "A reminder takes over the screen when it arrives, exactly like an alarm, " +
                            "but it can be answered without getting up.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            if (reminders.isEmpty()) {
                item {
                    HubGlassCard {
                        Text(
                            "Nothing here yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            items(reminders, key = { it.id }) { reminder ->
                HubGlassCard(accent = if (reminder.enabled) MaterialTheme.colorScheme.primary else null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                reminder.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "${reminder.repeatLabel()} · " +
                                    "${ChimeFormat.longDate(reminder.atMillis)} " +
                                    ChimeFormat.clock(reminder.atMillis) +
                                    " · ${dismissLabel(reminder.dismissMode)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                            if (reminder.note.isNotBlank()) {
                                Text(
                                    reminder.note,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        Switch(
                            checked = reminder.enabled,
                            onCheckedChange = { on ->
                                store.setReminderEnabled(reminder.id, on)
                                if (on) {
                                    scheduler.scheduleReminder(reminder.copy(enabled = true))
                                } else {
                                    scheduler.cancel(DueItem.Kind.REMINDER, reminder.id)
                                }
                                onChanged()
                            },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    HubQuietButton(
                        text = "Delete",
                        onClick = {
                            store.deleteReminder(reminder.id)
                            scheduler.cancel(DueItem.Kind.REMINDER, reminder.id)
                            onChanged()
                        },
                    )
                }
            }
            item {
                HubPrimaryButton(
                    text = "Add a reminder",
                    icon = Icons.Rounded.Add,
                    onClick = { creating = true },
                )
            }
        }

        if (creating) {
            ReminderEditor(
                settings = settings,
                onDismiss = { creating = false },
                onSave = { item ->
                    store.saveReminder(item)
                    scheduler.scheduleReminder(item)
                    creating = false
                    onChanged()
                },
            )
        }
    }
}

@Composable
private fun ReminderEditor(
    settings: ChimeSettings,
    onDismiss: () -> Unit,
    onSave: (ReminderItem) -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var atMillis by remember { mutableLongStateOf(System.currentTimeMillis() + 60 * 60_000L) }
    var repeatIndex by remember { mutableIntStateOf(0) }
    var modeIndex by remember { mutableIntStateOf(settings.defaultDismissMode.ordinal) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New reminder") },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    ReminderItem(
                        id = System.currentTimeMillis(),
                        title = title.ifBlank { "Reminder" },
                        note = note,
                        atMillis = atMillis,
                        repeat = RepeatRule.entries[repeatIndex],
                        enabled = true,
                        dismissMode = DismissMode.entries[modeIndex],
                        scanCode = null,
                        snoozeMinutes = settings.defaultSnoozeMinutes,
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("What is it for?") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "${ChimeFormat.longDate(atMillis)} · ${ChimeFormat.clock(atMillis)}",
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    HubQuietButton(text = "Pick date", onClick = {
                        val zone = ZoneId.systemDefault()
                        val current = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                atMillis = current
                                    .withYear(year)
                                    .withMonth(month + 1)
                                    .withDayOfMonth(day)
                                    .toInstant()
                                    .toEpochMilli()
                            },
                            current.year,
                            current.monthValue - 1,
                            current.dayOfMonth,
                        ).show()
                    })
                    HubQuietButton(text = "Pick time", onClick = {
                        val zone = ZoneId.systemDefault()
                        val current = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                atMillis = current
                                    .withHour(hour)
                                    .withMinute(minute)
                                    .withSecond(0)
                                    .toInstant()
                                    .toEpochMilli()
                            },
                            current.hour,
                            current.minute,
                            true,
                        ).show()
                    })
                }
                Spacer(Modifier.height(12.dp))
                Text("Repeat", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HubSegmented(
                    options = listOf("Once", "Daily", "Weekly"),
                    selectedIndex = repeatIndex,
                    onSelect = { repeatIndex = it },
                )
                Spacer(Modifier.height(10.dp))
                Text("Dismiss with", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HubSegmented(
                    options = listOf("Button", "Shake", "Scan", "Either"),
                    selectedIndex = modeIndex,
                    onSelect = { modeIndex = it },
                )
            }
        },
    )
}

@Composable
private fun SettingsScreen(
    settings: ChimeSettings,
    store: ChimeStore,
    scheduler: ChimeScheduler,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp),
    ) {
        item {
            HubGlassCard {
                Text("Quiet hours", fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(
                    "The bell and the spoken time stay silent inside this window. It is on by " +
                        "default so an hourly clock cannot wake anyone up.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.quietHoursEnabled,
                        onCheckedChange = {
                            store.saveSettings(settings.copy(quietHoursEnabled = it))
                            scheduler.scheduleNextBell()
                            onChanged()
                        },
                    )
                }
                Text(
                    settings.quietWindowLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    HubQuietButton(text = "From", onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, _ ->
                                store.saveSettings(settings.copy(quietFromHour = hour))
                                scheduler.scheduleNextBell()
                                onChanged()
                            },
                            settings.quietFromHour,
                            0,
                            true,
                        ).show()
                    })
                    HubQuietButton(text = "To", onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, _ ->
                                store.saveSettings(settings.copy(quietToHour = hour))
                                scheduler.scheduleNextBell()
                                onChanged()
                            },
                            settings.quietToHour,
                            0,
                            true,
                        ).show()
                    })
                }
            }
        }

        item {
            HubGlassCard {
                Text("When a bell rings", fontWeight = FontWeight.Black, fontSize = 20.sp)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Speak the time", fontSize = 14.sp)
                        Text(
                            "Says the time out loud with each strike, in words rather than digits.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = settings.announceTime,
                        onCheckedChange = {
                            store.saveSettings(
                                settings.copy(
                                    announceTime = it,
                                    bellMode = if (it && settings.bellMode == BellMode.OFF) {
                                        BellMode.HOURLY
                                    } else {
                                        settings.bellMode
                                    },
                                )
                            )
                            scheduler.scheduleNextBell()
                            onChanged()
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume ramp", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HubQuietButton(text = "−", onClick = {
                            store.saveSettings(settings.copy(rampSeconds = (settings.rampSeconds - 5).coerceAtLeast(0)))
                            onChanged()
                        })
                        Text("${settings.rampSeconds}s", fontSize = 14.sp)
                        HubQuietButton(text = "+", onClick = {
                            store.saveSettings(settings.copy(rampSeconds = (settings.rampSeconds + 5).coerceAtMost(120)))
                            onChanged()
                        })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Stop ringing after", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HubQuietButton(text = "−", onClick = {
                            store.saveSettings(
                                settings.copy(ringTimeoutMinutes = (settings.ringTimeoutMinutes - 1).coerceAtLeast(1))
                            )
                            onChanged()
                        })
                        Text("${settings.ringTimeoutMinutes}m", fontSize = 14.sp)
                        HubQuietButton(text = "+", onClick = {
                            store.saveSettings(
                                settings.copy(ringTimeoutMinutes = (settings.ringTimeoutMinutes + 1).coerceAtMost(60))
                            )
                            onChanged()
                        })
                    }
                }
            }
        }

        item {
            HubGlassCard {
                Text("Exact alarms", fontWeight = FontWeight.Black, fontSize = 20.sp)
                val exact = scheduler.canScheduleExact()
                Text(
                    if (exact) {
                        "Exact alarms are available, so an alarm rings at the minute you set."
                    } else {
                        "Android is withholding exact alarms, so they can drift by a few minutes."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                if (!exact) {
                    Spacer(Modifier.height(10.dp))
                    HubPrimaryButton(text = "Open alarm settings", onClick = {
                        if (Build.VERSION.SDK_INT >= 31) {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                                    )
                                )
                            }
                        }
                    })
                }
                Spacer(Modifier.height(10.dp))
                HubQuietButton(
                    text = "Test the bell and the spoken time",
                    onClick = {
                        scope.launch {
                            com.sylestia.toolhubs.chime.bell.BellVoice.strike(context, 3)
                            TimeVoice.preview(context, System.currentTimeMillis())
                        }
                    },
                )
            }
        }

        item {
            HubGlassCard {
                Text("About", fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(
                    "Chime ${com.sylestia.toolhubs.chime.BuildConfig.VERSION_NAME} — alarms, reminders " +
                        "and a chiming clock.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
