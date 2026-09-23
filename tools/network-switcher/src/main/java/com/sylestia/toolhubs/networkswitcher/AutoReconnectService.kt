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
        const val LIMIT = 5
        private const val PREFS = "network_switcher"
        private const val KEY_COUNT = "daily_count"
        private const val KEY_DAY = "daily_day"
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var telephony: TelephonyManager
    private lateinit var bridge: NetworkSwitcherBridge
    private var lastNetwork = TelephonyManager.NETWORK_TYPE_UNKNOWN
    private var operationRunning = false

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
        if (dailyCount() >= LIMIT) {
            notify("Daily limit reached", "5G reconnect has reached " + LIMIT + " automatic attempts today.")
            updateNotification("Daily limit reached · watching only")
            delay(30_000)
            return
        }

        operationRunning = true
        val attempt = incrementDailyCount()
        updateNotification("Re-triggering 5G · attempt " + attempt + "/" + LIMIT)

        val result = withContext(Dispatchers.IO) {
            bridge.reconnect5G()
        }

        if (result.startsWith("5G re-selection triggered")) {
            notify("5G reconnect triggered", "Automatic attempt " + attempt + "/" + LIMIT + " completed.")
            updateNotification("Watching for 5G drops · " + attempt + "/" + LIMIT + " used today")
        } else {
            notify("5G reconnect failed", result)
            updateNotification("Reconnect failed · watching for next LTE drop")
        }

        operationRunning = false
        delay(20_000)
    }

    private fun todayKey(): String {
        val calendar = java.util.Calendar.getInstance()
        return calendar.get(java.util.Calendar.YEAR).toString() + "-" +
            calendar.get(java.util.Calendar.DAY_OF_YEAR).toString()
    }

    private fun dailyCount(): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString(KEY_DAY, null) != todayKey()) return 0
        return prefs.getInt(KEY_COUNT, 0)
    }

    private fun incrementDailyCount(): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val count = if (prefs.getString(KEY_DAY, null) == todayKey()) {
            dailyCount() + 1
        } else {
            1
        }
        prefs.edit()
            .putString(KEY_DAY, todayKey())
            .putInt(KEY_COUNT, count)
            .apply()
        return count
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
            title.hashCode(),
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
