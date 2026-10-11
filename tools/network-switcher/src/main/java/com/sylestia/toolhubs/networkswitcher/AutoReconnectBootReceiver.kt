package com.sylestia.toolhubs.networkswitcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** Restarts only the watcher the user explicitly left enabled, after radio startup settles. */
class AutoReconnectBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DELAYED_START) {
            ContextCompat.startForegroundService(context, Intent(context, AutoReconnectService::class.java).apply { action = AutoReconnectService.ACTION_BOOT_START })
            return
        }
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val isUpdate = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        val prefs = context.getSharedPreferences(AutoReconnectService.PREFS_NAME, Context.MODE_PRIVATE)
        // The watcher was running a moment ago when this is an update, so it comes back whatever the
        // start-after-reboot setting says. That setting answers "should this come back cold", and an
        // update is not a cold start. Requiring it here is what silently dropped the ongoing
        // notification on every install and left the user toggling the watcher off and on again.
        if (!prefs.getBoolean(AutoReconnectService.PREF_AUTO_ENABLED, false)) return
        if (!isUpdate && !prefs.getBoolean(AutoReconnectService.PREF_START_AFTER_REBOOT, false)) return
        val history = prefs.getStringSet(AutoReconnectService.PREF_HISTORY, emptySet()).orEmpty().toMutableSet()
        history.add(
            org.json.JSONObject()
                .put("time", System.currentTimeMillis())
                .put("network", "System")
                .put(
                    "detail",
                    if (isUpdate) {
                        "Restoring the watcher after an app update"
                    } else {
                        "Waiting for radio/Shizuku after restart"
                    },
                )
                .put("type", "system")
                .toString()
        )
        prefs.edit().putStringSet(AutoReconnectService.PREF_HISTORY, history).apply()
        val delayed = PendingIntent.getBroadcast(context, 705, Intent(context, AutoReconnectBootReceiver::class.java).setAction(ACTION_DELAYED_START), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // A reboot has to wait for the radio stack and Shizuku to come up. An update is over in
        // milliseconds, so the notification belongs back almost immediately.
        val delay = if (isUpdate) UPDATE_DELAY_MS else BOOT_DELAY_MS
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, delayed)
    }

    companion object {
        const val ACTION_DELAYED_START = "com.sylestia.toolhubs.networkswitcher.DELAYED_BOOT_START"
        private const val BOOT_DELAY_MS = 30_000L
        private const val UPDATE_DELAY_MS = 5_000L
    }
}
