package com.sylestia.toolhubs.networkswitcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts only the watcher the user explicitly left enabled. */
class AutoReconnectBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (!context.getSharedPreferences(AutoReconnectService.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(AutoReconnectService.PREF_AUTO_ENABLED, false)) return
        ContextCompat.startForegroundService(context, Intent(context, AutoReconnectService::class.java).apply {
            action = AutoReconnectService.ACTION_START
        })
    }
}
