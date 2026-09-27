package com.sylestia.toolhubs.networkswitcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/** Restarts only the watcher the user explicitly left enabled. */
class AutoReconnectBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val prefs = context.getSharedPreferences(AutoReconnectService.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(AutoReconnectService.PREF_AUTO_ENABLED, false) ||
            !prefs.getBoolean(AutoReconnectService.PREF_START_AFTER_REBOOT, false)) return
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            ContextCompat.startForegroundService(context, Intent(context, AutoReconnectService::class.java).apply { action = AutoReconnectService.ACTION_START })
            pending.finish()
        }, 30_000L)
    }
}
