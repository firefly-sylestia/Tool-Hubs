package com.sylestia.toolhubs.networkswitcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
        const val CHANNEL_ID = "network_switcher"
        const val NOTIFICATION_ID = 501
        const val BURST_LIMIT = 2
        const val BURST_WINDOW_MS = 60_000L
        const val COOLDOWN_MS = 20_000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var telephony: TelephonyManager
    private lateinit var bridge: NetworkSwitcherBridge
    private var lastNetwork = TelephonyManager.NETWORK_TYPE_UNKNOWN
    private var operationRunning = false
    private val attemptTimes = ArrayDeque<Long>()

    override fun onCreate() {
        super.onCreate()
        telephony = getSystemService(TelephonyManager::class.java)
        bridge = NetworkSwitcherBridge(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Watching for 5G drops"))
        startWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startWatcher() {
        scope.launch {
            while (isActive) {
                val current = runCatching { telephony.dataNetworkType }
                    .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)

                if (current == TelephonyManager.NETWORK_TYPE_LTE &&
                    lastNetwork != TelephonyManager.NETWORK_TYPE_LTE &&
                    !operationRunning
                ) {
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
            updateNotification("Reconnect paused · 2 attempts used in the last minute")
            delay(BURST_WINDOW_MS - (now - attemptTimes.first()))
            return
        }

        operationRunning = true
        attemptTimes.addLast(now)
        val attempt = attemptTimes.size

        updateNotification("Re-triggering 5G · burst $attempt/2")

        val result = withContext(Dispatchers.IO) {
            bridge.reconnect5G()
        }

        if (result.startsWith("5G re-selection triggered")) {
            notify("5G reconnect triggered", "Automatic reconnect completed · burst $attempt/2.")
            updateNotification("Watching for 5G drops · $attempt/2 used")
        } else {
            notify("5G reconnect failed", result)
            updateNotification("Reconnect failed · watching for next LTE drop")
        }

        operationRunning = false
        delay(COOLDOWN_MS)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "5G Reconnect",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Automatic 5G reconnect status and failures"
            }
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_connected)
            .setContentTitle("5G Reconnect")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(text)
        )
    }

    private fun notify(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(
            title.hashCode() + System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_connected)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
