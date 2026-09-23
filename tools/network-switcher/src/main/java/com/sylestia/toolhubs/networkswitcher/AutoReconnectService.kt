package com.sylestia.toolhubs.networkswitcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
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
        const val BURST_WINDOW_MS = 60_000L
        const val PREFS_NAME = "network_switcher"
        const val PREF_INTERVAL_MS = "trigger_interval_ms"
        const val PREF_MAX_ATTEMPTS = "max_attempts_per_minute"
        const val DEFAULT_INTERVAL_MS = 30_000L
        const val MIN_INTERVAL_MS = 30_000L
        const val MAX_INTERVAL_MS = 600_000L
        const val DEFAULT_MAX_ATTEMPTS = 2
        const val MIN_MAX_ATTEMPTS = 1
        const val MAX_MAX_ATTEMPTS = 10
        @Volatile var isRunning = false
            private set
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var telephony: TelephonyManager
    private lateinit var bridge: NetworkSwitcherBridge
    private var operationRunning = false
    private var paused = false
    private var display5G = false
    private val attemptTimes = ArrayDeque<Long>()
    private var triggerIntervalMs = DEFAULT_INTERVAL_MS
    private var maxAttempts = DEFAULT_MAX_ATTEMPTS
    private var nextAttemptAt = 0L
    private var lastLimiterNotificationAt = 0L

    private val phoneStateListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        object : PhoneStateListener() {
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                display5G = info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                    info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
            }
        }
    } else {
        null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private val telephonyCallback = object : android.telephony.TelephonyCallback(),
        android.telephony.TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
            display5G = info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        telephony = getSystemService(TelephonyManager::class.java)
        bridge = NetworkSwitcherBridge(this)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        triggerIntervalMs = prefs.getLong(PREF_INTERVAL_MS, DEFAULT_INTERVAL_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        maxAttempts = prefs.getInt(PREF_MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS)
            .coerceIn(MIN_MAX_ATTEMPTS, MAX_MAX_ATTEMPTS)

        registerNetworkObserver()
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
                nextAttemptAt = 0L
                updateNotification("Watching for 5G drops")
            }
        }
        return START_STICKY
    }

    private fun registerNetworkObserver() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephony.registerTelephonyCallback(mainExecutor, telephonyCallback)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                telephony.listen(
                    phoneStateListener,
                    PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED
                )
            }
        }
    }

    private fun unregisterNetworkObserver() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephony.unregisterTelephonyCallback(telephonyCallback)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                telephony.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    private fun isActually5G(): Boolean {
        val dataType = runCatching { telephony.dataNetworkType }
            .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        return dataType == TelephonyManager.NETWORK_TYPE_NR || display5G
    }

    private fun startWatcher() {
        scope.launch {
            while (isActive) {
                if (!paused && !operationRunning && !isActually5G() &&
                    System.currentTimeMillis() >= nextAttemptAt
                ) {
                    attemptReconnect()
                }
                delay(1_000)
            }
        }
    }

    private suspend fun attemptReconnect() {
        val now = System.currentTimeMillis()
        while (attemptTimes.isNotEmpty() && now - attemptTimes.first() >= BURST_WINDOW_MS) {
            attemptTimes.removeFirst()
        }

        if (attemptTimes.size >= maxAttempts) {
            nextAttemptAt = attemptTimes.first() + BURST_WINDOW_MS
            if (now - lastLimiterNotificationAt >= 5_000L) {
                updateNotification("Attempt limit reached · " + maxAttempts + " used in the last minute")
                lastLimiterNotificationAt = now
            }
            return
        }

        operationRunning = true
        attemptTimes.addLast(now)
        val attempt = attemptTimes.size
        updateNotification("Trying 5G · attempt " + attempt + "/" + maxAttempts)

        val result = withContext(Dispatchers.IO) {
            bridge.reconnect5G()
        }

        val switchedTo5G = waitFor5G(12_000L)

        if (switchedTo5G) {
            nextAttemptAt = 0L
            updateNotification("5G switch confirmed · attempt " + attempt + "/" + maxAttempts)
        } else {
            nextAttemptAt = System.currentTimeMillis() + triggerIntervalMs
            val detail = if (result.startsWith("5G re-selection triggered")) {
                "5G not detected after trigger"
            } else {
                result.take(100)
            }
            updateNotification(
                "5G switch failed · retry in " + formatInterval(triggerIntervalMs) + " · " + detail
            )
        }

        operationRunning = false
    }

    private suspend fun waitFor5G(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isActually5G()) return true
            delay(500)
        }
        return isActually5G()
    }

    private fun formatInterval(ms: Long): String {
        val seconds = (ms / 1_000L).toInt()
        return if (seconds % 60 == 0) (seconds / 60).toString() + " min"
        else seconds.toString() + "s"
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

    override fun onDestroy() {
        isRunning = false
        unregisterNetworkObserver()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
