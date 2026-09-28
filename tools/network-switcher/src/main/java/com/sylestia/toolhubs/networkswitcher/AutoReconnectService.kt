package com.sylestia.toolhubs.networkswitcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground watcher that nudges the modem back onto 5G after an LTE fallback.
 *
 * Design notes for the retry machinery, because the previous revision was subtly broken:
 *
 *  * **Attempts are gated on Shizuku being READY.** A missing tool used to consume the retry
 *    budget and extend the back-off, so the watcher stayed parked for up to 15 minutes after the
 *    user fixed Shizuku. That is the "toggle it off and on again" symptom.
 *  * **The circuit breaker actually opens now.** The old code assigned `circuitOpen = false` in the
 *    failure branch, so the "paused after repeated failures" state was unreachable dead code.
 *  * **Back-off survives a reboot correctly.** It is stored against wall-clock time as well as
 *    `elapsedRealtime`, because `elapsedRealtime` restarts at zero on boot and the stale value made
 *    recovery wait for hours.
 *  * **The loop cannot die.** Every tick runs inside `runCatching`, so one platform exception can no
 *    longer cancel monitoring until the service is restarted.
 */
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
        const val PREF_NEXT_ATTEMPT_WALL = "next_attempt_at_wall"
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
        const val PREF_PAUSED = "service_paused"
        const val PREF_CURRENT_NETWORK = "current_network"
        const val PREF_STATUS = "service_status"
        const val PREF_LAST_EVENT_DETAIL = "last_event_detail"
        const val PREF_LAST_EVENT_AT = "last_event_at"
        const val PREF_BILLING_DAY = "billing_cycle_day"
        const val PREF_THEME_MODE = "theme_mode"
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

        /** How long we wait for a triggered re-selection to actually land on 5G. */
        const val SWITCH_CONFIRM_TIMEOUT_MS = 12_000L

        /** Longest single back-off, and the ceiling applied to a value restored after a reboot. */
        const val MAX_BACKOFF_MS = 900_000L

        /** Environment (Shizuku) problems must never cost the user minutes. */
        const val ENVIRONMENT_RETRY_DELAY_MS = 15_000L
        const val ENVIRONMENT_NOTICE_INTERVAL_MS = 30_000L

        private const val TICK_MS = 1_000L
        private const val HEALTH_REFRESH_INTERVAL_MS = 2_000L
        private const val LOOP_ERROR_LOG_INTERVAL_MS = 60_000L
        private const val RESULT_TRIGGERED = "5G re-selection triggered"

        @Volatile var isRunning = false
            private set
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var telephony: TelephonyManager
    private lateinit var baseTelephony: TelephonyManager
    private lateinit var bridge: NetworkSwitcherBridge
    private lateinit var usageTracker: NetworkUsageTracker

    @Volatile private var radioWatcher: RadioWatcher? = null

    @Volatile private var operationRunning = false
    @Volatile private var paused = false
    @Volatile private var lastObservedNetwork: String? = null
    @Volatile private var currentLabel: String = "Unknown"

    /**
     * Retry state is only ever mutated on the watcher thread. Anything arriving from the main
     * thread (start/resume, a recovered Shizuku) raises this flag instead of writing the fields
     * directly, which keeps the loop free of locking.
     */
    @Volatile private var resetRequested = false
    @Volatile private var resetReason = "Recovery armed"

    private val historyLock = Any()

    private var attemptTimes = ArrayDeque<Long>()
    private var triggerIntervalMs = DEFAULT_INTERVAL_MS
    private var maxAttempts = DEFAULT_MAX_ATTEMPTS
    private var recoveryCooldownMs = DEFAULT_RECOVERY_COOLDOWN_MS
    private var nextAttemptAt = 0L
    private var consecutiveFailures = 0
    private var circuitOpen = false
    private var lastEnvironmentNoticeAt = 0L
    private var lastHealthRefreshAt = 0L
    private var lastLoopErrorAt = 0L
    private var noServiceSince = 0L
    private var lastShizukuHealth: ShizukuHealth? = null

    private val healthListener: (ShizukuHealth) -> Unit = { health ->
        if (health == ShizukuHealth.READY) requestReset("Shizuku ready · recovery armed")
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        baseTelephony = getSystemService(TelephonyManager::class.java)
        telephony = baseTelephony
        bridge = NetworkSwitcherBridge(this)
        usageTracker = NetworkUsageTracker(this)

        ShizukuHealthMonitor.install()
        ShizukuHealthMonitor.addListener(healthListener)

        reloadSettings()
        // A START_STICKY recreation must come back in the same paused state the user left it in.
        paused = prefs().getBoolean(PREF_PAUSED, false)
        createRadioWatcher()
        createChannel()
        publishState(currentNetworkLabel(), "Starting background watcher…")
        startForeground(NOTIFICATION_ID, buildNotification("Starting background watcher…"))
        startWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && !prefs().getBoolean(PREF_AUTO_ENABLED, false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                prefs().edit().putBoolean(PREF_AUTO_ENABLED, true).apply()
                reloadSettings()
                createRadioWatcher()
                paused = false
                prefs().edit().putBoolean(PREF_PAUSED, false).apply()
                requestReset("Background watcher started")
                publishState(currentNetworkLabel(), "Background watcher started")
            }

            ACTION_BOOT_START -> {
                reloadSettings()
                createRadioWatcher()
            }

            ACTION_STOP -> {
                prefs().edit()
                    .putBoolean(PREF_AUTO_ENABLED, false)
                    .putBoolean(PREF_SERVICE_ACTIVE, false)
                    .apply()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PAUSE -> {
                paused = true
                prefs().edit().putBoolean(PREF_PAUSED, true).apply()
                publishState(currentNetworkLabel(), "Paused · tap Resume to continue")
                updateNotification("Paused · tap Resume to continue")
            }

            ACTION_RESUME -> {
                paused = false
                prefs().edit().putBoolean(PREF_PAUSED, false).apply()
                requestReset("Manual retry requested")
                ShizukuHealthMonitor.refresh()
                publishState(currentNetworkLabel(), statusForCurrent())
                updateNotification(statusForCurrent())
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs().getBoolean(PREF_AUTO_ENABLED, false)) updateNotification(statusForCurrent())
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { usageTracker.flush() }
        runCatching { radioWatcher?.stop() }
        runCatching { ShizukuHealthMonitor.removeListener(healthListener) }
        prefs().edit().putBoolean(PREF_SERVICE_ACTIVE, false).apply()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── settings ─────────────────────────────────────────────────────────────────────────────────

    private fun reloadSettings() {
        val prefs = prefs()
        triggerIntervalMs = prefs.getLong(PREF_INTERVAL_MS, DEFAULT_INTERVAL_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        maxAttempts = prefs.getInt(PREF_MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS)
            .coerceIn(MIN_MAX_ATTEMPTS, MAX_MAX_ATTEMPTS)
        recoveryCooldownMs = prefs.getLong(PREF_RECOVERY_COOLDOWN_MS, DEFAULT_RECOVERY_COOLDOWN_MS)
            .coerceIn(MIN_RECOVERY_COOLDOWN_MS, MAX_RECOVERY_COOLDOWN_MS)

        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val persistedWall = prefs.getLong(PREF_NEXT_ATTEMPT_WALL, 0L)
        val remaining = if (persistedWall > nowWall) persistedWall - nowWall else 0L
        nextAttemptAt = if (remaining <= 0L) 0L else nowElapsed + remaining.coerceAtMost(MAX_BACKOFF_MS)

        consecutiveFailures = prefs.getInt(PREF_FAILURE_STREAK, 0)
        circuitOpen = prefs.getBoolean(PREF_CIRCUIT_OPEN, false)

        val selectedSub = prefs.getInt(PREF_SELECTED_SUBSCRIPTION, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        telephony = if (selectedSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            runCatching { baseTelephony.createForSubscriptionId(selectedSub) }.getOrDefault(baseTelephony)
        } else {
            baseTelephony
        }
    }

    private fun persistRetryState() {
        val remaining = nextAttemptAt - SystemClock.elapsedRealtime()
        prefs().edit()
            .putLong(PREF_NEXT_ATTEMPT, nextAttemptAt)
            .putLong(PREF_NEXT_ATTEMPT_WALL, if (remaining > 0L) System.currentTimeMillis() + remaining else 0L)
            .putInt(PREF_FAILURE_STREAK, consecutiveFailures)
            .putBoolean(PREF_CIRCUIT_OPEN, circuitOpen)
            .apply()
    }

    /** Safe to call from any thread. Consumed by the watcher on its next tick. */
    private fun requestReset(reason: String) {
        resetReason = reason
        resetRequested = true
    }

    /** Watcher thread only. Clears every retry gate. */
    private fun applyReset() {
        consecutiveFailures = 0
        circuitOpen = false
        nextAttemptAt = 0L
        attemptTimes = ArrayDeque()
        persistRetryState()
        recordHistory(currentLabel, resetReason, "system")
    }

    private fun createRadioWatcher() {
        runCatching { radioWatcher?.stop() }
        radioWatcher = RadioWatcher(telephony, ContextCompat.getMainExecutor(this)).also { it.start() }
    }

    // ── watcher loop ─────────────────────────────────────────────────────────────────────────────

    private fun startWatcher() {
        scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { noteLoopError(it) }
                delay(TICK_MS)
            }
        }
    }

    private fun noteLoopError(error: Throwable) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLoopErrorAt < LOOP_ERROR_LOG_INTERVAL_MS) return
        lastLoopErrorAt = now
        recordHistory(currentLabel, "Watcher absorbed ${error.javaClass.simpleName}: ${error.message ?: "no detail"}", "system")
    }

    private suspend fun tick() {
        val now = SystemClock.elapsedRealtime()
        refreshHealth(now)
        if (resetRequested) {
            resetRequested = false
            applyReset()
        }
        val radio = radioWatcher?.snapshot() ?: return
        observeNetwork(radio)
        maybeRecover(radio, now)
    }

    private fun refreshHealth(now: Long) {
        if (now - lastHealthRefreshAt < HEALTH_REFRESH_INTERVAL_MS) return
        lastHealthRefreshAt = now

        val health = ShizukuHealthMonitor.refresh()
        if (health == lastShizukuHealth) return
        val previous = lastShizukuHealth
        lastShizukuHealth = health

        if (health == ShizukuHealth.READY) {
            if (previous != ShizukuHealth.READY) {
                // Routed through the same request flag the binder listener uses, so the two paths
                // cannot double-log the same recovery.
                requestReset("Shizuku ready · recovery armed")
                publishState(currentLabel, "Shizuku ready · monitoring continues")
                updateNotification("Shizuku ready · monitoring continues")
            }
        } else {
            val note = health.describe() + " · recovery paused"
            recordHistory("Shizuku", note, "system")
            publishState(currentLabel, note)
            updateNotification(note)
        }
    }

    private suspend fun maybeRecover(radio: RadioState, now: Long) {
        // Probe once the cooldown elapses so a paused recovery is never permanent.
        if (circuitOpen && now >= nextAttemptAt) {
            circuitOpen = false
            consecutiveFailures = 0
            attemptTimes = ArrayDeque()
            persistRetryState()
            recordHistory(radio.label, "Recovery resumed after cooldown", "system")
        }

        if (paused || operationRunning) return
        if (!radio.isLteFallback) return
        if (now < nextAttemptAt) return

        if (!ShizukuHealthMonitor.isReady) {
            if (now - lastEnvironmentNoticeAt >= ENVIRONMENT_NOTICE_INTERVAL_MS) {
                lastEnvironmentNoticeAt = now
                val note = ShizukuHealthMonitor.state.describe() + " · recovery paused"
                publishState(radio.label, note)
                updateNotification(note)
            }
            return
        }

        attemptReconnect(now)
    }

    // ── recovery ─────────────────────────────────────────────────────────────────────────────────

    private fun pruneBurstWindow(now: Long) {
        while (attemptTimes.isNotEmpty() && now - attemptTimes.first() >= BURST_WINDOW_MS) {
            attemptTimes.removeFirst()
        }
    }

    private suspend fun attemptReconnect(now: Long) {
        pruneBurstWindow(now)

        if (attemptTimes.size >= maxAttempts) {
            pauseRecovery(now, "Recovery paused · retrying shortly")
            return
        }

        operationRunning = true
        val startedAt = SystemClock.elapsedRealtime()
        val attempt = attemptTimes.size + 1
        try {
            attemptTimes.addLast(startedAt)
            prefs().edit().putLong(PREF_ATTEMPT_STARTED_ELAPSED, startedAt).apply()
            recordHistory("4G / LTE", "Automatic 5G reconnect attempt started", "attempt")
            updateNotification("Trying 5G · attempt $attempt/$maxAttempts")

            val result = withContext(Dispatchers.IO) { bridge.reconnect5G() }
            val confirmed = waitFor5G(SWITCH_CONFIRM_TIMEOUT_MS)

            when {
                confirmed -> onRecoveryConfirmed(startedAt, attempt)
                NetworkSwitcherBridge.isEnvironmentFailure(result) -> onEnvironmentFailure(result)
                else -> onRadioFailure(if (result.startsWith(RESULT_TRIGGERED)) "5G not detected after trigger" else result)
            }
        } catch (t: Throwable) {
            onRadioFailure("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
        } finally {
            operationRunning = false
        }
    }

    private fun onRecoveryConfirmed(startedAt: Long, attempt: Int) {
        val store = prefs()
        val nowElapsed = SystemClock.elapsedRealtime()
        val latency = (nowElapsed - startedAt).coerceAtLeast(0L)
        store.edit()
            .remove(PREF_ATTEMPT_STARTED_ELAPSED)
            .putLong(PREF_TRIGGER_RECOVERY_TOTAL_MS, store.getLong(PREF_TRIGGER_RECOVERY_TOTAL_MS, 0L) + latency)
            .putInt(PREF_TRIGGER_RECOVERY_COUNT, store.getInt(PREF_TRIGGER_RECOVERY_COUNT, 0) + 1)
            .putInt(PREF_RECOVERY_COUNT, store.getInt(PREF_RECOVERY_COUNT, 0) + 1)
            .apply()
        attemptTimes = ArrayDeque()
        consecutiveFailures = 0
        circuitOpen = false
        nextAttemptAt = 0L
        persistRetryState()
        recordHistory("5G", "Automatic reconnect confirmed", "success")
        publishState("5G", "5G switch confirmed · attempt $attempt/$maxAttempts", "Automatic reconnect confirmed")
        updateNotification("5G switch confirmed · attempt $attempt/$maxAttempts")
    }

    /**
     * The call never reached the modem (Shizuku down, binder error, rejected bind). Not a radio
     * failure, so the attempt budget and the long back-off are left untouched.
     */
    private fun onEnvironmentFailure(result: String) {
        nextAttemptAt = SystemClock.elapsedRealtime() + ENVIRONMENT_RETRY_DELAY_MS
        persistRetryState()
        recordHistory("4G / LTE", "Reconnect skipped: $result", "failed")
        publishState("4G / LTE", "Waiting for Shizuku · retrying shortly", result)
        updateNotification("Recovery waiting for Shizuku · tap Retry · $result")
    }

    private fun onRadioFailure(detail: String) {
        consecutiveFailures++
        val now = SystemClock.elapsedRealtime()

        if (consecutiveFailures >= maxAttempts) {
            pauseRecovery(now, detail)
            return
        }

        val wait = maxOf(triggerIntervalMs, backoffFor(consecutiveFailures))
        nextAttemptAt = now + wait
        persistRetryState()
        recordHistory("4G / LTE", detail, "failed")
        publishState("4G / LTE", "4G / LTE detected · retry in ${formatInterval(wait)}", detail)
        updateNotification("5G switch failed · retry in ${formatInterval(wait)} · $detail")
    }

    private fun pauseRecovery(now: Long, detail: String) {
        circuitOpen = true
        nextAttemptAt = now + recoveryCooldownMs
        persistRetryState()
        val attempts = maxOf(consecutiveFailures, attemptTimes.size)
        val summary = "Recovery paused after $attempts failed attempts"
        recordHistory("4G / LTE", "$summary · $detail", "failed")
        publishState("4G / LTE", "$summary · retrying in ${formatInterval(recoveryCooldownMs)}", "$summary · $detail")
        updateNotification("$summary · retrying in ${formatInterval(recoveryCooldownMs)}")
    }

    private fun backoffFor(failures: Int): Long = when {
        failures <= 1 -> 30_000L
        failures == 2 -> 60_000L
        failures == 3 -> 300_000L
        else -> MAX_BACKOFF_MS
    }

    private suspend fun waitFor5G(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (radioWatcher?.snapshot()?.is5G == true) return true
            delay(400)
        }
        return radioWatcher?.snapshot()?.is5G == true
    }

    // ── network observation ──────────────────────────────────────────────────────────────────────

    private fun observeNetwork(radio: RadioState) {
        currentLabel = radio.label

        if (radio.is5G && (consecutiveFailures != 0 || circuitOpen || nextAttemptAt != 0L || attemptTimes.isNotEmpty())) {
            attemptTimes = ArrayDeque()
            consecutiveFailures = 0
            circuitOpen = false
            nextAttemptAt = 0L
            persistRetryState()
        }

        if (radio.label != lastObservedNetwork) {
            val detail = if (lastObservedNetwork == null) "Monitoring started" else "Network changed from $lastObservedNetwork"
            recordHistory(radio.label, detail, if (lastObservedNetwork == null) "system" else "network")
            lastObservedNetwork = radio.label
            publishState(radio.label, statusFor(radio), detail)
        }

        usageTracker.sample(radio.label)
        trackFallbackImpact(radio.label)

        val now = System.currentTimeMillis()
        if (radio.label == "Unknown") {
            if (noServiceSince == 0L) noServiceSince = now
            maybeFailOverSim(now)
        } else {
            noServiceSince = 0L
        }
    }

    private fun statusFor(radio: RadioState): String {
        val auto = prefs().getBoolean(PREF_AUTO_ENABLED, false)
        return when {
            paused -> "Paused · tap Resume to continue"
            !auto -> if (radio.is5G) "5G active" else radio.label
            radio.is5G -> "5G active · watching for LTE fallback"
            radio.isLteFallback -> "4G / LTE detected · recovery active"
            radio.label == "4G / LTE" && !radio.detectionReliable ->
                "LTE · 5G state not detectable on this Android version"
            else -> "Monitoring ${radio.label}"
        }
    }

    private fun statusForCurrent(): String = runCatching {
        radioWatcher?.snapshot()?.let { statusFor(it) }
    }.getOrNull() ?: "Monitoring cellular state"

    private fun currentNetworkLabel(): String =
        runCatching { radioWatcher?.snapshot()?.label }.getOrNull() ?: "Unknown"

    private fun trackFallbackImpact(current: String) {
        val store = prefs()
        val nowElapsed = SystemClock.elapsedRealtime()
        val stored = store.getLong(PREF_FALLBACK_STARTED, 0L)
        val validStarted = if (stored > 0L && stored <= nowElapsed) stored else 0L
        if (stored > 0L && validStarted == 0L) store.edit().remove(PREF_FALLBACK_STARTED).apply()

        if (current == "4G / LTE" && validStarted == 0L) {
            store.edit().putLong(PREF_FALLBACK_STARTED, nowElapsed).apply()
        } else if (current == "5G" && validStarted > 0L) {
            val duration = (nowElapsed - validStarted).coerceAtLeast(0L)
            store.edit()
                .remove(PREF_FALLBACK_STARTED)
                .putLong(PREF_LTE_FALLBACK_MS, store.getLong(PREF_LTE_FALLBACK_MS, 0L) + duration)
                .putInt(PREF_LTE_FALLBACK_COUNT, store.getInt(PREF_LTE_FALLBACK_COUNT, 0) + 1)
                .apply()
        }
    }

    private fun maybeFailOverSim(now: Long) {
        val store = prefs()
        if (!store.getBoolean(PREF_FAILOVER_ENABLED, false)) return
        if (noServiceSince == 0L || now - noServiceSince < NO_SERVICE_FAILOVER_DELAY_MS) return
        if (now - store.getLong(PREF_LAST_FAILOVER, 0L) < SIM_FAILOVER_COOLDOWN_MS) return
        val target = store.getInt(PREF_FAILOVER_TARGET, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        if (target == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return
        store.edit().putLong(PREF_LAST_FAILOVER, now).apply()
        recordHistory("No service", "Switching mobile data to the selected backup SIM", "sim_failover")
        scope.launch {
            val result = withContext(Dispatchers.IO) { bridge.switchDefaultDataSubscription(target) }
            recordHistory("SIM", result, if (result.startsWith("Data SIM switched")) "success" else "failed")
            updateNotification(result)
        }
    }

    // ── persistence ──────────────────────────────────────────────────────────────────────────────

    private fun publishState(network: String, status: String, event: String? = null) {
        val editor = prefs().edit()
            .putBoolean(PREF_SERVICE_ACTIVE, isRunning)
            .putString(PREF_CURRENT_NETWORK, network)
            .putString(PREF_STATUS, status)
        if (event != null) {
            editor.putString(PREF_LAST_EVENT_DETAIL, event)
                .putLong(PREF_LAST_EVENT_AT, System.currentTimeMillis())
        }
        editor.apply()
    }

    /** Serialised because the watcher loop and the SIM-failover coroutine can both append. */
    private fun recordHistory(network: String, detail: String = "", type: String = "system") =
        synchronized(historyLock) { appendHistory(network, detail, type) }

    private fun appendHistory(network: String, detail: String, type: String) {
        val store = prefs()
        val entry = org.json.JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("network", network)
            put("detail", detail)
            put("type", type)
        }.toString()
        val history = store.getStringSet(PREF_HISTORY, emptySet()).orEmpty().toMutableSet()
        history.add(entry)
        while (history.size > MAX_HISTORY) {
            val oldest = history.minByOrNull {
                runCatching { org.json.JSONObject(it).optLong("time") }.getOrDefault(Long.MAX_VALUE)
            } ?: break
            history.remove(oldest)
        }
        val editor = store.edit().putStringSet(PREF_HISTORY, history)
        if (type == "success") editor.putInt("successful_attempts", store.getInt("successful_attempts", 0) + 1)
        if (type == "failed") editor.putInt("failed_attempts", store.getInt("failed_attempts", 0) + 1)
        editor.apply()
    }

    private fun formatInterval(ms: Long): String {
        val seconds = (ms / 1_000L).toInt()
        return if (seconds >= 60) "${seconds / 60} min" else "${seconds}s"
    }

    // ── notification ─────────────────────────────────────────────────────────────────────────────

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "5G Reconnect", NotificationManager.IMPORTANCE_LOW).apply {
                description = "5G reconnect background service"
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val health = ShizukuHealthMonitor.state

        val pauseIntent = PendingIntent.getService(
            this, 701,
            Intent(this, AutoReconnectService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 702,
            Intent(this, AutoReconnectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 700,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_network)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setContentTitle("5G Reconnect")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_media_pause, if (paused) "Resume" else "Pause", pauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)

        if (health == ShizukuHealth.UNAVAILABLE || health == ShizukuHealth.SERVICE_DIED) {
            packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { launch ->
                builder.addAction(
                    android.R.drawable.ic_menu_manage,
                    "Open Shizuku",
                    PendingIntent.getActivity(this, 703, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
            }
        }
        return builder.build()
    }

    private fun updateNotification(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
    }
}
