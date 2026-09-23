package com.sylestia.toolhubs.networkswitcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AutoReconnectService : Service() {
    companion object {
        const val ACTION_START = "com.sylestia.toolhubs.networkswitcher.START"
        const val ACTION_STOP = "com.sylestia.toolhubs.networkswitcher.STOP"
        const val ACTION_PAUSE = "com.sylestia.toolhubs.networkswitcher.PAUSE"
        const val ACTION_RESUME = "com.sylestia.toolhubs.networkswitcher.RESUME"
        const val CHANNEL_ID = "network_switcher"
        const val NOTIFICATION_ID = 501
        const val BURST_LIMIT = 2
        const val BURST_WINDOW_MS = 60_000L
        const val COOLDOWN_MS = 20_000L
        @Volatile var isRunning = false
            private set
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var telephony: TelephonyManager
    private lateinit var bridge: NetworkSwitcherBridge
    private var lastNetwork = TelephonyManager.NETWORK_TYPE_UNKNOWN
    private var operationRunning = false
    private var paused = false
    private val attemptTimes = ArrayDeque<Long>()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        telephony = getSystemService(TelephonyManager::class.java)
        bridge = NetworkSwitcherBridge(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting 5G check…"))
        startWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                paused = true
                updateNotification("Paused · tap Resume to continue")
            }
            ACTION_RESUME -> {
                paused = false
                updateNotification("Watching for 5G drops")
            }
        }
        return START_STICKY
    }

    private fun startWatcher() {
        scope.launch {
            // Deliberately initialize from the current state, so enabling the
            // service while already on LTE immediately attempts 5G.
            lastNetwork = runCatching { telephony.dataNetworkType }
                .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)

            while (isActive) {
                val current = runCatching { telephony.dataNetworkType }
                    .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)

                if (!paused && current == TelephonyManager.NETWORK_TYPE_LTE && !operationRunning) {
                    // Try once when already on LTE, and again after the cooldown
                    // if LTE remains the active network, subject to the 2/min burst limit.
                    attemptReconnect()
                }

                lastNetwork = current
                delay(3_000)
            }
        }
    }

    private suspend fun attemptReconnect() {
        val now = System.currentTimeMillis()
        while (attemptTimes.isNotEmpty() && now - attemptTimes.first() >= BURST_WINDOW_MS) {
            attemptTimes.removeFirst()
        }

        if (attemptTimes.size >= BURST_LIMIT) {
            val wait = BURST_WINDOW_MS - (now - attemptTimes.first())
            updateNotification("Paused briefly · 2 reconnects used in the last minute")
            delay(wait.coerceAtLeast(1_000L))
            return
        }

        operationRunning = true
        attemptTimes.addLast(now)
        val attempt = attemptTimes.size

        updateNotification("Trying 5G · attempt $attempt/2")

        val result = withContext(Dispatchers.IO) {
            bridge.reconnect5G()
        }

        if (result.startsWith("5G re-selection triggered")) {
            notify("5G reconnect triggered", "Automatic attempt $attempt/2 completed.")
            updateNotification("Watching · $attempt/2 attempts used")
        } else {
            notify("5G reconnect failed", result)
            updateNotification("Reconnect failed · watching")
        }

        operationRunning = false
        delay(COOLDOWN_MS)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "5G Reconnect", NotificationManager.IMPORTANCE_LOW).apply {
                description = "5G reconnect background service"
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val pauseIntent = PendingIntent.getService(
            this, 701,
            Intent(this, AutoReconnectService::class.java).setAction(
                if (paused) ACTION_RESUME else ACTION_PAUSE
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 702,
            Intent(this, AutoReconnectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("5G Reconnect")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (paused) "Resume" else "Pause",
                pauseIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID, buildNotification(text)
        )
    }

    private fun notify(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(
            title.hashCode() + System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        )
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
