package com.sylestia.toolhubs.chime.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.sylestia.toolhubs.chime.AlarmItem
import com.sylestia.toolhubs.chime.BellMode
import com.sylestia.toolhubs.chime.ChimeNotifications
import com.sylestia.toolhubs.chime.ChimeSettings
import com.sylestia.toolhubs.chime.ChimeStore
import com.sylestia.toolhubs.chime.ChimeTimes
import com.sylestia.toolhubs.chime.DueItem
import com.sylestia.toolhubs.chime.MainActivity
import com.sylestia.toolhubs.chime.ReminderItem
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Hands every alarm, reminder and bell to `AlarmManager`.
 *
 * Alarms use `setAlarmClock`, which is the strongest thing an app can ask for: it survives Doze,
 * shows the system's own alarm icon, and appears in the clock app's "next alarm" panel, so a user
 * who never opens Chime can still see that something is armed. Reminders get
 * `setExactAndAllowWhileIdle` instead: exact, but without claiming the phone's one alarm slot and
 * without waking a sleeping device for something that is not an alarm.
 *
 * Every call is wrapped: an OEM that refuses exact alarms must leave a working app behind, not a
 * crash on the add-alarm screen, so the fallback chain ends at the inexact `set`.
 */
class ChimeScheduler(private val context: Context) {

    private val manager = context.getSystemService(AlarmManager::class.java)
    private val store = ChimeStore(context)

    /**
     * Keeps the silent countdown notification in step with the schedule.
     *
     * Nothing scheduled means no notification at all: a permanent "nothing is set" entry in the
     * shade is exactly the kind of notification people swipe away and then disable.
     */
    fun refreshNextUp() {
        ChimeNotifications.ensureChannels(context)
        val item = nextUp()
        if (item == null) {
            ChimeNotifications.cancel(context, ChimeNotifications.NEXT_ID)
            return
        }
        ChimeNotifications.post(
            context,
            ChimeNotifications.NEXT_ID,
            ChimeNotifications.nextUp(context, item),
        )
    }

    fun canScheduleExact(): Boolean {
        val service = manager ?: return false
        return Build.VERSION.SDK_INT < 31 || service.canScheduleExactAlarms()
    }

    // ------------------------------------------------------------ scheduling

    fun scheduleAll() {
        store.alarms().forEach(::scheduleAlarm)
        store.reminders().forEach(::scheduleReminder)
        scheduleNextBell()
    }

    fun scheduleAlarm(item: AlarmItem) {
        val pending = ringIntent(DueItem.Kind.ALARM, item.id, ChimeReceiver.ACTION_RING, "ring")
        manager?.cancel(pending)
        if (!item.enabled) return
        val at = nextAlarmTrigger(item) ?: return
        val show = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val result = runCatching {
            manager?.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending)
        }
        if (result.isFailure) {
            // No exact-alarm permission: ring as close to the minute as the system will allow
            // rather than silently arming nothing.
            runCatching { manager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
                .onFailure { runCatching { manager?.set(AlarmManager.RTC_WAKEUP, at, pending) } }
        }
    }

    fun scheduleReminder(item: ReminderItem) {
        val pending = ringIntent(DueItem.Kind.REMINDER, item.id, ChimeReceiver.ACTION_RING, "ring")
        manager?.cancel(pending)
        if (!item.enabled) return
        val at = nextReminderTrigger(item) ?: return
        runCatching { manager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
            .onFailure { runCatching { manager?.set(AlarmManager.RTC_WAKEUP, at, pending) } }
    }

    fun cancel(kind: DueItem.Kind, id: Long) {
        manager?.cancel(ringIntent(kind, id, ChimeReceiver.ACTION_RING, "ring"))
        manager?.cancel(ringIntent(kind, id, ChimeReceiver.ACTION_RING, "snooze"))
    }

    /**
     * Arms the snooze separately from the repeating alarm, on its own request code, so snoozing
     * tomorrow's 07:00 once does not delete the 07:00 alarm itself.
     */
    fun scheduleSnooze(kind: DueItem.Kind, id: Long, untilMillis: Long) {
        val pending = ringIntent(kind, id, ChimeReceiver.ACTION_RING, "snooze", snoozed = true)
        runCatching { manager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, untilMillis, pending) }
            .onFailure { runCatching { manager?.set(AlarmManager.RTC_WAKEUP, untilMillis, pending) } }
    }

