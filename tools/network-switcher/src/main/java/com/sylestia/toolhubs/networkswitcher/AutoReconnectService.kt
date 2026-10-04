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
import java.util.Locale

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
        const val ALERT_CHANNEL_ID = "network_switcher_alerts"
        const val ALERT_NOTIFICATION_ID = 502
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

        // LTE data warning.
        const val PREF_LTE_WARNING_ENABLED = "lte_warning_enabled"
        const val PREF_LTE_WARNING_BYTES = "lte_warning_bytes"
        const val PREF_LTE_WARNED_DAY = "lte_warned_day"
        const val DEFAULT_LTE_WARNING_BYTES = 2L * 1024L * 1024L * 1024L

        // RAT-driven SIM failover: use a backup SIM while the monitored SIM sits on LTE, then
        // return to the primary SIM as soon as 5G is observed again.
        const val PREF_LTE_FAILOVER_ENABLED = "lte_failover_enabled"
        const val PREF_LTE_FAILOVER_TARGET = "lte_failover_target"
        const val PREF_LTE_FAILOVER_PRIMARY = "lte_failover_primary"
        const val PREF_LTE_FAILOVER_ACTIVE = "lte_failover_active"
        const val PREF_LTE_FAILOVER_LAST = "lte_failover_last_switch"
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

        /** Stops a RAT-driven SIM switch from flapping between the two SIMs. */
        const val LTE_FAILOVER_COOLDOWN_MS = 60_000L

        /** How often the LTE data-warning threshold is evaluated. */
        const val DATA_WARNING_CHECK_MS = 5_000L

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
     * The ongoing service notification only exists from [onCreate]'s `startForeground` onwards; posting
     * to [NOTIFICATION_ID] before that would create a detached notification, so updates wait for it.
     */
    @Volatile private var foregroundStarted = false

    /**
     * Retry state is only ever mutated on the watcher thread. Explicit lifecycle actions from the
     * main thread (start/resume) raise this flag instead of writing the fields directly, which
     * keeps the loop free of locking. Environment recovery does not reset retry state.
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
    private var lastLteWarningCheckAt = 0L
    @Volatile private var lteFailoverInFlight = false
    private var lastShizukuHealth: ShizukuHealth? = null

    private val healthListener: (ShizukuHealth) -> Unit = { health ->
        // Becoming ready must never clear an existing retry/cooldown deadline. The watcher
        // continues from the persisted deadline on its next tick. publishState() also refreshes
        // the ongoing notification, so the tray and the app stay in step.
        if (health == ShizukuHealth.READY) {
            publishState(currentLabel, "Shizuku ready · monitoring continues")
        }
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
        startForeground(NOTIFICATION_ID, buildNotification(currentNetworkLabel(), "Starting background watcher…"))
        foregroundStarted = true
        startWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && !prefs().getBoolean(PREF_AUTO_ENABLED, false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                val wasEnabled = prefs().getBoolean(PREF_AUTO_ENABLED, false)
                val wasPaused = prefs().getBoolean(PREF_PAUSED, false)

                prefs().edit().putBoolean(PREF_AUTO_ENABLED, true).apply()
                reloadSettings()
                createRadioWatcher()
                paused = false
                prefs().edit().putBoolean(PREF_PAUSED, false).apply()

                // Starting an already-enabled watcher is an idempotent lifecycle/settings refresh.
                // It must not erase a retry deadline that is already cooling down. A genuinely new
                // enable is still allowed to arm a fresh recovery cycle.
                if (!wasEnabled) {
                    requestReset("Background watcher started")
                } else if (wasPaused) {
                    recordHistory(currentLabel, "Background watcher resumed", "system")
                }

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
            }

            ACTION_RESUME -> {
                paused = false
                prefs().edit().putBoolean(PREF_PAUSED, false).apply()
                requestReset("Manual retry requested")
                ShizukuHealthMonitor.refresh()
                publishState(currentNetworkLabel(), statusForCurrent())
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs().getBoolean(PREF_AUTO_ENABLED, false)) updateOngoing(currentNetworkLabel(), statusForCurrent())
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

        // Never shorten a live in-memory deadline during a settings/lifecycle refresh. This
        // matters when ACTION_START is used to apply a setting while the watcher is already
        // cooling down: the persisted value may lag the watcher slightly because
        // SharedPreferences.apply() is asynchronous.
        val inMemoryWall = if (nextAttemptAt > nowElapsed) nowWall + (nextAttemptAt - nowElapsed) else 0L
        val effectiveWall = maxOf(persistedWall, inMemoryWall)
        val remaining = if (effectiveWall > nowWall) effectiveWall - nowWall else 0L
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
                // Shizuku recovery is an environment event, not a manual retry. Never clear an
                // active retry/cooldown deadline just because the privileged bridge returned.
                publishState(currentLabel, "Shizuku ready · monitoring continues")
            }
        } else {
            val note = health.describe() + " · recovery paused"
            recordHistory("Shizuku", note, "system")
            publishState(currentLabel, note)
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
            updateOngoing("4G / LTE", "Trying 5G · attempt $attempt/$maxAttempts")

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
    }

    private fun pauseRecovery(now: Long, detail: String) {
        circuitOpen = true
        nextAttemptAt = now + recoveryCooldownMs
        persistRetryState()
        val attempts = maxOf(consecutiveFailures, attemptTimes.size)
        val summary = "Recovery paused after $attempts failed attempts"
        recordHistory("4G / LTE", "$summary · $detail", "failed")
        publishState("4G / LTE", "$summary · retrying in ${formatInterval(recoveryCooldownMs)}", "$summary · $detail")
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
        checkLteDataWarning()
        maybeLteFailover(radio)

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
            updateOngoing("SIM", result)
        }
    }

    /**
     * Post an alert the first time today's observed LTE traffic crosses the user's limit.
     *
     * The threshold is checked at most once every [DATA_WARNING_CHECK_MS] so a 1-second watcher tick
     * does not hammer SharedPreferences, and a per-day stamped flag guarantees a single alert per day
     * even though the observed total keeps climbing.
     */
    private fun checkLteDataWarning() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLteWarningCheckAt < DATA_WARNING_CHECK_MS) return
        lastLteWarningCheckAt = now

        val store = prefs()
        if (!store.getBoolean(PREF_LTE_WARNING_ENABLED, false)) return
        val limit = store.getLong(PREF_LTE_WARNING_BYTES, 0L)
        if (limit <= 0L) return

        val today = NetworkUsageTracker.todayStamp()
        if (store.getString(PREF_LTE_WARNED_DAY, null) == today) return

        val used = NetworkUsageTracker.todayBytes(this, NetworkUsageTracker.KEY_LTE_BYTES)
        if (used < limit) return

        store.edit().putString(PREF_LTE_WARNED_DAY, today).apply()
        val detail = "Today's LTE traffic reached " + formatBytes(used) + " of your " + formatBytes(limit) + " limit"
        recordHistory(NetworkUsageTracker.LTE_LABEL, detail, "warning")
        postAlert("4G data warning", detail)
    }

    /**
     * RAT-driven SIM failover: while the monitored SIM sits on LTE, move mobile data to the backup
     * SIM, then move it back once the monitored SIM reports 5G again.
     *
     * The 60-second cooldown plus the persisted `ACTIVE` flag keep a single LTE stretch from firing
     * the switch on every tick. The switch itself is an async Shizuku call, so the state is only
     * updated once the command confirms, and a failed attempt simply retries after the cooldown.
     */
    private fun maybeLteFailover(radio: RadioState) {
        val store = prefs()
        if (!store.getBoolean(PREF_LTE_FAILOVER_ENABLED, false)) return

        val target = store.getInt(PREF_LTE_FAILOVER_TARGET, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val primary = store.getInt(PREF_SELECTED_SUBSCRIPTION, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        if (target == SubscriptionManager.INVALID_SUBSCRIPTION_ID ||
            primary == SubscriptionManager.INVALID_SUBSCRIPTION_ID ||
            target == primary
        ) return
        if (lteFailoverInFlight) return

        val now = System.currentTimeMillis()
        if (now - store.getLong(PREF_LTE_FAILOVER_LAST, 0L) < LTE_FAILOVER_COOLDOWN_MS) return

        val active = store.getBoolean(PREF_LTE_FAILOVER_ACTIVE, false)
        when {
            !active && radio.isLteFallback -> {
                lteFailoverInFlight = true
                store.edit().putLong(PREF_LTE_FAILOVER_LAST, now).apply()
                switchDataSim(target, primary, true, "LTE fallback · moving data to the backup SIM")
            }

            active && radio.is5G -> {
                lteFailoverInFlight = true
                store.edit().putLong(PREF_LTE_FAILOVER_LAST, now).apply()
                switchDataSim(primary, primary, false, "5G restored · moving data back to the primary SIM")
            }
        }
    }

    private fun switchDataSim(target: Int, primary: Int, outbound: Boolean, note: String) {
        recordHistory(currentLabel, note, "sim_failover")
        scope.launch {
            try {
                val result = try {
                    withContext(Dispatchers.IO) { bridge.switchDefaultDataSubscription(target) }
                } catch (t: Throwable) {
                    "Data SIM switch failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                }
                val switched = result.startsWith("Data SIM switched")
                if (switched) {
                    prefs().edit()
                        .putBoolean(PREF_LTE_FAILOVER_ACTIVE, outbound)
                        .putInt(PREF_LTE_FAILOVER_PRIMARY, primary)
                        .apply()
                }
                recordHistory("SIM", result, if (switched) "success" else "failed")
                updateOngoing("SIM", result)
            } finally {
                lteFailoverInFlight = false
            }
        }
    }

    private fun postAlert(title: String, text: String) {
        val openIntent = PendingIntent.getActivity(
            this, 710,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_network)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, notification) }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824f)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576f)
        bytes >= 1_024L -> String.format(Locale.US, "%.0f KB", bytes / 1_024f)
        else -> bytes.toString() + " B"
    }

    // ── persistence ──────────────────────────────────────────────────────────────────────────────

    /**
     * Single source of truth for the watcher's status.
     *
     * Writes it to the shared preferences *and* pushes the exact same text to the ongoing
     * notification. The two used to drift — several call sites updated the status without notifying
     * (so the notification stayed on "Starting background watcher…" for a long time) while others
     * notified with a different string than they stored (so the tray contradicted the app). Building
     * both from one call is what keeps them in step.
     */
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
        updateOngoing(network, status)
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "5G Reconnect", NotificationManager.IMPORTANCE_LOW).apply {
                description = "5G reconnect background service"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "Data warnings", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when observed 4G data usage reaches your limit"
            }
        )
    }

    private fun buildNotification(network: String, status: String): Notification {
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
            .setShowWhen(false)
            .setContentTitle(notificationTitle(network))
            .setContentText(status)
            .setSubText(notificationSubtitle())
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
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

    /** A short, unambiguous headline: what the radio is doing, plus whether the watcher is live. */
    private fun notificationTitle(network: String): String {
        val state = when {
            paused -> "paused"
            network == "5G" -> "on 5G"
            network == "4G / LTE" -> "on 4G"
            network == "Unknown" -> "no service"
            else -> "on " + network.lowercase(Locale.getDefault())
        }
        return "5G Reconnect · $state"
    }

    private fun notificationSubtitle(): String = when {
        paused -> "Tap Resume to keep watching"
        prefs().getBoolean(PREF_AUTO_ENABLED, false) -> "Watching in the background"
        else -> "Started manually"
    }

    /**
     * Pushes [status] to the ongoing notification without touching the stored status. Used for short
     * lived progress lines (an in-flight attempt, a SIM switch result) that should not linger as the
     * app's state once the watcher moves on.
     */
    private fun updateOngoing(network: String, status: String) {
        if (!foregroundStarted) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(network, status))
        }
    }
}
