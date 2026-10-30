package com.sylestia.toolhubs.wallpaperstudio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat

/**
 * Listens only while at least one event-driven scene is enabled.
 *
 * Android 8+ does not deliver several implicit broadcasts to manifest receivers, so the receiver
 * must live in a process the system keeps alive. The user-visible special-use foreground service
 * is started on demand and stopped as soon as all event scenes are paused or removed.
 */
class WallpaperMonitoringService : Service() {
    private val eventReceiver = WallpaperEventReceiver()
    private var receiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (!hasEventScenes()) {
            stopSelf()
            return
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wallpaper)
            .setContentTitle("Wallpaper Studio")
            .setContentText("Scenario automation is active")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    100,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(eventReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(eventReceiver, filter)
            }
            receiverRegistered = true
        }.onFailure { error ->
            WallpaperStore(this).recordFailure(
                "Event monitor could not register system events: ${error.message ?: "permission denied"}",
            )
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasEventScenes()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(eventReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasEventScenes(): Boolean = WallpaperStore(this).scenarios().any {
        it.enabled && it.trigger != WallpaperTrigger.TIME
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wallpaper automation",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while event-based wallpaper scenes are enabled."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "wallpaper_automation"
        private const val NOTIFICATION_ID = 4102

        fun sync(context: Context, scenarios: List<WallpaperScenario>) {
            val appContext = context.applicationContext
            val shouldRun = scenarios.any {
                it.enabled && it.trigger != WallpaperTrigger.TIME
            }
            val intent = Intent(appContext, WallpaperMonitoringService::class.java)
            if (shouldRun) {
                runCatching {
                    ContextCompat.startForegroundService(appContext, intent)
                }.onFailure { error ->
                    WallpaperStore(appContext).recordFailure(
                        "Android blocked the event monitor from starting. Open Wallpaper Studio to resume it. ${error.message ?: ""}",
                    )
                }
            } else {
                appContext.stopService(intent)
            }
        }
    }
}
