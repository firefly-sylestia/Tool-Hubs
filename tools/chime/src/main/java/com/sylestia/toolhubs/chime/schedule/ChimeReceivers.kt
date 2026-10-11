package com.sylestia.toolhubs.chime.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sylestia.toolhubs.chime.ChimeNotifications
import com.sylestia.toolhubs.chime.ChimeStore
import com.sylestia.toolhubs.chime.DueItem
import com.sylestia.toolhubs.chime.RepeatRule
import com.sylestia.toolhubs.chime.bell.BellService
import com.sylestia.toolhubs.chime.bell.BellVoice
import com.sylestia.toolhubs.chime.bell.TimeVoice
import com.sylestia.toolhubs.chime.ring.RingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * Fires when an alarm, a reminder or the bell is due.
 *
 * It does three things and gets out of the way: hand the ringing to [RingService], re-arm the *next*
 * occurrence of a repeating item, and refresh the countdown notification. A one-shot reminder that
 * pointed at a past moment is switched off here rather than left armed forever, because a reminder
 * that keeps re-firing its stale timestamp is worse than one that quietly retires.
 */
class ChimeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = ChimeStore(context)
        val scheduler = ChimeScheduler(context)
        when (intent.action) {
            ACTION_RING -> {
                val kind = kindOf(intent)
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                if (id < 0L) return
                val snoozed = intent.getBooleanExtra(EXTRA_SNOOZED, false)
                store.clearSnooze(kind, id)
                RingService.start(context, kind, id)
                rearm(context, kind, id)
                if (!snoozed) retireOneShot(context, kind, id)
                scheduler.refreshNextUp()
            }

            ACTION_BELL -> {
                val settings = store.settings()
                // The hour comes from the intent, filled in from the moment the strike was armed for.
                // Reading the wall clock here instead is what made the count drift: an alarm that
                // landed a moment early struck once too few.
                val armedHour = intent.getIntExtra(EXTRA_HOUR, -1)
                val hour = if (armedHour in 0..23) armedHour else ZonedDateTime.now().hour
                if (settings.isQuietHour(hour)) {
                    scheduler.scheduleNextBell()
                    return
                }
                // The count was decided when the strike was armed, because only the arm-time knows
                // whether this hour counts itself out or is a single chime from the set-times list.
                val armedStrikes = intent.getIntExtra(
                    EXTRA_STRIKES,
                    com.sylestia.toolhubs.chime.ChimeTimes.strikesFor(hour),
                )
                val strikes = if (settings.bellEnabled) armedStrikes else 0
                val speak = settings.speakTime
                val hour24 = intent.getBooleanExtra(EXTRA_HOUR24, false)
                if (strikes <= 0 && !speak) {
                    // Nothing is switched on, so arming the next one costs nothing but a wake-up.
                    scheduler.scheduleNextBell()
                    return
                }
                if (!BellService.start(context, strikes, speak, hour24)) {
                    // The start was refused, so ring inside the broadcast window rather than lose the
                    // chime. A twelve-strike chime may not finish; a missed hour is worse than a cut one.
                    val pending = goAsync()
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    scope.launch {
                        runCatching { BellVoice.strike(context, strikes) }
                        if (speak) runCatching { TimeVoice.announce(context, hour24) }
                        pending.finish()
                    }
                }
                scheduler.scheduleNextBell()
            }
        }
    }

    private fun kindOf(intent: Intent): DueItem.Kind = runCatching {
        DueItem.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: DueItem.Kind.ALARM.name)
    }.getOrDefault(DueItem.Kind.ALARM)

    private fun rearm(context: Context, kind: DueItem.Kind, id: Long) {
        val store = ChimeStore(context)
        val scheduler = ChimeScheduler(context)
        when (kind) {
            DueItem.Kind.ALARM -> store.alarms().firstOrNull { it.id == id }?.let(scheduler::scheduleAlarm)
            DueItem.Kind.REMINDER -> store.reminders().firstOrNull { it.id == id }
                ?.let(scheduler::scheduleReminder)
        }
    }

    private fun retireOneShot(context: Context, kind: DueItem.Kind, id: Long) {
        val store = ChimeStore(context)
        when (kind) {
            DueItem.Kind.ALARM -> store.alarms().firstOrNull { it.id == id }?.let { alarm ->
                if (!alarm.isRepeating) store.setAlarmEnabled(id, false)
            }
            DueItem.Kind.REMINDER -> store.reminders().firstOrNull { it.id == id }?.let { reminder ->
                if (reminder.repeat == RepeatRule.ONCE) store.setReminderEnabled(id, false)
            }
        }
    }

    companion object {
        const val ACTION_RING = "com.sylestia.toolhubs.chime.ACTION_RING"
        const val ACTION_BELL = "com.sylestia.toolhubs.chime.ACTION_BELL"
        const val EXTRA_KIND = "kind"
        const val EXTRA_ID = "id"
        const val EXTRA_SNOOZED = "snoozed"
        /** The hour the strike was armed for, so a delayed chime still rings the right count. */
        const val EXTRA_HOUR = "hour"
        /** Strikes decided at arm time, so the count cannot follow a late-running hour. */
        const val EXTRA_STRIKES = "strikes"
        const val EXTRA_SPEAK = "speak"
        const val EXTRA_HOUR24 = "hour24"
    }
}

