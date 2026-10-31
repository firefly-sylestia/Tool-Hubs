package com.sylestia.toolhubs.chime

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * How much work the user has to do before an alarm lets them go.
 *
 * [SCAN] exists for the reason people ask for it: a code stuck to the bathroom mirror is a genuine
 * "get out of bed" gate, while a code on the phone in the same hand is not. The scanner accepts a
 * value, but so does the app's own log — the match is recorded either way.
 */
enum class DismissMode { BUTTON, SHAKE, SCAN, SHAKE_OR_SCAN }

/** One-shot reminders vanish after ringing; the repeating rules re-schedule themselves. */
enum class RepeatRule { ONCE, DAILY, WEEKLY }

/** When the pendulum bell is allowed to speak. */
enum class BellMode { OFF, HOURLY, SET_TIMES }

data class AlarmItem(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String,
    /** `DayOfWeek.value` 1–7. Empty means "the next time this clock time comes round", one-shot. */
    val days: Set<Int>,
    val enabled: Boolean,
    val dismissMode: DismissMode,
    /** Barcode value that dismisses this alarm, or null when any scan is accepted. */
    val scanCode: String?,
    val snoozeMinutes: Int,
    val vibrate: Boolean,
) {
    val isRepeating: Boolean get() = days.isNotEmpty()

    /** The time as the user reads a clock, honouring their 12-hour or 24-hour choice. */
    fun timeLabel(hour24: Boolean = true): String = if (hour24) {
        "%02d:%02d".format(hour, minute)
    } else {
        val clockHour = if (hour % 12 == 0) 12 else hour % 12
        "%d:%02d %s".format(clockHour, minute, if (hour < 12) "AM" else "PM")
    }

    fun dayLabel(): String = when {
        days.isEmpty() -> "Once"
        days.size == 7 -> "Every day"
        days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
        days == setOf(6, 7) -> "Weekends"
        else -> DayOfWeek.entries.filter { it.value in days }
            .joinToString(" ") { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
    }
}

data class ReminderItem(
    val id: Long,
    val title: String,
    val note: String,
    val atMillis: Long,
    val repeat: RepeatRule,
    val enabled: Boolean,
    val dismissMode: DismissMode,
    val scanCode: String?,
    val snoozeMinutes: Int,
) {
    fun repeatLabel(): String = when (repeat) {
        RepeatRule.ONCE -> "Once"
        RepeatRule.DAILY -> "Every day"
        RepeatRule.WEEKLY -> "Every week"
    }
}

/**
 * Everything the bells and announcements answer to.
 *
 * Quiet hours are on by default and are the reason [BellMode.HOURLY] is safe to leave enabled: the
 * clock chimes through the day and stays silent overnight without the user having to remember to
 * switch it off. A bedtime that runs past midnight (22 → 7) is the normal case, so the window is
 * allowed to wrap.
 */
data class ChimeSettings(
    /** When the clock may speak at all: never, every hour, or only at the times below. */
    val bellMode: BellMode,
    /** Minutes past midnight for [BellMode.SET_TIMES]. */
    val bellTimes: List<Int>,
    /**
     * Whether the bell sounds, and whether the time is spoken, are separate switches on purpose:
     * a pendulum strike on its own, a spoken time on its own, or both are all reasonable, and one
     * combined "announce" toggle forced the two together.
     */
    val bellEnabled: Boolean,
    val speakTime: Boolean,
    /** null follows the device's own 12/24-hour setting rather than overriding it. */
    val use24Hour: Boolean?,
    val quietHoursEnabled: Boolean,
    val quietFromHour: Int,
    val quietToHour: Int,
    val defaultSnoozeMinutes: Int,
    val defaultDismissMode: DismissMode,
    /** Seconds the alarm volume takes to climb from a whisper to the stream maximum. */
    val rampSeconds: Int,
    /** Safety stop: an alarm that keeps ringing forever is a bug, not a feature. */
    val ringTimeoutMinutes: Int,
) {
    /** True when [hour] falls in the quiet window, including a window that wraps past midnight. */
    fun isQuietHour(hour: Int): Boolean {
        if (!quietHoursEnabled) return false
        if (quietFromHour == quietToHour) return false
        return if (quietFromHour < quietToHour) {
            hour >= quietFromHour && hour < quietToHour
        } else {
            hour >= quietFromHour || hour < quietToHour
        }
    }

    fun quietWindowLabel(): String = "%02d:00 – %02d:00".format(quietFromHour, quietToHour)

    companion object {
        val DEFAULT = ChimeSettings(
            bellMode = BellMode.HOURLY,
            bellTimes = emptyList(),
            bellEnabled = true,
            speakTime = false,
            use24Hour = null,
            quietHoursEnabled = true,
            quietFromHour = 22,
            quietToHour = 7,
            defaultSnoozeMinutes = 5,
            defaultDismissMode = DismissMode.BUTTON,
            rampSeconds = 20,
            ringTimeoutMinutes = 15,
        )
    }
}

/** One scheduled thing, whatever kind it is, as the scheduler and the notification both need it. */
data class DueItem(
    val kind: Kind,
    val id: Long,
    val title: String,
    val subtitle: String,
    val triggerAt: Long,
    val dismissMode: DismissMode,
    val scanCode: String?,
    val snoozeMinutes: Int,
    val vibrate: Boolean,
) {
    enum class Kind { ALARM, REMINDER }
}

object ChimeTimes {

    /** Number of strikes a pendulum clock makes for [hour]: twelve at noon and midnight, never zero. */
    fun strikesFor(hour: Int): Int {
        val mod = hour % 12
        return if (mod == 0) 12 else mod
    }

    /**
     * The next wall-clock moment this alarm should ring at or after [from].
     *
     * Returns null for a disabled alarm, and for a one-shot whose time has already gone by today —
     * an alarm is not a reminder, and firing one immediately on creation is how an alarm clock
     * becomes something people uninstall.
     */
    fun nextTrigger(alarm: AlarmItem, from: ZonedDateTime): ZonedDateTime? {
        if (!alarm.enabled) return null
        val time = LocalTime.of(alarm.hour, alarm.minute)
        val startDate: LocalDate = from.toLocalDate()

        if (alarm.days.isEmpty()) {
            val today = startDate.atTime(time).atZone(from.zone)
            return if (today.isAfter(from)) today else startDate.plusDays(1).atTime(time).atZone(from.zone)
        }

        // Seven days is the whole week, so one of them always matches.
        for (offset in 0..7) {
            val date = startDate.plusDays(offset.toLong())
            if (date.dayOfWeek.value !in alarm.days) continue
            val candidate = date.atTime(time).atZone(from.zone)
            if (candidate.isAfter(from)) return candidate
        }
        return null
    }

    /** The next moment a reminder should fire, following its repeat rule. */
    fun nextTrigger(reminder: ReminderItem, from: ZonedDateTime): ZonedDateTime? {
        if (!reminder.enabled) return null
        val at = java.time.Instant.ofEpochMilli(reminder.atMillis).atZone(from.zone)
        if (reminder.repeat == RepeatRule.ONCE) {
            return if (at.isAfter(from)) at else null
        }
        var candidate = at
        val step = if (reminder.repeat == RepeatRule.DAILY) 1L else 7L
        // Advance whole periods rather than adding one at a time, so a reminder left running for
        // months does not have to loop thousands of times to find its next turn.
        if (!candidate.isAfter(from)) {
            val daysBehind = ChronoUnit.DAYS.between(candidate.toLocalDate(), from.toLocalDate())
            val periods = daysBehind / step
            if (periods > 0) candidate = candidate.plusDays(periods * step)
            while (!candidate.isAfter(from)) candidate = candidate.plusDays(step)
        }
        return candidate
    }

    /** Minutes past midnight, formatted as a clock time, for the set-times list. */
    fun minuteLabel(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    /** "in 8h 12m" / "in 45m" / "now", for the notification and the clock card. */
    fun untilLabel(targetAt: Long, now: Long): String {
        val seconds = (targetAt - now) / 1000
        if (seconds <= 30) return "now"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "in %dh %02dm".format(hours, minutes)
            minutes > 0 -> "in %dm".format(minutes)
            else -> "in under a minute"
        }
    }
}
