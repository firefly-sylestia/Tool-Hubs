package com.sylestia.toolhubs.chime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sylestia.toolhubs.chime.ring.RingingActivity
import com.sylestia.toolhubs.chime.schedule.ChimeActionReceiver

/**
 * The two notifications Chime is allowed to post.
 *
 * The ringing one is deliberately loud in every sense: alarm category, public visibility so it
 * survives a locked screen, and a full-screen intent so the phone shows the alarm rather than a
 * banner. The countdown one is the opposite — silent, low importance, and driven by the platform
 * chronometer so it ticks without a single line of background work.
 */
object ChimeNotifications {

    const val CHANNEL_ALARMS = "chime_alarms"
    const val CHANNEL_NEXT = "chime_next"
    const val RINGING_ID = 4101
    const val NEXT_ID = 4102

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARMS,
                context.getString(R.string.channel_alarms),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_alarms_desc)
                // The channel stays silent and still because the ringing service owns both the sound
                // and the vibration: it ramps the alarm stream's own volume, which a channel sound
                // cannot do, and two vibrations fighting each other feel like a fault in the phone.
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEXT,
                context.getString(R.string.channel_next),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.channel_next_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    private fun ringIntent(context: Context, due: DueItem, action: String): PendingIntent {
        val intent = Intent(context, ChimeActionReceiver::class.java).apply {
            this.action = action
            putExtra(ChimeActionReceiver.EXTRA_KIND, due.kind.name)
            putExtra(ChimeActionReceiver.EXTRA_ID, due.id)
        }
        return PendingIntent.getBroadcast(
            context,
            ("$action-${due.kind}-${due.id}").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The notification that takes over the screen when something is due. */
    fun ringing(context: Context, due: DueItem): Notification {
        val fullScreen = PendingIntent.getActivity(
            context,
            ("ring-${due.id}").hashCode(),
            RingingActivity.intent(context, due),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (due.kind) {
            DueItem.Kind.ALARM -> due.title.ifBlank { "Alarm" }
            DueItem.Kind.REMINDER -> due.title
        }
        val dismissLabel = when (due.dismissMode) {
            DismissMode.SCAN -> "Scan to dismiss"
            DismissMode.SHAKE -> "Shake to dismiss"
            DismissMode.SHAKE_OR_SCAN -> "Shake or scan"
            DismissMode.BUTTON -> "Dismiss"
        }
        return NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_stat_chime)
            .setContentTitle(title)
            .setContentText("$dismissLabel · ${due.subtitle}")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(
                R.drawable.ic_action_snooze,
                "Snooze ${due.snoozeMinutes}m",
                ringIntent(context, due, ChimeActionReceiver.ACTION_SNOOZE),
            )
            .addAction(
                R.drawable.ic_action_dismiss,
                dismissLabel,
                ringIntent(context, due, ChimeActionReceiver.ACTION_DISMISS),
            )
            .build()
    }

    /**
     * The silent countdown to whatever is next.
     *
     * `setUsesChronometer` + `setChronometerCountDown` lets the system render "in 8h 12m" live, so
     * this notification stays accurate for hours without Chime running anything in the background.
     */
    fun nextUp(context: Context, due: DueItem): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_NEXT)
            .setSmallIcon(R.drawable.ic_stat_chime)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        val kind = if (due.kind == DueItem.Kind.ALARM) "Alarm" else "Reminder"
        // The countdown follows the same clock style as everything else; hard-coding 24-hour here is
        // how a 12-hour phone ended up with "Next up at 18:30" under a clock that said 6:30 PM.
        val hour24 = ChimeFormat.use24Hour(context, ChimeStore(context).settings().use24Hour)
        return builder
            .setContentTitle("$kind · ${due.title}".trim())
            .setContentText("Next up at ${ChimeFormat.clock(due.triggerAt, hour24)}")
            .setWhen(due.triggerAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .build()
    }

    fun post(context: Context, id: Int, notification: Notification) {
        if (!canPost(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }
}

/**
 * Small shared formatters so the clock, the notification and the ringing screen agree.
 *
 * The clock style is a stored preference that may be "follow the device", which is a third answer
 * rather than a default for one of the other two — a phone that flips to 24-hour style while
 * travelling should take Chime with it.
 */
object ChimeFormat {

    fun use24Hour(context: Context, preference: Boolean?): Boolean =
        preference ?: android.text.format.DateFormat.is24HourFormat(context)

    fun clock(millis: Long, hour24: Boolean = true): String =
        pattern(if (hour24) "HH:mm" else "h:mm a", millis)

    fun clockWithSeconds(millis: Long, hour24: Boolean = true): String =
        pattern(if (hour24) "HH:mm:ss" else "h:mm:ss a", millis)

    fun longDate(millis: Long): String = pattern("EEE d MMM", millis)

    private fun pattern(pattern: String, millis: Long): String =
        java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
            .format(java.util.Date(millis))
}