/**
 * The notification's own buttons.
 *
 * Snooze stores the new time and arms it, because the service is stopped a moment later and
 * anything only held in memory would be lost with it. Dismiss clears the snooze chain too: a user
 * who dismisses has answered the alarm, and leaving a snooze armed would ring again for nobody.
 */
class ChimeActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = ChimeStore(context)
        val scheduler = ChimeScheduler(context)
        val kind = runCatching {
            DueItem.Kind.valueOf(intent.getStringExtra(ChimeReceiver.EXTRA_KIND) ?: DueItem.Kind.ALARM.name)
        }.getOrDefault(DueItem.Kind.ALARM)
        val id = intent.getLongExtra(ChimeReceiver.EXTRA_ID, -1L)
        if (id < 0L) {
            RingService.stop(context)
            return
        }

        when (intent.action) {
            ACTION_SNOOZE -> {
                val due = scheduler.dueItem(kind, id)
                val minutes = due?.snoozeMinutes ?: store.settings().defaultSnoozeMinutes
                val count = store.snoozeCount(kind, id) + 1
                val until = System.currentTimeMillis() + minutes * 60_000L
                store.setSnooze(kind, id, until, count)
                scheduler.scheduleSnooze(kind, id, until)
            }

            ACTION_DISMISS, ACTION_SKIP -> {
                store.clearSnooze(kind, id)
                scheduler.cancelSnooze(kind, id)
                // A one-shot reminder answered from the notification is finished with; the caller
                // path (ChimeReceiver) retires it too, whichever arrives first.
                if (intent.action == ACTION_DISMISS) retireIfOneShot(context, store, kind, id)
            }
        }

        RingService.stop(context)
        scheduler.refreshNextUp()
    }

    private fun retireIfOneShot(context: Context, store: ChimeStore, kind: DueItem.Kind, id: Long) {
        when (kind) {
            DueItem.Kind.ALARM -> store.alarms().firstOrNull { it.id == id }?.let { alarm ->
                if (!alarm.isRepeating) store.setAlarmEnabled(id, false)
            }
            DueItem.Kind.REMINDER -> store.reminders().firstOrNull { it.id == id }?.let { reminder ->
                if (reminder.repeat == RepeatRule.ONCE) store.setReminderEnabled(id, false)
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.sylestia.toolhubs.chime.ACTION_SNOOZE"
        const val ACTION_DISMISS = "com.sylestia.toolhubs.chime.ACTION_DISMISS"
        const val ACTION_SKIP = "com.sylestia.toolhubs.chime.ACTION_SKIP"
        const val EXTRA_KIND = ChimeReceiver.EXTRA_KIND
        const val EXTRA_ID = ChimeReceiver.EXTRA_ID
    }
}

/**
 * Re-arms everything after a reboot, an app update or a clock change.
 *
 * `AlarmManager` forgets every pending alarm on reboot and a timezone jump moves every wall-clock
 * alarm, so both events have to re-derive the schedule from the store. This is the reason the store,
 * not the alarm manager, is the source of truth.
 */
class ChimeBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            runCatching {
                ChimeNotifications.ensureChannels(context)
                val scheduler = ChimeScheduler(context)
                scheduler.scheduleAll()
                scheduler.refreshNextUp()
            }
            pending.finish()
        }
    }
}