    fun cancelSnooze(kind: DueItem.Kind, id: Long) {
        manager?.cancel(ringIntent(kind, id, ChimeReceiver.ACTION_RING, "snooze", snoozed = true))
    }

    // ------------------------------------------------------------------ bells

    /** Arms the next pendulum strike, or disarms it when the clock is off or every time is quiet. */
    fun scheduleNextBell() {
        val settings = store.settings()
        manager?.cancel(bellIntent(-1, 0, false, false))
        val next = nextBellAt(settings, ZonedDateTime.now()) ?: return
        // Both the strike count and the hour travel with the intent. Reading the clock inside the
        // receiver instead is how a clock ends up ringing six times at seven o'clock: the alarm can
        // land a moment early or late, and the count would follow the wrong hour.
        //
        // A time from the "set times" list is a single chime, not a clock face. Someone who picked
        // 08:30 asked for a chime at half past eight, not eight strikes.
        val strikes = if (settings.bellMode == BellMode.SET_TIMES) {
            1
        } else {
            ChimeTimes.strikesFor(next.hour)
        }
        val hour24 = settings.use24Hour ?: android.text.format.DateFormat.is24HourFormat(context)
        val pending = bellIntent(next.hour, strikes, settings.speakTime, hour24)
        runCatching {
            manager?.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                next.toInstant().toEpochMilli(),
                pending,
            )
        }.onFailure {
            runCatching {
                manager?.set(
                    AlarmManager.RTC_WAKEUP,
                    next.toInstant().toEpochMilli(),
                    pending,
                )
            }
        }
    }

    fun cancelBell() = manager?.cancel(bellIntent(-1, 0, false, false))

    // ------------------------------------------------------------------ reads

    fun nextAlarmTrigger(item: AlarmItem): Long? {
        val now = System.currentTimeMillis()
        val snoozed = store.snoozeUntil(DueItem.Kind.ALARM, item.id)
        if (snoozed > now) return snoozed
        return ChimeTimes.nextTrigger(item, zonedNow())?.toInstant()?.toEpochMilli()
    }

    fun nextReminderTrigger(item: ReminderItem): Long? = ChimeTimes
        .nextTrigger(item, zonedNow())
        ?.toInstant()
        ?.toEpochMilli()

    /** The soonest armed thing, which is what the live countdown notification shows. */
    fun nextUp(now: Long = System.currentTimeMillis()): DueItem? {
        val candidates = buildList {
            store.alarms().filter { it.enabled }.forEach { alarm ->
                val at = nextAlarmTrigger(alarm)
                if (at != null && at > now) add(dueItem(alarm, at))
            }
            store.reminders().filter { it.enabled }.forEach { reminder ->
                val at = nextReminderTrigger(reminder)
                if (at != null && at > now) add(dueItem(reminder, at))
            }
        }
        return candidates.minByOrNull { it.triggerAt }
    }

    /** Rebuilds what is ringing at fire time, from the store, so the intent only carries an id. */
    fun dueItem(kind: DueItem.Kind, id: Long, now: Long = System.currentTimeMillis()): DueItem? = when (kind) {
        DueItem.Kind.ALARM -> store.alarms().firstOrNull { it.id == id }?.let { item ->
            dueItem(item, nextAlarmTrigger(item) ?: now)
        }
        DueItem.Kind.REMINDER -> store.reminders().firstOrNull { it.id == id }?.let { item ->
            dueItem(item, nextReminderTrigger(item) ?: now)
        }
    }

    private fun dueItem(item: AlarmItem, triggerAt: Long) = DueItem(
        kind = DueItem.Kind.ALARM,
        id = item.id,
        title = item.label.ifBlank { "Alarm ${item.timeLabel(use24Hour())}" },
        subtitle = "${item.timeLabel(use24Hour())} · ${item.dayLabel()}",
        triggerAt = triggerAt,
        dismissMode = item.dismissMode,
        scanCode = item.scanCode,
        snoozeMinutes = store.effectiveSnoozeMinutes(DueItem.Kind.ALARM, item.id, item.snoozeMinutes),
        vibrate = item.vibrate,
    )

    private fun dueItem(item: ReminderItem, triggerAt: Long) = DueItem(
        kind = DueItem.Kind.REMINDER,
        id = item.id,
        title = item.title.ifBlank { "Reminder" },
        subtitle = if (item.note.isBlank()) item.repeatLabel() else item.note,
        triggerAt = triggerAt,
        dismissMode = item.dismissMode,
        scanCode = item.scanCode,
        snoozeMinutes = store.effectiveSnoozeMinutes(
            DueItem.Kind.REMINDER,
            item.id,
            item.snoozeMinutes,
        ),
        vibrate = true,
    )

    // ---------------------------------------------------------------- helpers

    private fun zonedNow(): ZonedDateTime = Instant.now().atZone(ZoneId.systemDefault())

    /**
     * The clock style in force right now: the user's own choice, or the device's when it is "follow
     * the system". Every string the app writes outside a composable has to ask this, or a 12-hour
     * phone gets 24-hour notifications beside its 12-hour clock face.
     */
    private fun use24Hour(): Boolean =
        store.settings().use24Hour ?: android.text.format.DateFormat.is24HourFormat(context)

    /**
     * The next strike the bell is allowed to make.
     *
     * Quiet hours are applied here rather than at ring time so the phone is genuinely not woken:
     * a strike that would land inside the window is skipped and the next allowed one armed instead.
     */
    private fun nextBellAt(settings: ChimeSettings, from: ZonedDateTime): ZonedDateTime? {
        // A spoken time with the bell switched off still needs a cadence, and for a clock the hour
        // is the only one that makes sense.
        if (settings.bellMode == BellMode.OFF && !settings.speakTime) return null
        val candidates: Sequence<ZonedDateTime> = when (settings.bellMode) {
            BellMode.OFF, BellMode.HOURLY -> generateSequence(from.truncatedTo(ChronoUnit.HOURS).plusHours(1)) {
                it.plusHours(1)
            }
            BellMode.SET_TIMES -> sequence {
                val times = settings.bellTimes.sorted()
                var date = from.toLocalDate()
                repeat(3) {
                    for (minute in times) yield(date.atTime(minute / 60, minute % 60).atZone(from.zone))
                    date = date.plusDays(1)
                }
            }
            BellMode.OFF -> emptySequence()
        }
        return candidates.firstOrNull { it.isAfter(from) && !settings.isQuietHour(it.hour) }
    }

    private fun ringIntent(
        kind: DueItem.Kind,
        id: Long,
        action: String,
        slot: String,
        snoozed: Boolean = false,
    ): PendingIntent {
        val intent = Intent(context, ChimeReceiver::class.java).apply {
            this.action = action
            putExtra(ChimeReceiver.EXTRA_KIND, kind.name)
            putExtra(ChimeReceiver.EXTRA_ID, id)
            putExtra(ChimeReceiver.EXTRA_SNOOZED, snoozed)
        }
        return PendingIntent.getBroadcast(
            context,
            "$slot-$action-$kind-$id".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun bellIntent(hour: Int, strikes: Int, speak: Boolean, hour24: Boolean): PendingIntent {
        val intent = Intent(context, ChimeReceiver::class.java).apply {
            action = ChimeReceiver.ACTION_BELL
            putExtra(ChimeReceiver.EXTRA_HOUR, hour)
            putExtra(ChimeReceiver.EXTRA_STRIKES, strikes)
            putExtra(ChimeReceiver.EXTRA_SPEAK, speak)
            putExtra(ChimeReceiver.EXTRA_HOUR24, hour24)
        }
        return PendingIntent.getBroadcast(
            context,
            "bell".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
