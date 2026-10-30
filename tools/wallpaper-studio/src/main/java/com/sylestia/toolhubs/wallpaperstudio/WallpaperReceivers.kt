package com.sylestia.toolhubs.wallpaperstudio

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import java.time.ZonedDateTime
import kotlin.concurrent.thread

class WallpaperEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            Intent.ACTION_USER_PRESENT -> WallpaperEvent.UNLOCKED
            Intent.ACTION_POWER_CONNECTED -> WallpaperEvent.CHARGING
            Intent.ACTION_POWER_DISCONNECTED -> WallpaperEvent.UNPLUGGED
            Intent.ACTION_BATTERY_LOW -> WallpaperEvent.LOW_BATTERY
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                if (WallpaperEngine.isWifiConnected(context)) {
                    WallpaperEvent.WIFI_CONNECTED
                } else {
                    WallpaperEvent.WIFI_DISCONNECTED
                }
            }
            else -> return
        }

        val pending = goAsync()
        thread(name = "wallpaper-event") {
            try {
                WallpaperEngine.applyForEvent(context, event)
            } finally {
                pending.finish()
            }
        }
    }
}

class WallpaperTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scenarioId = intent.getStringExtra(WallpaperScheduler.EXTRA_SCENARIO_ID) ?: return
        val pending = goAsync()
        thread(name = "wallpaper-time") {
            try {
                val scenario = WallpaperStore(context).scenario(scenarioId)
                if (scenario?.enabled == true && scenario.trigger == WallpaperTrigger.TIME) {
                    WallpaperEngine.applyScenario(context, scenarioId)
                }
            } finally {
                runCatching {
                    WallpaperStore(context).scenario(scenarioId)?.let {
                        if (it.enabled && it.trigger == WallpaperTrigger.TIME) {
                            WallpaperScheduler(context).schedule(it)
                        }
                    }
                }
                pending.finish()
            }
        }
    }
}

class WallpaperBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WallpaperScheduler(context).refresh()
        WallpaperMonitoringService.sync(context, WallpaperStore(context).scenarios())
    }
}

class WallpaperScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun refresh() {
        val store = WallpaperStore(appContext)
        store.scenarios().forEach { scenario ->
            if (scenario.enabled && scenario.trigger == WallpaperTrigger.TIME) {
                schedule(scenario)
            } else {
                cancel(scenario.id)
            }
        }
    }

    fun schedule(scenario: WallpaperScenario) {
        val next = nextOccurrence(scenario.time) ?: return
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            next,
            pendingIntent(scenario.id),
        )
    }

    fun cancel(scenarioId: String) {
        alarmManager.cancel(pendingIntent(scenarioId))
    }

    private fun pendingIntent(scenarioId: String): PendingIntent {
        val intent = Intent(appContext, WallpaperTimeReceiver::class.java)
            .setAction(ACTION_TIME_SCENARIO)
            .putExtra(EXTRA_SCENARIO_ID, scenarioId)
        return PendingIntent.getBroadcast(
            appContext,
            scenarioId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun nextOccurrence(time: String): Long? {
        val parts = time.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        val now = ZonedDateTime.now()
        var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }

    companion object {
        const val ACTION_TIME_SCENARIO = "com.sylestia.toolhubs.wallpaperstudio.TIME_SCENARIO"
        const val EXTRA_SCENARIO_ID = "scenario_id"
    }
}
