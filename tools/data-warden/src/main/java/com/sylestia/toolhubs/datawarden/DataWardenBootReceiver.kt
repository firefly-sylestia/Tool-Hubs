package com.sylestia.toolhubs.datawarden

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

class DataWardenBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(DataWardenService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("background_enabled", false)) return
        val serviceIntent = Intent(context, DataWardenService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, serviceIntent)
        else context.startService(serviceIntent)
    }
}
