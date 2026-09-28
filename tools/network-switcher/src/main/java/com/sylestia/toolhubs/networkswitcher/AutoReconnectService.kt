package com.sylestia.toolhubs.networkswitcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.telephony.SubscriptionManager
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
        const val ACTION_BOOT_START = "com.sylestia.toolhubs.networkswitcher.BOOT_START"
        const val ACTION_STOP = "com.sylestia.toolhubs.networkswitcher.STOP"
        const val ACTION_PAUSE = "com.sylestia.toolhubs.networkswitcher.PAUSE"
        const val ACTION_RESUME = "com.sylestia.toolhubs.networkswitcher.RESUME"
        const val CHANNEL_ID = "network_switcher"
        const val NOTIFICATION_ID = 501
        const val BURST_WINDOW_MS = 60_000L
        const val PREFS_NAME = "network_switcher"
        const val PREF_INTERVAL_MS = "trigger_interval_ms"
        const val PREF_MAX_ATTEMPTS = "max_attempts_per_recovery"
        const val PREF_RECOVERY_COOLDOWN_MS = "recovery_cooldown_ms"
        const val PREF_AUTO_ENABLED = "auto_reconnect_enabled"
        const val PREF_START_AFTER_REBOOT = "start_after_reboot"
        const val PREF_HISTORY = "network_history"
        const val PREF_NEXT_ATTEMPT = "next_attempt_at_elapsed"
        const val PREF_FAILURE_STREAK = "failure_streak"
        const val PREF_CIRCUIT_OPEN = "circuit_open"
        const val PREF_SELECTED_SUBSCRIPTION = "selected_subscription"
        const val PREF_FAILOVER_ENABLED = "sim_failover_enabled"
        const val PREF_FAILOVER_TARGET = "sim_failover_target"
        const val PREF_LAST_FAILOVER = "last_sim_failover"
        const val PREF_FALLBACK_STARTED = "fallback_started_elapsed"
        const val PREF_LTE_FALLBACK_MS = "lte_fallback_ms"
        const val PREF_RECOVERY_TOTAL_MS = "recovery_total_ms"
        const val PREF_RECOVERY_COUNT = "recovery_count"
        const val PREF_LTE_FALLBACK_COUNT = "lte_fallback_count"
        const val PREF_TRIGGER_RECOVERY_TOTAL_MS = "trigger_recovery_total_ms"
        const val PREF_TRIGGER_RECOVERY_COUNT = "trigger_recovery_count"
        const val PREF_ATTEMPT_STARTED_ELAPSED = "attempt_started_elapsed"
        const val PREF_SERVICE_ACTIVE = "service_active"
        const val PREF_CURRENT_NETWORK = "current_network"
        const val PREF_STATUS = "service_status"
        const val PREF_LAST_EVENT_DETAIL = "last_event_detail"
        const val PREF_LAST_EVENT_AT = "last_event_at"
        const val PREF_BILLING_DAY = "billing_cycle_day"
        const val MAX_HISTORY = 200
        const val DEFAULT_INTERVAL_MS = 30_000L
        const val MIN_INTERVAL_MS = 30_000L
        const val MAX_INTERVAL_MS = 600_000L
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val MIN_MAX_ATTEMPTS = 1
        const val MAX_MAX_ATTEMPTS = 8
        const val DEFAULT_RECOVERY_COOLDOWN_MS = 5 * 60_000L
        const val MIN_RECOVERY_COOLDOWN_MS = 1 * 60_000L
        const val MAX_RECOVERY_COOLDOWN_MS = 30 * 60_000L
        const val NO_SERVICE_FAILOVER_DELAY_MS = 45_000L
        const val SIM_FAILOVER_COOLDOWN_MS = 15 * 60_000L
        @Volatile var isRunning = false
            private set
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var telephony: TelephonyManager
    private lateinit var baseTelephony: TelephonyManager
    private lateinit var bridge: NetworkSwitcherBridge
    private lateinit var usageTracker: NetworkUsageTracker
    private var operationRunning = false
    private var paused = false
    private var display5G = false
    private val attemptTimes = ArrayDeque<Long>()
    private var triggerIntervalMs = DEFAULT_INTERVAL_MS
    private var maxAttempts = DEFAULT_MAX_ATTEMPTS
    private var recoveryCooldownMs = DEFAULT_RECOVERY_COOLDOWN_MS
    private var nextAttemptAt = 0L
    private var consecutiveFailures = 0
    private var circuitOpen = false
    private var lastLimiterNotificationAt = 0L
    private var lastObservedNetwork: String? = null
    private var noServiceSince = 0L
    private var lastShizukuHealth: ShizukuHealth? = null

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

    private val telephonyCallback by lazy { createTelephonyCallback() }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createTelephonyCallback(): android.telephony.TelephonyCallback = object : android.telephony.TelephonyCallback(),
        android.telephony.TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
            display5G = info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
        }
    }

    private fun reloadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        triggerIntervalMs = prefs.getLong(PREF_INTERVAL_MS, DEFAULT_INTERVAL_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        maxAttempts = prefs.getInt(PREF_MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS)
            .coerceIn(MIN_MAX_ATTEMPTS, MAX_MAX_ATTEMPTS)
        recoveryCooldownMs = prefs.getLong(PREF_RECOVERY_COOLDOWN_MS, DEFAULT_RECOVERY_COOLDOWN_MS)
            .coerceIn(MIN_RECOVERY_COOLDOWN_MS, MAX_RECOVERY_COOLDOWN_MS)
        val nowElapsed = SystemClock.elapsedRealtime()
        val persistedNext = prefs.getLong(PREF_NEXT_ATTEMPT, 0L)
        nextAttemptAt = if (persistedNext > nowElapsed) persistedNext else 0L
        consecutiveFailures = prefs.getInt(PREF_FAILURE_STREAK, 0)
        circuitOpen = prefs.getBoolean(PREF_CIRCUIT_OPEN, false)
        val selectedSub = prefs.getInt(PREF_SELECTED_SUBSCRIPTION, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        telephony = if (selectedSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) baseTelephony.createForSubscriptionId(selectedSub) else baseTelephony
    }

    private fun persistRetryState() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
        .putLong(PREF_NEXT_ATTEMPT, nextAttemptAt)
        .putInt(PREF_FAILURE_STREAK, consecutiveFailures)
        .putBoolean(PREF_CIRCUIT_OPEN, circuitOpen)
        .apply()

    private fun publishState(network: String, status: String, event: String? = null) {
        val editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_SERVICE_ACTIVE, isRunning)
            .putString(PREF_CURRENT_NETWORK, network)
            .putString(PREF_STATUS, status)
        if (event != null) {
            editor.putString(PREF_LAST_EVENT_DETAIL, event)
                .putLong(PREF_LAST_EVENT_AT, System.currentTimeMillis())
        }
        editor.apply()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        baseTelephony = getSystemService(TelephonyManager::class.java)
        telephony = baseTelephony
        bridge = NetworkSwitcherBridge(this)
        ShizukuHealthMonitor.install()
        usageTracker = NetworkUsageTracker(this)

        reloadSettings()
        registerNetworkObserver()
        createChannel()
        publishState(currentNetworkLabel(), "Starting background watcher…")
        startForeground(NOTIFICATION_ID, buildNotification("Starting background watcher…"))
        startWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && !getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_AUTO_ENABLED, false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(PREF_AUTO_ENABLED, true).apply()
                unregisterNetworkObserver()
                reloadSettings()
                registerNetworkObserver()
                paused = false
                circuitOpen = false
                consecutiveFailures = 0
                nextAttemptAt = 0L
                persistRetryState()
                publishState(currentNetworkLabel(), "Background watcher started")
            }
            ACTION_BOOT_START -> {
                unregisterNetworkObserver()
                reloadSettings()
                registerNetworkObserver()
            }
            ACTION_STOP -> {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(PREF_AUTO_ENABLED, false)
                    .putBoolean(PREF_SERVICE_ACTIVE, false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                paused = true
                publishState(currentNetworkLabel(), "Paused · tap Resume to continue")
                updateNotification("Paused · tap Resume to continue")
            }
            ACTION_RESUME -> {
                paused = false
                circuitOpen = false
                consecutiveFailures = 0
                nextAttemptAt = 0L
                persistRetryState()
                val status = when {
                    isActually5G() -> "5G active · monitoring for LTE fallback"
                    isLteFallback() -> "4G / LTE detected · recovery active"
                    else -> "Monitoring cellular state"
                }
                publishState(currentNetworkLabel(), status)
                updateNotification(status)
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
                telephony.listen(phoneStateListener, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
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

    private fun currentNetworkLabel(): String = when {
        isActually5G() -> "5G"
        currentDataType() == TelephonyManager.NETWORK_TYPE_LTE -> "4G / LTE"
        currentDataType() == TelephonyManager.NETWORK_TYPE_HSPAP ||
            currentDataType() == TelephonyManager.NETWORK_TYPE_HSPA ||
            currentDataType() == TelephonyManager.NETWORK_TYPE_HSDPA ||
            currentDataType() == TelephonyManager.NETWORK_TYPE_HSUPA ||
            currentDataType() == TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        currentDataType() == TelephonyManager.NETWORK_TYPE_EDGE ||
            currentDataType() == TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        else -> "Unknown"
    }

    private fun currentDataType(): Int =
        runCatching { telephony.dataNetworkType }
            .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)

    private fun isActually5G(): Boolean {
        return currentDataType() == TelephonyManager.NETWORK_TYPE_NR || display5G
    }

    private fun isLteFallback(): Boolean {
        return currentDataType() == TelephonyManager.NETWORK_TYPE_LTE && !isActually5G()
    }

    private fun recordHistory(network: String, detail: String = "", type: String = "system") {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val entry = org.json.JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("network", network)
            put("detail", detail)
            put("type", type)
        }.toString()
        val history = prefs.getStringSet(PREF_HISTORY, emptySet()).orEmpty().toMutableSet()
        // Set is only used as compact persistence; the timestamp makes every entry unique.
        history.add(entry)
        while (history.size > MAX_HISTORY) history.remove(history.minByOrNull { runCatching { org.json.JSONObject(it).optLong("time") }.getOrDefault(Long.MAX_VALUE) } ?: break)
        val editor = prefs.edit().putStringSet(PREF_HISTORY, history)
        if (type == "success") editor.putInt("successful_attempts", prefs.getInt("successful_attempts", 0) + 1)
        if (type == "failed") editor.putInt("failed_attempts", prefs.getInt("failed_attempts", 0) + 1)
        editor.apply()
    }

    private fun observeNetwork() {
        val current = currentNetworkLabel()
        if (current == "5G") {
            consecutiveFailures = 0
            circuitOpen = false
            nextAttemptAt = 0L
            persistRetryState()
        }
        if (current != lastObservedNetwork) {
            val detail = if (lastObservedNetwork == null) "Monitoring started" else "Network changed from $lastObservedNetwork"
            recordHistory(current, detail, if (lastObservedNetwork == null) "system" else "network")
            lastObservedNetwork = current
            publishState(current, when {
                current == "5G" -> "5G active · monitoring for LTE fallback"
                current == "4G / LTE" -> "4G / LTE detected · recovery watcher active"
                else -> "Monitoring $current"
            }, detail)
        }
        usageTracker.sample(current)
        trackFallbackImpact(current)
        val now = System.currentTimeMillis()
        if (current == "Unknown") {
            if (noServiceSince == 0L) noServiceSince = now
            maybeFailOverSim(now)
        } else noServiceSince = 0L
    }

    private fun trackFallbackImpact(current: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val nowElapsed = SystemClock.elapsedRealtime()
        val started = prefs.getLong(PREF_FALLBACK_STARTED, 0L)
        if (started > nowElapsed) {
            prefs.edit().remove(PREF_FALLBACK_STARTED).apply()
        }
        val validStarted = if (started > 0L && started <= nowElapsed) started else 0L
        if (current == "4G / LTE" && validStarted == 0L) {
            prefs.edit().putLong(PREF_FALLBACK_STARTED, nowElapsed).apply()
        } else if (current == "5G" && validStarted > 0L) {
            val duration = (nowElapsed - validStarted).coerceAtLeast(0L)
            prefs.edit().remove(PREF_FALLBACK_STARTED)
                .putLong(PREF_LTE_FALLBACK_MS, prefs.getLong(PREF_LTE_FALLBACK_MS, 0L) + duration)
                .putInt(PREF_LTE_FALLBACK_COUNT, prefs.getInt(PREF_LTE_FALLBACK_COUNT, 0) + 1).apply()
        }
    }

    private fun maybeFailOverSim(now: Long) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_FAILOVER_ENABLED, false) || now - noServiceSince < NO_SERVICE_FAILOVER_DELAY_MS) return
        if (now - prefs.getLong(PREF_LAST_FAILOVER, 0L) < SIM_FAILOVER_COOLDOWN_MS) return
        val target = prefs.getInt(PREF_FAILOVER_TARGET, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        if (target == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return
        prefs.edit().putLong(PREF_LAST_FAILOVER, now).apply()
        recordHistory("No service", "Switching mobile data to selected backup SIM", "sim_failover")
        scope.launch {
            val result = withContext(Dispatchers.IO) { bridge.switchDefaultDataSubscription(target) }
            recordHistory("SIM", result, if (result.startsWith("Data SIM switched")) "success" else "failed")
            updateNotification(result)
        }
    }

    private fun startWatcher() {
        scope.launch {
            while (isActive) {
                observeNetwork()
                observeShizukuHealth()
                if (circuitOpen && SystemClock.elapsedRealtime() >= nextAttemptAt) {
                    circuitOpen = false
                    consecutiveFailures = 0
                    persistRetryState()
                }
                if (!paused && !operationRunning && isLteFallback() &&
                    SystemClock.elapsedRealtime() >= nextAttemptAt
                ) {
                    attemptReconnect()
                }
                delay(1_000)
            }
        }
    }

    private fun observeShizukuHealth() {
        val health = ShizukuHealthMonitor.refresh()
        if (health == lastShizukuHealth) return
        lastShizukuHealth = health
        if (health == ShizukuHealth.SERVICE_DIED || health == ShizukuHealth.UNAVAILABLE) {
            recordHistory("Shizuku", "Background service unavailable; open Shizuku then tap Retry", "system")
            publishState(currentNetworkLabel(), "Shizuku unavailable · open it, then tap Retry")
            updateNotification("Shizuku unavailable · open it, then tap Retry")
        } else if (health == ShizukuHealth.READY && lastShizukuHealth != ShizukuHealth.READY) {
            publishState(currentNetworkLabel(), "Shizuku ready · monitoring continues")
            updateNotification("Shizuku ready · monitoring continues")
        }
    }

    private suspend fun attemptReconnect() {
        val now = SystemClock.elapsedRealtime()
        while (attemptTimes.isNotEmpty() && now - attemptTimes.first() >= BURST_WINDOW_MS) {
            attemptTimes.removeFirst()
        }

        if (attemptTimes.size >= maxAttempts) {
            nextAttemptAt = now + recoveryCooldownMs
            persistRetryState()
            if (now - lastLimiterNotificationAt >= 5_000L) {
                updateNotification("Recovery paused · " + maxAttempts + " attempts used. Retry in " + formatInterval(recoveryCooldownMs))
                lastLimiterNotificationAt = now
            }
            return
        }

        operationRunning = true
        try {
            attemptTimes.addLast(now)
        recordHistory("4G / LTE", "Automatic 5G reconnect attempt started", "attempt")
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putLong(PREF_ATTEMPT_STARTED_ELAPSED, SystemClock.elapsedRealtime())
                .apply()
            val attempt = attemptTimes.size
            updateNotification("Trying 5G · attempt " + attempt + "/" + maxAttempts)
            val result = withContext(Dispatchers.IO) { bridge.reconnect5G() }
            val switchedTo5G = waitFor5G(12_000L)
            if (switchedTo5G) {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val nowElapsed = SystemClock.elapsedRealtime()
                val started = prefs.getLong(PREF_ATTEMPT_STARTED_ELAPSED, 0L)
                val latency = if (started > 0L && started <= nowElapsed) (nowElapsed - started).coerceAtLeast(0L) else 0L
                val count = prefs.getInt(PREF_TRIGGER_RECOVERY_COUNT, 0)
                val total = prefs.getLong(PREF_TRIGGER_RECOVERY_TOTAL_MS, 0L)
                recordHistory("5G", "Automatic reconnect confirmed", "success")
                prefs.edit().remove(PREF_ATTEMPT_STARTED_ELAPSED)
                    .putLong(PREF_TRIGGER_RECOVERY_TOTAL_MS, total + latency)
                    .putInt(PREF_TRIGGER_RECOVERY_COUNT, count + 1).apply()
                nextAttemptAt = 0L; consecutiveFailures = 0; circuitOpen = false; persistRetryState()
                publishState("5G", "5G switch confirmed · attempt " + attempt + "/" + maxAttempts, "Automatic reconnect confirmed")
                updateNotification("5G switch confirmed · attempt " + attempt + "/" + maxAttempts)
            } else {
                consecutiveFailures++
                // Respect the user's configured base interval, then progressively back off.
                val delay = maxOf(triggerIntervalMs, when (consecutiveFailures) { 1 -> 30_000L; 2 -> 60_000L; 3 -> 300_000L; else -> 900_000L })
                circuitOpen = false
                nextAttemptAt = SystemClock.elapsedRealtime() + delay
                persistRetryState()
                val detail = if (result.startsWith("5G re-selection triggered")) "5G not detected after trigger" else result.take(100)
                recordHistory("4G / LTE", if (circuitOpen) "Reconnect paused after $consecutiveFailures failures: $detail" else "Reconnect failed: $detail", "failed")
                publishState("4G / LTE", if (circuitOpen) "Reconnect paused after repeated failures" else "4G / LTE detected · retry in " + formatInterval(delay), detail)
                updateNotification(if (circuitOpen) "Reconnect paused after repeated failures · tap Resume" else "5G switch failed · retry in " + formatInterval(delay) + " · " + detail)
            }
        } finally {
            operationRunning = false
        }
    }

    private suspend fun waitFor5G(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
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

        val openIntent = PendingIntent.getActivity(this, 700, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setContentTitle("5G Reconnect")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_media_pause, if (paused) "Resume" else "Pause", pauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
        if (ShizukuHealthMonitor.state == ShizukuHealth.UNAVAILABLE || ShizukuHealthMonitor.state == ShizukuHealth.SERVICE_DIED) {
            val shizukuIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (shizukuIntent != null) builder.addAction(android.R.drawable.ic_menu_manage, "Open Shizuku", PendingIntent.getActivity(this, 703, shizukuIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            builder.addAction(android.R.drawable.ic_popup_sync, "Retry", PendingIntent.getService(this, 704, Intent(this, AutoReconnectService::class.java).setAction(ACTION_RESUME), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_AUTO_ENABLED, false)) {
            updateNotification(when {
                isActually5G() -> "5G active · monitoring for LTE fallback"
                isLteFallback() -> "4G / LTE detected · recovery active"
                else -> "Monitoring cellular state"
            })
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { usageTracker.flush() }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_SERVICE_ACTIVE, false).apply()
        unregisterNetworkObserver()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
