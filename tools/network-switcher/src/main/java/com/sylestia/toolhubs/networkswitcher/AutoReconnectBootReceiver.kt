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
        val prefs = context.getSharedPreferences(AutoReconnectService.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(AutoReconnectService.PREF_AUTO_ENABLED, false) || !prefs.getBoolean(AutoReconnectService.PREF_START_AFTER_REBOOT, false)) return
        val history = prefs.getStringSet(AutoReconnectService.PREF_HISTORY, emptySet()).orEmpty().toMutableSet()
        history.add(org.json.JSONObject().put("time", System.currentTimeMillis()).put("network", "System").put("detail", "Waiting for radio/Shizuku after restart").put("type", "system").toString())
        prefs.edit().putStringSet(AutoReconnectService.PREF_HISTORY, history).apply()
        val delayed = PendingIntent.getBroadcast(context, 705, Intent(context, AutoReconnectBootReceiver::class.java).setAction(ACTION_DELAYED_START), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + BOOT_DELAY_MS, delayed)
    }

    companion object {
        const val ACTION_DELAYED_START = "com.sylestia.toolhubs.networkswitcher.DELAYED_BOOT_START"
        private const val BOOT_DELAY_MS = 30_000L
    }
}
