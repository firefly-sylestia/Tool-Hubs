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
import android.telecom.TelecomManager
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
        const val ACTION_RETRY = "com.sylestia.toolhubs.networkswitcher.RETRY"
        const val ACTION_REVERT = "com.sylestia.toolhubs.networkswitcher.REVERT"
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

        // No-service failover bookkeeping. `ACTIVE` plus `MONITORED` is what makes the move back
        // possible at all: the outbound switch recorded nothing about where it came from, so there
        // was no code path that could ever return the data connection to the monitored SIM.
        const val PREF_FAILOVER_ACTIVE = "sim_failover_active"
        const val PREF_FAILOVER_MONITORED = "sim_failover_monitored"
        const val PREF_FAILOVER_MOVED_TO = "sim_failover_moved_to"

        // Wi-Fi failover. Shell-level Wi-Fi control is the only version of this that can work: apps
        // have been barred from toggling Wi-Fi since Android 10, so it goes through Shizuku.
        const val PREF_WIFI_FAILOVER_ENABLED = "wifi_failover_enabled"
        const val PREF_WIFI_FAILOVER_ACTIVE = "wifi_failover_active"
        const val PREF_WIFI_FAILOVER_LAST = "wifi_failover_last"

        // LTE data warning.
        const val PREF_LTE_WARNING_ENABLED = "lte_warning_enabled"
        const val PREF_LTE_WARNING_BYTES = "lte_warning_bytes"
        const val PREF_LTE_WARNED_DAY = "lte_warned_day"
        const val DEFAULT_LTE_WARNING_BYTES = 2L * 1024L * 1024L * 1024L

        // Developer-options style download shaping (Android 13+ when supported by the device).
        const val PREF_LTE_SPEED_LIMIT_ENABLED = "lte_speed_limit_enabled"
        const val PREF_LTE_SPEED_LIMIT_MBPS = "lte_speed_limit_mbps"
        const val PREF_SPEED_LIMIT_ORIGINAL = "speed_limit_original"
        const val PREF_SPEED_LIMIT_ACTIVE = "speed_limit_active"
        const val PREF_SPEED_LIMIT_APPLIED_BYTES = "speed_limit_applied_bytes"
        const val DEFAULT_LTE_SPEED_LIMIT_MBPS = 5f

        // RAT-driven SIM failover: use a backup SIM while the monitored SIM sits on LTE, then
        // return to the primary SIM as soon as 5G is observed again.
        const val PREF_LTE_FAILOVER_ENABLED = "lte_failover_enabled"
        const val PREF_LTE_FAILOVER_TARGET = "lte_failover_target"
        const val PREF_LTE_FAILOVER_PRIMARY = "lte_failover_primary"
        const val PREF_LTE_FAILOVER_ACTIVE = "lte_failover_active"
        const val PREF_LTE_FAILOVER_LAST = "lte_failover_last_switch"
        const val PREF_LTE_FAILOVER_ACTIVATED_AT = "lte_failover_activated_at"
        const val PREF_LTE_FAILOVER_RELAPSE_UNTIL = "lte_failover_relapse_until"
        const val PREF_LTE_FAILOVER_PAUSE_RECOVERY = "lte_failover_pause_recovery"
        const val PREF_PAUSE_DURING_CALL = "pause_during_call"
        const val PREF_FALLBACK_STARTED = "fallback_started_elapsed"
        const val PREF_LTE_FALLBACK_MS = "lte_fallback_ms"
        const val PREF_RECOVERY_TOTAL_MS = "recovery_total_ms"
        const val PREF_RECOVERY_COUNT = "recovery_count"

        // Best and worst confirmed recovery, and the longest run of consecutive failures. The
        // recovery analytics screen used to show only a mean, which hides exactly the two cases a
        // user cares about: the recovery that took a minute, and the streak that never ended.
        const val PREF_RECOVERY_FASTEST_MS = "recovery_fastest_ms"
        const val PREF_RECOVERY_SLOWEST_MS = "recovery_slowest_ms"
        const val PREF_FAILURE_STREAK_MAX = "failure_streak_max"
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
        const val DEFAULT_INTERVAL_MS = 5_000L
        const val MIN_INTERVAL_MS = 5_000L
        const val MAX_INTERVAL_MS = 600_000L
        const val DEFAULT_MAX_ATTEMPTS = 2
        const val MIN_MAX_ATTEMPTS = 1
        const val MAX_MAX_ATTEMPTS = 2
        const val DEFAULT_RECOVERY_COOLDOWN_MS = 1 * 60_000L
        const val MIN_RECOVERY_COOLDOWN_MS = 1 * 60_000L
        const val MAX_RECOVERY_COOLDOWN_MS = 30 * 60_000L
        const val NO_SERVICE_FAILOVER_DELAY_MS = 45_000L
        const val SIM_FAILOVER_COOLDOWN_MS = 15 * 60_000L

        /** Settle time before handing data back after service returns, so it does not bounce. */
        const val SIM_FAILOVER_REVERT_SETTLE_MS = 60_000L

        /** Stops a RAT-driven SIM switch from flapping between the two SIMs. */
        const val LTE_FAILOVER_COOLDOWN_MS = 60_000L

        /**
         * How long data may stay parked on the backup SIM before it is handed back regardless.
         *
         * Reverting only when the monitored SIM reports 5G assumed the primary would recover on its
         * own, which it cannot do while the backup SIM holds the data connection — so a phone that
         * fell back stayed on the backup SIM until the user intervened. This bounds that.
         */
        const val LTE_FAILOVER_MAX_HOLD_MS = 4 * 60_000L

        /**
         * After a timed hand-back, wait this long before moving to the backup SIM again. Without it
         * the two SIMs would trade the data connection every cooldown, which is worse than either
         * SIM on its own.
         */
        const val LTE_FAILOVER_RELAPSE_COOLDOWN_MS = 10 * 60_000L

        /** How long the monitored SIM may sit on LTE (or with no service) before Wi-Fi is raised. */
        const val WIFI_FAILOVER_DELAY_MS = 20_000L
        const val WIFI_FAILOVER_COOLDOWN_MS = 60_000L

        /** Give the modem a few seconds after a call before any automatic SIM/RAT change. */
        const val CALL_END_GRACE_MS = 3_500L

        /** How often the LTE data-warning threshold is evaluated. */
        const val DATA_WARNING_CHECK_MS = 5_000L

        /** How long we wait for a triggered re-selection to actually land on 5G. */
        const val SWITCH_CONFIRM_TIMEOUT_MS = 8_000L

        /** Longest single back-off, and the ceiling applied to a value restored after a reboot. */
        const val MAX_BACKOFF_MS = 900_000L

        /** Environment (Shizuku) problems must never cost the user minutes. */
        const val ENVIRONMENT_RETRY_DELAY_MS = 15_000L
        const val ENVIRONMENT_NOTICE_INTERVAL_MS = 30_000L

        /**
         * How often the cooldown countdown is refreshed in the tray and in the app while recovery
         * is parked. Without this the notification froze on the moment the cooldown started.
         */
        const val COOLDOWN_NOTICE_REFRESH_MS = 15_000L

        private const val TICK_MS = 1_000L
        // Stable 5G needs only occasional checks; return to fast sampling on LTE/cooldown/recovery.
        private const val IDLE_TICK_MS = 10_000L
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
    @Volatile private var lastNotificationFingerprint: String? = null

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
    private var lastCooldownPublishAt = 0L
    private var lastLoopErrorAt = 0L
    private var noServiceSince = 0L
    private var wifiStruggleSince = 0L
    private var lastLteWarningCheckAt = 0L
    private var lastSpeedLimitAttemptAt = 0L
    @Volatile private var lteFailoverInFlight = false
    @Volatile private var wifiFailoverInFlight = false

    /** Set from the notification's revert action, consumed on the watcher thread. */
    @Volatile private var revertRequested = false
    @Volatile private var callPaused = false
    private var callGraceUntilElapsed = 0L
    private var lastShizukuHealth: ShizukuHealth? = null

    private val healthListener: (ShizukuHealth) -> Unit = { health ->
        // Becoming ready must never clear an existing retry/cooldown deadline. The watcher
        // continues from the persisted deadline on its next tick. The status is derived from state
        // rather than written as a literal, so a recovered bridge cannot hide an active cooldown.
        if (health == ShizukuHealth.READY) {
            publishState(currentLabel, statusText())
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
        usageTracker.onImplausibleBurst = { network, bytesPerSecond ->
            recordHistory(network, "Ignored an implausible traffic burst of " + formatBytes(bytesPerSecond) + "/s", "system")
        }

        ShizukuHealthMonitor.install()
        ShizukuHealthMonitor.addListener(healthListener)

        reloadSettings()
        // A START_STICKY recreation must come back in the same paused state the user left it in.
        paused = prefs().getBoolean(PREF_PAUSED, false)
        createRadioWatcher()
        createChannel()
        val startup = statusText()
        publishState(currentNetworkLabel(), startup)
        startForeground(NOTIFICATION_ID, buildNotification(currentNetworkLabel(), startup))
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

                // Derived, not literal: a settings refresh must never wipe a cooldown indicator.
                publishState(currentNetworkLabel(), statusText())
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
                runCatching { restoreLteSpeedLimit() }
                publishState(currentNetworkLabel(), "Paused · tap Resume to continue")
            }

            ACTION_RESUME -> {
                paused = false
                prefs().edit().putBoolean(PREF_PAUSED, false).apply()
                // Settings changed while the watcher was paused only reach the loop on a reload.
                reloadSettings()
                createRadioWatcher()
                requestReset("Manual retry requested")
                ShizukuHealthMonitor.refresh()
                publishState(currentNetworkLabel(), statusText())
            }

            ACTION_RETRY -> {
                // Clears the cooldown and the attempt budget without unpausing: the tray action is
                // "try again now", not "resume automation".
                requestReset("Retry requested from the status notification")
                ShizukuHealthMonitor.refresh()
                publishState(currentNetworkLabel(), statusText())
            }

            ACTION_REVERT -> {
                // Undo every active failover now. This is the escape hatch for the case the automatic
                // path cannot cover: the monitored SIM never recovers, so waiting is pointless.
                revertRequested = true
                publishState(currentNetworkLabel(), "Moving data back to the monitored connection…")
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs().getBoolean(PREF_AUTO_ENABLED, false)) updateOngoing(currentNetworkLabel(), statusText())
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { usageTracker.flush() }
        runCatching { restoreLteSpeedLimit() }
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
                val radio = runCatching { radioWatcher?.snapshot() }.getOrNull()
                val needsFastChecks = radio?.isLteFallback == true || operationRunning || circuitOpen
                delay(if (needsFastChecks) TICK_MS else IDLE_TICK_MS)
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
        if (revertRequested) {
            revertRequested = false
            revertAllFailovers("Manual revert")
        }
        val radio = radioWatcher?.snapshot() ?: return
        val pauseDuringCall = prefs().getBoolean(PREF_PAUSE_DURING_CALL, false)
        val inCall = pauseDuringCall && isPhoneCallActive()

        if (inCall && !callPaused) {
            callPaused = true
            callGraceUntilElapsed = 0L
            usageTracker.pauseForCall()
            publishState(radio.label, "Call active · automatic switching paused")
        } else if (!inCall && callPaused) {
            callPaused = false
            callGraceUntilElapsed = now + CALL_END_GRACE_MS
            usageTracker.resumeAfterCall()
            publishState(radio.label, "Call ended · waiting 4s before switching")
        }

        val automationBlocked = inCall || now < callGraceUntilElapsed
        observeNetwork(radio, automationBlocked)
        if (!automationBlocked) maybeRecover(radio, now)
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
            publishState(currentLabel, statusText())
            // Tell the user the moment the privileged bridge stops working, instead of only
            // showing it in the app where they may never look.
            if (previous == ShizukuHealth.READY) postAlert("5G recovery paused", note)
        }
    }

    private suspend fun maybeRecover(radio: RadioState, now: Long) {
        // Keep the countdown current while recovery is parked: the notification used to freeze on
        // the instant the cooldown began, and the app read the same stale string.
        if (circuitOpen && now < nextAttemptAt && now - lastCooldownPublishAt >= COOLDOWN_NOTICE_REFRESH_MS) {
            lastCooldownPublishAt = now
            publishState(radio.label, statusText(radio))
        }

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

        // When LTE failover has moved data to the backup SIM, do not keep forcing
        // 5G re-selection on the monitored primary. The backup SIM may be LTE-only.
        // We still keep watching the primary radio, so a natural 5G return can move
        // data back to the primary and automatically resume recovery.
        val failoverActive = prefs().getBoolean(PREF_LTE_FAILOVER_ACTIVE, false)
        val pauseRecoveryForBackup = prefs().getBoolean(PREF_LTE_FAILOVER_PAUSE_RECOVERY, true)
        if (failoverActive && pauseRecoveryForBackup) return

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
        val fastest = store.getLong(PREF_RECOVERY_FASTEST_MS, 0L)
        val editor = store.edit()
            .remove(PREF_ATTEMPT_STARTED_ELAPSED)
            .putLong(PREF_TRIGGER_RECOVERY_TOTAL_MS, store.getLong(PREF_TRIGGER_RECOVERY_TOTAL_MS, 0L) + latency)
            .putInt(PREF_TRIGGER_RECOVERY_COUNT, store.getInt(PREF_TRIGGER_RECOVERY_COUNT, 0) + 1)
            .putInt(PREF_RECOVERY_COUNT, store.getInt(PREF_RECOVERY_COUNT, 0) + 1)
        if (fastest <= 0L || latency < fastest) editor.putLong(PREF_RECOVERY_FASTEST_MS, latency)
        if (latency > store.getLong(PREF_RECOVERY_SLOWEST_MS, 0L)) {
            editor.putLong(PREF_RECOVERY_SLOWEST_MS, latency)
        }
        editor.apply()
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
        // The privileged bridge failed before the modem was ever asked to do anything, so this is not
        // a radio failure: give the attempt back. Three transient bind errors inside the burst window
        // used to trip `attemptTimes.size >= maxAttempts`, which parked recovery on the full cooldown
        // even though nothing was wrong with the network.
        if (attemptTimes.isNotEmpty()) attemptTimes.removeLast()
        nextAttemptAt = SystemClock.elapsedRealtime() + ENVIRONMENT_RETRY_DELAY_MS
        persistRetryState()
        recordHistory("4G / LTE", "Reconnect skipped: $result", "failed")
        publishState("4G / LTE", "Waiting for Shizuku · retrying shortly", result)
    }

    private fun onRadioFailure(detail: String) {
        consecutiveFailures++
        val now = SystemClock.elapsedRealtime()
        if (consecutiveFailures > prefs().getInt(PREF_FAILURE_STREAK_MAX, 0)) {
            prefs().edit().putInt(PREF_FAILURE_STREAK_MAX, consecutiveFailures).apply()
        }

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
        publishState("4G / LTE", "$summary · retrying in ${formatCountdown(recoveryCooldownMs)}", "$summary · $detail")
        // A cooldown is a real state change the user asked to be told about, not just a line in the
        // app they may never open.
        postAlert(
            "5G recovery paused",
            "$summary.\n$detail\n\nRetrying automatically in ${formatInterval(recoveryCooldownMs)}. Open 5G Reconnect to retry now.",
        )
    }

    private fun backoffFor(failures: Int): Long = when {
        failures <= 1 -> 5_000L
        failures == 2 -> 10_000L
        failures == 3 -> 20_000L
        else -> 60_000L
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

    private fun observeNetwork(radio: RadioState, automationBlocked: Boolean = false) {
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
            publishState(radio.label, statusText(radio), detail)
        }

        updateLteSpeedLimit(radio)
        if (!callPaused) usageTracker.sample(radio.label)
        trackFallbackImpact(radio.label)
        checkLteDataWarning()
        if (!automationBlocked) maybeLteFailover(radio)

        val now = System.currentTimeMillis()
        if (radio.label == "Unknown") {
            if (noServiceSince == 0L) noServiceSince = now
        } else {
            noServiceSince = 0L
        }
        if (!automationBlocked) {
            maybeNoServiceFailover(radio, now)
            maybeWifiFailover(radio, now)
        }
    }

    /**
     * This is the same Android Developer options "Network download rate limit" setting.
     * Android applies it to every internet-capable network (including Wi-Fi), not only cellular.
     * It limits downloads; this Android setting does not expose an upload cap.
     *
     * The app owns the setting only while LTE fallback is active. On exit, it restores the
     * previous value only if the global setting still equals the value this app wrote, avoiding
     * overwriting a value the user changed manually in Developer options.
     */
    private fun updateLteSpeedLimit(radio: RadioState) {
        val store = prefs()
        val enabled = store.getBoolean(PREF_LTE_SPEED_LIMIT_ENABLED, false)
        if (!enabled || !radio.isLteFallback) {
            if (store.getBoolean(PREF_SPEED_LIMIT_ACTIVE, false)) restoreLteSpeedLimit()
            return
        }

        val mbps = store.getFloat(PREF_LTE_SPEED_LIMIT_MBPS, DEFAULT_LTE_SPEED_LIMIT_MBPS)
            .coerceIn(0.1f, 50f)
        val bytesPerSecond = (mbps * 125_000f).toLong().coerceAtLeast(1L)
        val alreadyActive = store.getBoolean(PREF_SPEED_LIMIT_ACTIVE, false)
        if (alreadyActive && store.getLong(PREF_SPEED_LIMIT_APPLIED_BYTES, -1L) == bytesPerSecond) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastSpeedLimitAttemptAt < 10_000L) return
        lastSpeedLimitAttemptAt = now

        runCatching {
            val original = if (alreadyActive) {
                store.getLong(PREF_SPEED_LIMIT_ORIGINAL, -1L)
            } else {
                val current = bridge.getIngressRateLimit().trim()
                val parsed = current.toLongOrNull()
                if (parsed == null) {
                    recordHistory(radio.label, "Cannot read Android download limit: $current", "failed")
                    return
                }
                parsed
            }

            val result = bridge.setIngressRateLimit(bytesPerSecond)
            if (result.startsWith("Download rate limit updated")) {
                store.edit()
                    .putLong(PREF_SPEED_LIMIT_ORIGINAL, original)
                    .putBoolean(PREF_SPEED_LIMIT_ACTIVE, true)
                    .putLong(PREF_SPEED_LIMIT_APPLIED_BYTES, bytesPerSecond)
                    .apply()
                recordHistory(
                    radio.label,
                    if (alreadyActive) "LTE download limit updated · $mbps Mbps"
                    else "Android download limit enabled on LTE · $mbps Mbps",
                    "system",
                )
                publishState(radio.label, "LTE download limit active · $mbps Mbps")
            } else {
                recordHistory(radio.label, "Could not apply LTE download limit: $result", "failed")
            }
        }.onFailure {
            recordHistory(radio.label, "Download limit error: ${it.javaClass.simpleName}", "failed")
        }
    }

    private fun restoreLteSpeedLimit() {
        val store = prefs()
        if (!store.getBoolean(PREF_SPEED_LIMIT_ACTIVE, false)) return

        val currentText = bridge.getIngressRateLimit().trim()
        val current = currentText.toLongOrNull()
        if (current == null) {
            recordHistory(currentLabel, "Could not verify Android download limit before restoring: $currentText", "failed")
            return
        }

        val applied = store.getLong(PREF_SPEED_LIMIT_APPLIED_BYTES, -1L)
        if (current != applied) {
            // The user or Android changed Developer options while LTE shaping was active.
            // Respect that newer value rather than restoring over it.
            store.edit()
                .putBoolean(PREF_SPEED_LIMIT_ACTIVE, false)
                .remove(PREF_SPEED_LIMIT_ORIGINAL)
                .remove(PREF_SPEED_LIMIT_APPLIED_BYTES)
                .apply()
            recordHistory(currentLabel, "Android download limit changed externally; left the newer value untouched", "system")
            return
        }

        val original = store.getLong(PREF_SPEED_LIMIT_ORIGINAL, -1L)
        val result = bridge.setIngressRateLimit(original)
        if (result.startsWith("Download rate limit updated")) {
            store.edit()
                .putBoolean(PREF_SPEED_LIMIT_ACTIVE, false)
                .remove(PREF_SPEED_LIMIT_ORIGINAL)
                .remove(PREF_SPEED_LIMIT_APPLIED_BYTES)
                .apply()
            recordHistory(currentLabel, "Previous Android Developer options download limit restored", "system")
        } else {
            recordHistory(currentLabel, "Could not restore previous download limit: $result", "failed")
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

    /**
     * The single place that decides what the tray *and* the app say the watcher is doing.
     *
     * Every status publish goes through here. Several call sites used to write their own literal
     * string — "Background watcher started", "Shizuku ready" — which overwrote an active cooldown
     * message whenever a setting changed or Shizuku flickered. That is why recovery could be parked
     * with no indicator anywhere. Composing the text from state keeps the cooldown visible until it
     * actually ends.
     */
    private fun statusText(radio: RadioState? = null): String {
        if (paused) return "Paused · tap Resume to continue"

        val health = ShizukuHealthMonitor.state
        if (health != ShizukuHealth.READY) return health.describe() + " · recovery paused"

        if (circuitOpen) {
            val attemptWord = if (consecutiveFailures == 1) "attempt" else "attempts"
            val wait = remainingUntilNextAttemptMs()
            val prefix = "Cooling down after $consecutiveFailures failed $attemptWord"
            return if (wait > 0L) "$prefix · retrying in ${formatCountdown(wait)}" else "$prefix · probing now"
        }

        val state = radio ?: runCatching { radioWatcher?.snapshot() }.getOrNull()
            ?: return "Monitoring cellular state"
        // A moved data connection outranks the radio label: it is the one thing the user has to know.
        failoverSummary()?.let { return "$it · ${statusFor(state)}" }
        val wait = remainingUntilNextAttemptMs()
        if (wait > 0L && state.isLteFallback) return "4G / LTE detected · next attempt in ${formatCountdown(wait)}"
        return statusFor(state)
    }

    /**
     * Where the data connection currently is, or null when nothing has been moved.
     *
     * The tray used to keep reading "monitoring 4G / LTE" straight through a failover, so the user
     * could not tell that their data had been moved to the other SIM, or that Wi-Fi had been raised.
     */
    private fun failoverSummary(): String? {
        val store = prefs()
        val wifiActive = store.getBoolean(PREF_WIFI_FAILOVER_ACTIVE, false)
        val lteActive = store.getBoolean(PREF_LTE_FAILOVER_ACTIVE, false)
        val simActive = store.getBoolean(PREF_FAILOVER_ACTIVE, false)
        if (!wifiActive && !lteActive && !simActive) return null

        val defaultData = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }
            .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val monitored = store.getInt(PREF_SELECTED_SUBSCRIPTION, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val dataIsOnBackup = defaultData != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
            monitored != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
            defaultData != monitored

        return when {
            wifiActive && dataIsOnBackup -> "Wi-Fi raised · data on the other SIM"
            wifiActive -> "Wi-Fi raised · waiting for 5G"
            dataIsOnBackup -> "Data moved to the other SIM · tap Revert to move it back"
            lteActive || simActive -> "Failover active · confirming the data SIM"
            else -> null
        }
    }

    /** Milliseconds before the next attempt may run, or 0 when one is allowed now. */
    private fun remainingUntilNextAttemptMs(): Long {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nextAttemptAt > nowElapsed) return nextAttemptAt - nowElapsed
        // Fall back to the persisted wall-clock deadline, which is also what the UI reads, so the
        // tray and the app cannot disagree after a process restart.
        return (prefs().getLong(PREF_NEXT_ATTEMPT_WALL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    }

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

    /** Telecom covers managed and self-managed calls on either SIM. */
    private fun isPhoneCallActive(): Boolean = runCatching {
        getSystemService(TelecomManager::class.java)?.isInCall == true ||
            telephony.callState != TelephonyManager.CALL_STATE_IDLE
    }.getOrDefault(false)

    /**
     * Works out which SIM is monitored and which one is the backup.
     *
     * Both used to have to be chosen explicitly, so a phone with two SIMs and untouched settings sat
     * there doing nothing at all — the failover looked broken because it never engaged. The monitored
     * SIM now defaults to whichever SIM carries data, and the backup to the other active SIM.
     * Returns null when the device genuinely only has one usable subscription.
     */
    private fun resolveSubscriptions(
        store: android.content.SharedPreferences,
        monitorKey: String,
        backupKey: String,
    ): Pair<Int, Int>? {
        val active = runCatching {
            getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList
                .orEmpty()
                .map { it.subscriptionId }
        }.getOrDefault(emptyList())
        if (active.size < 2) return null

        val storedMonitor = store.getInt(monitorKey, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val monitored = if (active.contains(storedMonitor)) {
            storedMonitor
        } else {
            SubscriptionManager.getDefaultDataSubscriptionId().takeIf { active.contains(it) } ?: active.first()
        }

        val storedBackup = store.getInt(backupKey, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val backup = if (storedBackup != monitored && active.contains(storedBackup)) {
            storedBackup
        } else {
            active.firstOrNull { it != monitored } ?: return null
        }
        return monitored to backup
    }

    /** Performs one data-SIM move and reports whether the device confirmed it. */
    private suspend fun moveData(subscriptionId: Int): Boolean {
        val result = try {
            withContext(Dispatchers.IO) { bridge.switchDefaultDataSubscription(subscriptionId) }
        } catch (t: Throwable) {
            "Data SIM switch failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
        }
        val switched = result.startsWith("Data SIM switched")
        recordHistory("SIM", result, if (switched) "success" else "failed")
        updateOngoing("SIM", result)
        return switched
    }

    /**
     * No-service SIM failover, in both directions.
     *
     * The previous version could only ever move data *to* the backup SIM. Nothing recorded that a
     * failover was in progress or which SIM it came from, so there was no path back and the user
     * stayed on the backup SIM until they changed it by hand.
     */
    private fun maybeNoServiceFailover(radio: RadioState, now: Long) {
        val store = prefs()

        if (store.getBoolean(PREF_FAILOVER_ACTIVE, false)) {
            // Any service at all on the monitored SIM is enough to hand the connection back.
            if (radio.label == "Unknown") return
            val monitored = store.getInt(
                PREF_FAILOVER_MONITORED,
                store.getInt(PREF_SELECTED_SUBSCRIPTION, SubscriptionManager.INVALID_SUBSCRIPTION_ID),
            )
            if (monitored == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return
            if (now - store.getLong(PREF_LAST_FAILOVER, 0L) < SIM_FAILOVER_REVERT_SETTLE_MS) return
            store.edit().putLong(PREF_LAST_FAILOVER, now).apply()
            recordHistory(radio.label, "Service restored · moving data back to the monitored SIM", "sim_failover")
            scope.launch {
                if (moveData(monitored)) {
                    prefs().edit()
                        .putBoolean(PREF_FAILOVER_ACTIVE, false)
                        .remove(PREF_FAILOVER_MOVED_TO)
                        .remove(PREF_FAILOVER_MONITORED)
                        .apply()
                }
                publishState(currentLabel, statusText())
            }
            return
        }

        if (!store.getBoolean(PREF_FAILOVER_ENABLED, false)) return
        if (noServiceSince == 0L || now - noServiceSince < NO_SERVICE_FAILOVER_DELAY_MS) return
        if (now - store.getLong(PREF_LAST_FAILOVER, 0L) < SIM_FAILOVER_COOLDOWN_MS) return

        val pair = resolveSubscriptions(store, PREF_SELECTED_SUBSCRIPTION, PREF_FAILOVER_TARGET)
        if (pair == null) {
            recordHistory("No service", "Backup SIM failover skipped: no second SIM is available", "system")
            return
        }
        val (monitored, backup) = pair
        store.edit()
            .putLong(PREF_LAST_FAILOVER, now)
            .putInt(PREF_FAILOVER_MONITORED, monitored)
            .putInt(PREF_FAILOVER_MOVED_TO, backup)
            .apply()
        recordHistory("No service", "Switching mobile data to the other SIM", "sim_failover")
        scope.launch {
            if (moveData(backup)) {
                prefs().edit().putBoolean(PREF_FAILOVER_ACTIVE, true).apply()
            }
            publishState(currentLabel, statusText())
        }
    }

    /**
     * Wi-Fi failover: raise the Wi-Fi radio when cellular recovery cannot get anywhere.
     *
     * This can only *enable* Wi-Fi and let the device auto-join a saved network — Android gives no way
     * to pick one — so it is a nudge, not a guarantee, and it says so. It is reverted as soon as the
     * monitored radio is back on 5G.
     */
    private fun maybeWifiFailover(radio: RadioState, now: Long) {
        val store = prefs()
        val enabled = store.getBoolean(PREF_WIFI_FAILOVER_ENABLED, false)
        val active = store.getBoolean(PREF_WIFI_FAILOVER_ACTIVE, false)
        if (!enabled && !active) return
        if (wifiFailoverInFlight) return

        val wifiOn = runCatching {
            getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled == true
        }.getOrDefault(false)

        if (active) {
            if (!radio.is5G || !wifiOn) return
            if (now - store.getLong(PREF_WIFI_FAILOVER_LAST, 0L) < WIFI_FAILOVER_COOLDOWN_MS) return
            wifiFailoverInFlight = true
            store.edit().putLong(PREF_WIFI_FAILOVER_LAST, now).apply()
            scope.launch {
                try {
                    val result = runCatching {
                        withContext(Dispatchers.IO) { bridge.setWifiEnabled(false) }
                    }.getOrElse { "Wi-Fi switch failed: ${it.javaClass.simpleName}" }
                    if (result.startsWith("Wi-Fi")) {
                        prefs().edit().putBoolean(PREF_WIFI_FAILOVER_ACTIVE, false).apply()
                    }
                    recordHistory("Wi-Fi", "5G restored · $result", if (result.startsWith("Wi-Fi")) "success" else "failed")
                    publishState(currentLabel, statusText())
                } finally {
                    wifiFailoverInFlight = false
                }
            }
            return
        }

        val struggling = radio.isLteFallback || radio.label == "Unknown"
        if (!struggling) {
            wifiStruggleSince = 0L
            return
        }
        if (wifiStruggleSince == 0L) wifiStruggleSince = now
        if (now - wifiStruggleSince < WIFI_FAILOVER_DELAY_MS) return
        if (wifiOn) return
        if (now - store.getLong(PREF_WIFI_FAILOVER_LAST, 0L) < WIFI_FAILOVER_COOLDOWN_MS) return

        wifiFailoverInFlight = true
        store.edit().putLong(PREF_WIFI_FAILOVER_LAST, now).apply()
        recordHistory(radio.label, "Cellular recovery stalled · raising Wi-Fi", "wifi_failover")
        scope.launch {
            try {
                val result = runCatching {
                    withContext(Dispatchers.IO) { bridge.setWifiEnabled(true) }
                }.getOrElse { "Wi-Fi switch failed: ${it.javaClass.simpleName}" }
                if (result.startsWith("Wi-Fi")) {
                    prefs().edit().putBoolean(PREF_WIFI_FAILOVER_ACTIVE, true).apply()
                }
                recordHistory("Wi-Fi", result, if (result.startsWith("Wi-Fi")) "success" else "failed")
                publishState(currentLabel, statusText())
            } finally {
                wifiFailoverInFlight = false
            }
        }
    }

    /**
     * Hands every active failover back in one ordered pass: data first, then the Wi-Fi radio.
     *
     * Used by the tray's revert action, which exists because the automatic path cannot cover the case
     * where the monitored SIM never recovers at all.
     */
    private fun revertAllFailovers(reason: String) {
        scope.launch {
            val store = prefs()
            val monitored = store.getInt(PREF_SELECTED_SUBSCRIPTION, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            val lteActive = store.getBoolean(PREF_LTE_FAILOVER_ACTIVE, false)
            val simActive = store.getBoolean(PREF_FAILOVER_ACTIVE, false)

            if (lteActive || simActive) {
                val target = if (lteActive) {
                    store.getInt(PREF_LTE_FAILOVER_PRIMARY, monitored)
                } else {
                    store.getInt(PREF_FAILOVER_MONITORED, monitored)
                }
                if (target != SubscriptionManager.INVALID_SUBSCRIPTION_ID && moveData(target)) {
                    prefs().edit()
                        .putBoolean(PREF_LTE_FAILOVER_ACTIVE, false)
                        .putBoolean(PREF_FAILOVER_ACTIVE, false)
                        .remove(PREF_LTE_FAILOVER_ACTIVATED_AT)
                        .remove(PREF_FAILOVER_MOVED_TO)
                        .apply()
                }
            }

            if (store.getBoolean(PREF_WIFI_FAILOVER_ACTIVE, false)) {
                val result = runCatching {
                    withContext(Dispatchers.IO) { bridge.setWifiEnabled(false) }
                }.getOrElse { "Wi-Fi switch failed: ${it.javaClass.simpleName}" }
                if (result.startsWith("Wi-Fi")) {
                    prefs().edit().putBoolean(PREF_WIFI_FAILOVER_ACTIVE, false).apply()
                }
                recordHistory("Wi-Fi", "$reason · $result", if (result.startsWith("Wi-Fi")) "success" else "failed")
            }

            recordHistory(currentLabel, reason, "sim_failover")
            publishState(currentLabel, statusText())
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
     * When `PREF_LTE_FAILOVER_PAUSE_RECOVERY` is enabled, the normal 5G re-selection loop is suspended
     * while the backup SIM is active. This is important when the backup SIM is LTE-only: the watcher
     * must not keep trying to force 5G on the data path just because the primary SIM is still on LTE.
     *
     * The 60-second cooldown plus the persisted `ACTIVE` flag keep a single LTE stretch from firing
     * the switch on every tick. The switch itself is an async Shizuku call, so the state is only
     * updated once the command confirms, and a failed attempt simply retries after the cooldown.
     */
    private fun maybeLteFailover(radio: RadioState) {
        val store = prefs()
        if (!store.getBoolean(PREF_LTE_FAILOVER_ENABLED, false)) return

        if (lteFailoverInFlight) return

        val pair = resolveSubscriptions(store, PREF_SELECTED_SUBSCRIPTION, PREF_LTE_FAILOVER_TARGET) ?: return
        val (monitored, backup) = pair

        val now = System.currentTimeMillis()
        val active = store.getBoolean(PREF_LTE_FAILOVER_ACTIVE, false)

        if (active) {
            if (now - store.getLong(PREF_LTE_FAILOVER_LAST, 0L) < LTE_FAILOVER_COOLDOWN_MS) return
            val heldFor = (now - store.getLong(PREF_LTE_FAILOVER_ACTIVATED_AT, now)).coerceAtLeast(0L)
            val restored = radio.is5G
            val heldTooLong = heldFor >= LTE_FAILOVER_MAX_HOLD_MS
            if (!restored && !heldTooLong) return

            val note = if (restored) {
                "5G restored · moving data back to the monitored SIM"
            } else {
                "Handing data back to the monitored SIM after " + formatInterval(LTE_FAILOVER_MAX_HOLD_MS)
            }
            // A timed hand-back means the same fallback is still in place, so suppress the next
            // outbound switch for a while. Otherwise the two SIMs would trade the data connection on
            // every cooldown, which is worse than leaving it on either one.
            val editor = store.edit().putLong(PREF_LTE_FAILOVER_LAST, now)
            if (heldTooLong && !restored) {
                editor.putLong(PREF_LTE_FAILOVER_RELAPSE_UNTIL, now + LTE_FAILOVER_RELAPSE_COOLDOWN_MS)
            }
            editor.apply()

            lteFailoverInFlight = true
            recordHistory(radio.label, note, "sim_failover")
            scope.launch {
                try {
                    if (moveData(monitored)) {
                        prefs().edit()
                            .putBoolean(PREF_LTE_FAILOVER_ACTIVE, false)
                            .remove(PREF_LTE_FAILOVER_ACTIVATED_AT)
                            .apply()
                    }
                    publishState(currentLabel, statusText())
                } finally {
                    lteFailoverInFlight = false
                }
            }
            return
        }

        if (now < store.getLong(PREF_LTE_FAILOVER_RELAPSE_UNTIL, 0L)) return
        if (!radio.isLteFallback) return
        if (now - store.getLong(PREF_LTE_FAILOVER_LAST, 0L) < LTE_FAILOVER_COOLDOWN_MS) return

        lteFailoverInFlight = true
        store.edit()
            .putLong(PREF_LTE_FAILOVER_LAST, now)
            .putLong(PREF_LTE_FAILOVER_ACTIVATED_AT, now)
            .apply()
        switchDataSim(backup, monitored, true, "LTE fallback · moving data to the backup SIM")
    }

    private fun switchDataSim(target: Int, primary: Int, outbound: Boolean, note: String) {
        recordHistory(currentLabel, note, "sim_failover")
        scope.launch {
            try {
                if (moveData(target)) {
                    prefs().edit()
                        .putBoolean(PREF_LTE_FAILOVER_ACTIVE, outbound)
                        .putInt(PREF_LTE_FAILOVER_PRIMARY, primary)
                        .apply()
                }
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
        // Lifetime counters. The keys are shared with the health score and the recovery analytics,
        // which read the same two values, so they are declared once in NetworkInsights.
        if (type == "success") {
            editor.putInt(KEY_SUCCESSFUL_ATTEMPTS, store.getInt(KEY_SUCCESSFUL_ATTEMPTS, 0) + 1)
        }
        if (type == "failed") {
            editor.putInt(KEY_FAILED_ATTEMPTS, store.getInt(KEY_FAILED_ATTEMPTS, 0) + 1)
        }
        editor.apply()
    }

    private fun formatInterval(ms: Long): String {
        val seconds = (ms / 1_000L).toInt()
        return if (seconds >= 60) "${seconds / 60} min" else "${seconds}s"
    }

    /** `m:ss` (or `h:mm`) countdown, used wherever a wait is shown live. */
    private fun formatCountdown(ms: Long): String {
        val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes >= 60L) {
            String.format(Locale.US, "%dh %02dm", minutes / 60L, minutes % 60L)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
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
        val coolingDown = circuitOpen && remainingUntilNextAttemptMs() > 0L
        val action = when {
            coolingDown -> ACTION_RETRY
            paused -> ACTION_RESUME
            else -> ACTION_PAUSE
        }
        val actionLabel = when {
            coolingDown -> "Retry"
            paused -> "Resume"
            else -> "Pause"
        }
        val actionIcon = when {
            coolingDown -> android.R.drawable.ic_menu_rotate
            paused -> android.R.drawable.ic_media_play
            else -> android.R.drawable.ic_media_pause
        }
        val actionIntent = PendingIntent.getService(
            this, 701,
            Intent(this, AutoReconnectService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 700,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val summary = when {
            health != ShizukuHealth.READY -> "Shizuku unavailable"
            paused -> "Paused"
            coolingDown -> "Cooling down · retry scheduled"
            operationRunning -> "Recovering network…"
            network == "5G" -> "5G stable · watching for fallback"
            network == "4G / LTE" -> "LTE detected · watching for 5G"
            network == "Unknown" -> "Waiting for network"
            else -> "Monitoring · $network"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_network)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentTitle("5G Reconnect")
            .setContentText(summary)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(actionIcon, actionLabel, actionIntent)
            .build()
    }

    /**
     * Pushes [status] to the ongoing notification without touching the stored status. Used for short
     * lived progress lines (an in-flight attempt, a SIM switch result) that should not linger as the
     * app's state once the watcher moves on.
     */
    private fun updateOngoing(network: String, status: String) {
        if (!foregroundStarted) return
        val fingerprint = listOf(
            network,
            paused,
            circuitOpen,
            operationRunning,
            ShizukuHealthMonitor.state,
            prefs().getBoolean(PREF_AUTO_ENABLED, false),
        ).joinToString("|")
        if (fingerprint == lastNotificationFingerprint) return
        lastNotificationFingerprint = fingerprint
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(network, status))
        }
    }
}
