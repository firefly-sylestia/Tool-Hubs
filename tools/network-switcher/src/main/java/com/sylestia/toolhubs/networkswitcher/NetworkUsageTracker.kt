package com.sylestia.toolhubs.networkswitcher

import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Today's and lifetime observed traffic, plus speed samples, as the dashboard needs them. */
data class UsageTotals(
    val todayLte: Long,
    val todayFiveG: Long,
    val lifetimeLte: Long,
    val lifetimeFiveG: Long,
    val todayPeakBps: Long,
    val todayPeakAt: Long,
    val todayPeakNetwork: String?,
    val currentBps: Long,
)

/**
 * The device's aggregate mobile counter against the app's own attribution, for one watcher session.
 *
 * [unexplained] is the honest residual: bytes the device counted that the app never saw because the
 * watcher was off, the radio state was unknown, or the sample was rejected. It is expected to be
 * positive — a negative value would mean the app credited more traffic than the device moved.
 */
data class UsageReconciliation(
    val sinceAt: Long,
    val deviceTotal: Long,
    val attributedLte: Long,
    val attributedFiveG: Long,
    val unattributed: Long,
    val rejected: Long,
) {
    val attributed: Long get() = attributedLte + attributedFiveG

    val unexplained: Long get() = (deviceTotal - attributed - unattributed - rejected).coerceAtLeast(0L)

    /** True when the parts still add up to what the device reported. */
    val balanced: Boolean get() = attributed + unattributed + rejected <= deviceTotal
}

/** One calendar day of observed traffic, used to build the history list. */
data class DailyUsage(
    val stamp: String,
    val label: String,
    val lte: Long,
    val fiveG: Long,
    val peakBps: Long,
)

/**
 * Tracks device-wide mobile traffic observed while the watcher is alive.
 *
 * Android exposes aggregate mobile traffic, not carrier-billing bytes split by RAT. Each counter
 * delta is attributed to the radio state active during the preceding sample interval. This is an
 * observation, not an exact billing counter.
 *
 * Two accuracy rules matter here:
 *
 *  * **Counters are bucketed per calendar day.** Lifetime totals are still kept, but the dashboard
 *    reads today's bucket, so the numbers reset at midnight and tomorrow starts from zero instead of
 *    climbing forever.
 *  * **Baselines are session-local.** `TrafficStats` keeps counting while the watcher is stopped, so
 *    restoring the previous session's counter and subtracting it dumped *all* traffic used while the
 *    watcher was off onto whichever radio happened to be labelled last. The baseline now starts
 *    fresh each time the watcher starts, and a delta is only ever attributed to two consecutive
 *    samples from the same session.
 *
 * Each delta is deliberately attributed to the radio that was active during the interval, and bytes
 * observed while the network was unknown are dropped rather than assigned to 4G or 5G.
 *
 * A third rule was added after users kept seeing impossible figures on the Data page:
 *
 *  * **A rate has to be physically possible.** Aggregate mobile counters can leap upward when the
 *    interface set changes (a RAT/SIM switch, a VPN appearing) without the counter ever going
 *    backwards, so a reset check alone does not catch it. Anything faster than
 *    [MAX_PLAUSIBLE_BPS] is treated as a counter discontinuity: the bytes are excluded, the speed
 *    peak is not touched, and the burst is reported via [onImplausibleBurst] so it shows up in the
 *    event history instead of being silently dropped. Sampling windows shorter than
 *    [MIN_TRUSTED_WINDOW_MS] are never turned into a rate at all — dividing a burst by a few
 *    milliseconds is what used to manufacture gigabit-per-second readings.
 */
class NetworkUsageTracker(context: Context) {
    private val prefsContext = context.applicationContext
    private val prefs = context.getSharedPreferences(AutoReconnectService.PREFS_NAME, Context.MODE_PRIVATE)

    private var lastPersistAt = 0L
    private var pendingLte = 0L
    private var pending5G = 0L

    /** Bytes this session observed but could not attribute to a radio at all. */
    private var pendingUnattributed = 0L

    /** Bytes this session rejected as an impossible burst. */
    private var pendingRejected = 0L

    /** Rolling suffix that keeps interval ids unique inside the same millisecond. */
    private var intervalSeq = 0L

    /** Device counter when this session's baseline was taken, or -1 before the first sample. */
    private var sessionStartTotal = -1L

    // Session-local baselines. Intentionally not restored from disk — see the class comment.
    // Usage and speed share one window, so a single pair of baselines drives both.
    private var lastTotal = -1L
    private var lastNetwork: String? = null
    private var lastElapsed = -1L
    private var lastSpeedPersistAt = 0L

    /**
     * Called when a burst is discarded as a counter discontinuity, so the watcher can surface it in
     * the event history rather than dropping it silently. Invoked on the watcher thread.
     */
    var onImplausibleBurst: ((network: String, bytesPerSecond: Long) -> Unit)? = null

    // Current minute bucket. We keep one-minute intervals so the history screen can show exactly
    // when data moved without creating thousands of SharedPreferences writes.
    private var intervalId = -1L
    private var intervalStartAt = -1L
    private var intervalEndAt = -1L
    private var intervalNetwork: String? = null
    private var intervalBytes = 0L
    private var intervalPeakBps = 0L

    /** Ignore traffic samples while telephony is changing accounting state during a call. */
    private var pausedForCall = false

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun sample(network: String) {
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        // TrafficStats.UNSUPPORTED is -1. Never add a valid counter to an unsupported one.
        if (rx < 0L || tx < 0L) return
        val total = rx + tx
        if (total < 0L) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()

        if (pausedForCall) return

        // Anchor the reconciliation baseline before anything is credited, so the 4G/5G split can be
        // checked against the raw device counter for this session.
        ensureSessionBaseline(total)

        // A RAT/SIM transition can make the kernel's aggregate mobile counter jump or restart.
        // Treat that sample as a new baseline instead of turning the discontinuity into fake data.
        val counterReset = lastTotal >= 0L && total < lastTotal
        val networkChanged = lastNetwork != null && network != lastNetwork
        if (counterReset || networkChanged) {
            flushInterval()
            persist(dayFormat.format(Date(nowWall)), nowWall)
            rebaseline(total, nowElapsed, network)
            return
        }

        val haveBaseline = lastTotal >= 0L && lastElapsed >= 0L && nowElapsed > lastElapsed && total >= lastTotal
        if (!haveBaseline) {
            rebaseline(total, nowElapsed, network)
            return
        }

        val delta = total - lastTotal
        val intervalMs = nowElapsed - lastElapsed

        // Too short to be a rate. Keep the baseline moving so the next window is honest, but do not
        // credit these bytes to a radio, a minute bucket or the speed peak.
        if (intervalMs < MIN_TRUSTED_WINDOW_MS) {
            pendingUnattributed += delta
            rebaseline(total, nowElapsed, network)
            return
        }

        val bytesPerSecond = delta * 1000L / intervalMs

        // Faster than the link can physically go: a counter discontinuity, not traffic. Exclude the
        // bytes, leave today's and the lifetime speed peak untouched, re-baseline, and let the
        // watcher record a visible note so the user is told instead of silently losing data.
        if (bytesPerSecond > MAX_PLAUSIBLE_BPS) {
            flushInterval()
            // Counted in the reconciliation as rejected, so the device total still adds up: the
            // counter really did move, we simply refuse to call it traffic.
            pendingRejected += delta
            persist(dayFormat.format(Date(nowWall)), nowWall)
            onImplausibleBurst?.invoke(lastNetwork ?: network, bytesPerSecond)
            rebaseline(total, nowElapsed, network)
            return
        }

        when (lastNetwork) {
            LTE_LABEL -> pendingLte += delta
            FIVE_G_LABEL -> pending5G += delta
            // Bytes moved while the radio state was unknown. They are deliberately not assigned to
            // 4G or 5G, but they are still reported so the totals can be reconciled.
            else -> pendingUnattributed += delta
        }
        recordInterval(
            network = lastNetwork ?: network,
            bytes = delta,
            peakBps = bytesPerSecond,
            wall = nowWall,
        )
        recordSpeed(bytesPerSecond, network, nowWall)

        val day = dayFormat.format(Date(nowWall))
        if (network != lastNetwork || nowWall - lastPersistAt >= PERSIST_EVERY_MS) {
            persist(day, nowWall)
        }

        rebaseline(total, nowElapsed, network)
    }

    /** Flush pending traffic and forget the counter baseline before an active phone call. */
    fun pauseForCall() {
        if (pausedForCall) return
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        if (rx >= 0L && tx >= 0L) {
            persist(dayFormat.format(Date()), System.currentTimeMillis())
        }
        pausedForCall = true
        flushInterval()
        resetBaselines()
    }

    /** Resume with a fresh baseline so call-time counter changes cannot become fake traffic. */
    fun resumeAfterCall() {
        pausedForCall = false
        resetBaselines()
    }

    private fun resetBaselines() {
        lastTotal = -1L
        lastNetwork = null
        lastElapsed = -1L
    }

    /** Moves every baseline to the current sample without crediting its bytes to anything. */
    private fun rebaseline(total: Long, elapsed: Long, network: String) {
        lastTotal = total
        lastElapsed = elapsed
        lastNetwork = network
    }

    /**
     * Establishes the device-counter baseline the reconciliation is measured from.
     *
     * A counter that went backwards means a reboot or a kernel reset, so the previous baseline and
     * every session total are discarded together — otherwise the reconciliation would compare a
     * fresh counter against a pre-reboot baseline and report a huge bogus gap.
     */
    private fun ensureSessionBaseline(total: Long) {
        // The in-memory value doubles as a cache so the common path costs no preference read.
        if (sessionStartTotal >= 0L && total >= sessionStartTotal) return
        val stored = prefs().getLong(KEY_SESSION_START_TOTAL, -1L)
        if (stored >= 0L && total >= stored) {
            sessionStartTotal = stored
            return
        }
        sessionStartTotal = total
        prefs().edit()
            .putLong(KEY_SESSION_START_TOTAL, total)
            .putLong(KEY_SESSION_START_AT, System.currentTimeMillis())
            .putLong(KEY_SESSION_LTE, 0L)
            .putLong(KEY_SESSION_5G, 0L)
            .putLong(KEY_SESSION_UNATTRIBUTED, 0L)
            .putLong(KEY_SESSION_REJECTED, 0L)
            .apply()
    }

    /**
     * Ids must be unique per record: the review list toggles a row by id, so sharing a bucket made
     * "mark interval fake" flip a different record and appear to do nothing. Wall-clock milliseconds
     * alone can collide when one bucket is flushed twice in the same millisecond, hence the suffix.
     */
    private fun nextIntervalId(): Long {
        intervalSeq = (intervalSeq + 1L) % 1_000L
        return System.currentTimeMillis() * 1_000L + intervalSeq
    }

    /**
     * Persists the live speed (throttled), today's peak (resets at midnight) and the lifetime best.
     *
     * The two peaks are tracked separately so starting a new day — when the day bucket is empty and the
     * first sample automatically beats it — cannot overwrite the lifetime record with a lower value.
     */
    private fun recordSpeed(bytesPerSecond: Long, network: String, wall: Long) {
        val day = dayFormat.format(Date(wall))
        val editor = prefs.edit()
        if (wall - lastSpeedPersistAt >= SPEED_PERSIST_MS) {
            lastSpeedPersistAt = wall
            editor.putLong(KEY_CURRENT_BPS, bytesPerSecond)
        }
        val dayPeakKey = "$KEY_PEAK_BPS:$day"
        if (bytesPerSecond > prefs.getLong(dayPeakKey, 0L)) {
            editor.putLong(dayPeakKey, bytesPerSecond)
            editor.putLong("$KEY_PEAK_AT:$day", wall)
            editor.putString("$KEY_PEAK_NETWORK:$day", network)
        }
        if (bytesPerSecond > prefs.getLong(KEY_PEAK_BPS, 0L)) {
            editor.putLong(KEY_PEAK_BPS, bytesPerSecond)
            editor.putLong(KEY_PEAK_AT, wall)
            editor.putString(KEY_PEAK_NETWORK, network)
        }
        editor.apply()
    }

    private fun persist(day: String, wall: Long) {
        prefs.edit()
            .putLong(KEY_LTE_BYTES, prefs.getLong(KEY_LTE_BYTES, 0L) + pendingLte)
            .putLong(KEY_5G_BYTES, prefs.getLong(KEY_5G_BYTES, 0L) + pending5G)
            .putLong("$KEY_LTE_BYTES:$day", prefs.getLong("$KEY_LTE_BYTES:$day", 0L) + pendingLte)
            .putLong("$KEY_5G_BYTES:$day", prefs.getLong("$KEY_5G_BYTES:$day", 0L) + pending5G)
            .putLong(KEY_SESSION_LTE, prefs.getLong(KEY_SESSION_LTE, 0L) + pendingLte)
            .putLong(KEY_SESSION_5G, prefs.getLong(KEY_SESSION_5G, 0L) + pending5G)
            .putLong(KEY_SESSION_UNATTRIBUTED, prefs.getLong(KEY_SESSION_UNATTRIBUTED, 0L) + pendingUnattributed)
            .putLong(KEY_SESSION_REJECTED, prefs.getLong(KEY_SESSION_REJECTED, 0L) + pendingRejected)
            .apply()
        pendingLte = 0L
        pending5G = 0L
        pendingUnattributed = 0L
        pendingRejected = 0L
        lastPersistAt = wall
    }

    fun flush() {
        flushInterval()
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        if (rx < 0L || tx < 0L) return
        val nowWall = System.currentTimeMillis()
        persist(dayFormat.format(Date(nowWall)), nowWall)
    }

    private fun recordInterval(network: String, bytes: Long, peakBps: Long, wall: Long) {
        if (bytes <= 0L) return
        val bucket = (wall / INTERVAL_MS) * INTERVAL_MS
        if (intervalId != bucket) {
            flushInterval()
            intervalId = bucket
            intervalStartAt = wall
            intervalEndAt = wall
            intervalNetwork = network
            intervalBytes = 0L
            intervalPeakBps = 0L
        }
        if (intervalNetwork != network) {
            flushInterval()
            intervalId = bucket
            intervalStartAt = wall
            intervalEndAt = wall
            intervalNetwork = network
            intervalBytes = 0L
            intervalPeakBps = 0L
        }
        intervalEndAt = wall
        intervalBytes += bytes
        intervalPeakBps = maxOf(intervalPeakBps, peakBps)
    }

    private fun flushInterval() {
        val network = intervalNetwork
        if (intervalId < 0L || network == null || intervalBytes <= 0L) return
        UsageHistoryStore.append(
            context = prefsContext,
            interval = UsageInterval(
                id = nextIntervalId(),
                startAt = intervalStartAt,
                endAt = intervalEndAt,
                network = network,
                bytes = intervalBytes,
                peakBps = intervalPeakBps,
                suspicious = intervalPeakBps >= SUSPECT_SPIKE_BPS,
                fake = false,
            ),
        )
        intervalId = -1L
        intervalStartAt = -1L
        intervalEndAt = -1L
        intervalNetwork = null
        intervalBytes = 0L
        intervalPeakBps = 0L
    }

    companion object {
        const val LTE_LABEL = "4G / LTE"
        const val FIVE_G_LABEL = "5G"

        const val KEY_LTE_BYTES = "usage_lte_bytes"
        const val KEY_5G_BYTES = "usage_5g_bytes"
        const val KEY_PEAK_BPS = "usage_peak_bps"
        const val KEY_PEAK_AT = "usage_peak_at"
        const val KEY_PEAK_NETWORK = "usage_peak_network"
        const val KEY_CURRENT_BPS = "usage_current_bps"

        // Session reconciliation: the raw device counter at baseline time, and how this session's
        // observed bytes were divided. These are what let the 4G/5G split be checked against the
        // device instead of being taken on trust.
        const val KEY_SESSION_START_TOTAL = "usage_session_start_total"
        const val KEY_SESSION_START_AT = "usage_session_start_at"
        const val KEY_SESSION_LTE = "usage_session_lte"
        const val KEY_SESSION_5G = "usage_session_5g"
        const val KEY_SESSION_UNATTRIBUTED = "usage_session_unattributed"
        const val KEY_SESSION_REJECTED = "usage_session_rejected"

        const val PERSIST_EVERY_MS = 30_000L
        private const val SPEED_PERSIST_MS = 2_000L

        /**
         * A sampling window shorter than this is never turned into a rate. The watcher ticks once a
         * second, so a much shorter window means the sample was re-scheduled; dividing a counter
         * jump by a few milliseconds is what used to manufacture gigabit-per-second "spikes".
         */
        private const val MIN_TRUSTED_WINDOW_MS = 500L

        /**
         * The most a phone's cellular link can plausibly move in one second. Deliberately far above
         * real-world 5G (a few hundred Mbit/s, ~1.7 Gbit/s only in ideal mmWave conditions) and far
         * below the multi-gigabyte leaps a counter discontinuity produces, so a burst past it is
         * treated as a counter glitch rather than traffic.
         */
        const val MAX_PLAUSIBLE_BPS = 200L * 1024L * 1024L

        /**
         * Rates at or above this are still counted — they are possible on fast 5G — but are flagged
         * so the interval appears in the History review list instead of passing unnoticed.
         */
        const val SUSPECT_SPIKE_BPS = 100L * 1024L * 1024L

        const val INTERVAL_MS = 60_000L

        private fun prefs(context: Context) =
            context.getSharedPreferences(AutoReconnectService.PREFS_NAME, Context.MODE_PRIVATE)

        private fun dayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        /** The `yyyy-MM-dd` stamp for right now, shared with the watcher's warning logic. */
        fun todayStamp(): String = dayFormat().format(Date())

        /** Today's value for [key] (`KEY_LTE_BYTES` / `KEY_5G_BYTES`). Resets at midnight. */
        fun todayBytes(context: Context, key: String): Long =
            prefs(context).getLong("$key:" + todayStamp(), 0L).coerceAtLeast(0L)

        fun readIntervals(context: Context, days: Int = 7): List<UsageInterval> =
            UsageHistoryStore.read(context, System.currentTimeMillis() - days.coerceAtLeast(1) * 24L * 60L * 60L * 1000L)

        fun toggleIntervalFake(context: Context, id: Long): UsageInterval? {
            val updated = UsageHistoryStore.toggleFake(context, id) ?: return null
            applyIntervalAccounting(context, updated)
            recomputeTodayPeak(context, dayFormat().format(Date(updated.startAt)))
            return updated
        }

        /**
         * Marks every flagged spike fake in one pass and returns how many rows changed.
         *
         * Each row is flipped through the same single-row path, so the byte accounting is identical
         * to marking them one at a time; the speed peak is recomputed once at the end instead of once
         * per row, which would re-read the whole history file every time.
         */
        fun markAllSuspiciousFake(context: Context): Int {
            val targets = UsageHistoryStore
                .read(context, System.currentTimeMillis() - 14L * 24L * 60L * 60L * 1000L)
                .filter { it.suspicious && !it.fake }
            var changed = 0
            val days = mutableSetOf<String>()
            for (row in targets) {
                val updated = UsageHistoryStore.toggleFake(context, row.id) ?: continue
                applyIntervalAccounting(context, updated)
                days += dayFormat().format(Date(updated.startAt))
                changed++
            }
            days.forEach { recomputeTodayPeak(context, it) }
            return changed
        }

        /**
         * Adds or removes one interval's bytes from its day, lifetime and session buckets.
         *
         * The session total is adjusted too, so marking a bad interval fake also improves the counter
         * reconciliation — otherwise correcting the history and the reconciliation would disagree.
         */
        private fun applyIntervalAccounting(context: Context, updated: UsageInterval) {
            val store = prefs(context)
            val day = dayFormat().format(Date(updated.startAt))
            val signed = if (updated.fake) -updated.bytes else updated.bytes
            val fiveG = updated.network == FIVE_G_LABEL
            val key = if (fiveG) KEY_5G_BYTES else KEY_LTE_BYTES
            val sessionKey = if (fiveG) KEY_SESSION_5G else KEY_SESSION_LTE
            store.edit()
                .putLong("$key:$day", (store.getLong("$key:$day", 0L) + signed).coerceAtLeast(0L))
                .putLong(key, (store.getLong(key, 0L) + signed).coerceAtLeast(0L))
                .putLong(sessionKey, (store.getLong(sessionKey, 0L) + signed).coerceAtLeast(0L))
                .apply()
        }

        /**
         * Repairs the buckets when the session reconciliation says they cannot be right.
         *
         * Order matters. Flagged spikes are marked fake first, because that is a correction the user
         * can see and undo row by row. Only if the totals are *still* over what the device counter
         * moved are the numbers themselves trimmed, and the returned message always says which of the
         * two happened — this never silently rewrites history.
         */
        fun autoCorrect(context: Context): String {
            val before = readReconciliation(context)
                ?: return "Nothing to check yet — the watcher needs a sample first."
            if (before.balanced) {
                return "Already consistent: attributed plus set aside fits inside the device total."
            }

            val flagged = markAllSuspiciousFake(context)
            val after = readReconciliation(context) ?: before
            if (after.balanced) {
                return "Corrected by marking $flagged flagged interval${if (flagged == 1) "" else "s"} fake."
            }

            val excess = (after.attributed + after.unattributed + after.rejected - after.deviceTotal)
                .coerceAtLeast(0L)
            if (excess <= 0L) return "Nothing to correct."

            val store = prefs(context)
            val day = todayStamp()
            val lteKey = "$KEY_LTE_BYTES:$day"
            val fiveGKey = "$KEY_5G_BYTES:$day"
            val lte = store.getLong(lteKey, 0L)
            val fiveG = store.getLong(fiveGKey, 0L)

            // Take the excess out of the larger bucket first, then the smaller one.
            var remaining = excess
            val trimLte: Long
            val trimFiveG: Long
            if (lte >= fiveG) {
                trimLte = minOf(remaining, lte)
                remaining -= trimLte
                trimFiveG = minOf(remaining, fiveG)
            } else {
                trimFiveG = minOf(remaining, fiveG)
                remaining -= trimFiveG
                trimLte = minOf(remaining, lte)
            }

            store.edit()
                .putLong(lteKey, (lte - trimLte).coerceAtLeast(0L))
                .putLong(fiveGKey, (fiveG - trimFiveG).coerceAtLeast(0L))
                .putLong(KEY_LTE_BYTES, (store.getLong(KEY_LTE_BYTES, 0L) - trimLte).coerceAtLeast(0L))
                .putLong(KEY_5G_BYTES, (store.getLong(KEY_5G_BYTES, 0L) - trimFiveG).coerceAtLeast(0L))
                .putLong(KEY_SESSION_LTE, (store.getLong(KEY_SESSION_LTE, 0L) - trimLte).coerceAtLeast(0L))
                .putLong(KEY_SESSION_5G, (store.getLong(KEY_SESSION_5G, 0L) - trimFiveG).coerceAtLeast(0L))
                .apply()
            recomputeTodayPeak(context, day)

            return "Marked $flagged spike${if (flagged == 1) "" else "s"} fake, then removed " +
                humanBytes(trimLte + trimFiveG) + " from today's totals to match the device counter."
        }

        private fun humanBytes(bytes: Long): String = when {
            bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824f)
            bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576f)
            bytes >= 1_024L -> String.format(Locale.US, "%.0f KB", bytes / 1_024f)
            else -> "$bytes B"
        }

        /** Which of today's counters a reset clears. */
        enum class ResetScope { ALL, LTE, FIVE_G, PEAK }

        /**
         * Clears today's buckets.
         *
         * The scope is explicit because "reset the 4G number" and "reset everything" are different
         * intentions: clearing one field must not silently wipe the other two.
         */
        fun resetToday(context: Context, scope: ResetScope = ResetScope.ALL) {
            val today = todayStamp()
            val store = prefs(context)
            val editor = store.edit()
            when (scope) {
                ResetScope.ALL -> {
                    editor.putLong("$KEY_LTE_BYTES:$today", 0L)
                        .putLong("$KEY_5G_BYTES:$today", 0L)
                        .putLong("$KEY_PEAK_BPS:$today", 0L)
                        .putLong("$KEY_PEAK_AT:$today", 0L)
                        .remove("$KEY_PEAK_NETWORK:$today")
                        .putLong(KEY_CURRENT_BPS, 0L)
                        .apply()
                    UsageHistoryStore.clearDay(context, today, null)
                }

                ResetScope.LTE -> {
                    editor.putLong("$KEY_LTE_BYTES:$today", 0L).apply()
                    UsageHistoryStore.clearDay(context, today, LTE_LABEL)
                    recomputeTodayPeak(context, today)
                }

                ResetScope.FIVE_G -> {
                    editor.putLong("$KEY_5G_BYTES:$today", 0L).apply()
                    UsageHistoryStore.clearDay(context, today, FIVE_G_LABEL)
                    recomputeTodayPeak(context, today)
                }

                ResetScope.PEAK -> editor
                    .putLong("$KEY_PEAK_BPS:$today", 0L)
                    .putLong("$KEY_PEAK_AT:$today", 0L)
                    .remove("$KEY_PEAK_NETWORK:$today")
                    .putLong(KEY_CURRENT_BPS, 0L)
                    .apply()
            }
        }

        /**
         * The device's own mobile counter against what the app attributed, for the current session.
         *
         * Android gives no per-radio billing breakdown, but it does give one aggregate counter. That
         * is enough to prove the split is self-consistent: whatever is attributed to 4G and 5G, plus
         * what was deliberately set aside, must not add up to more than the device moved.
         */
        fun readReconciliation(context: Context): UsageReconciliation? {
            val store = prefs(context)
            val start = store.getLong(KEY_SESSION_START_TOTAL, -1L)
            if (start < 0L) return null
            val rx = TrafficStats.getMobileRxBytes()
            val tx = TrafficStats.getMobileTxBytes()
            if (rx < 0L || tx < 0L) return null
            val deviceTotal = (rx + tx - start).coerceAtLeast(0L)
            return UsageReconciliation(
                sinceAt = store.getLong(KEY_SESSION_START_AT, 0L),
                deviceTotal = deviceTotal,
                attributedLte = store.getLong(KEY_SESSION_LTE, 0L),
                attributedFiveG = store.getLong(KEY_SESSION_5G, 0L),
                unattributed = store.getLong(KEY_SESSION_UNATTRIBUTED, 0L),
                rejected = store.getLong(KEY_SESSION_REJECTED, 0L),
            )
        }

        private fun recomputeTodayPeak(context: Context, day: String) {
            if (day != todayStamp()) return
            val store = prefs(context)
            val peak = UsageHistoryStore.read(context, System.currentTimeMillis() - 24L * 60L * 60L * 1000L)
                .filter { dayFormat().format(Date(it.startAt)) == day && !it.fake }
                .maxByOrNull { it.peakBps }
            store.edit()
                .putLong("$KEY_PEAK_BPS:$day", peak?.peakBps ?: 0L)
                .putLong("$KEY_PEAK_AT:$day", peak?.endAt ?: 0L)
                .putString("$KEY_PEAK_NETWORK:$day", peak?.network ?: "")
                .apply()
        }

        fun readTotals(context: Context): UsageTotals {
            val store = prefs(context)
            val today = todayStamp()
            return UsageTotals(
                todayLte = store.getLong("$KEY_LTE_BYTES:$today", 0L).coerceAtLeast(0L),
                todayFiveG = store.getLong("$KEY_5G_BYTES:$today", 0L).coerceAtLeast(0L),
                lifetimeLte = store.getLong(KEY_LTE_BYTES, 0L),
                lifetimeFiveG = store.getLong(KEY_5G_BYTES, 0L),
                todayPeakBps = store.getLong("$KEY_PEAK_BPS:$today", 0L).coerceAtMost(MAX_PLAUSIBLE_BPS),
                todayPeakAt = store.getLong("$KEY_PEAK_AT:$today", 0L),
                todayPeakNetwork = store.getString("$KEY_PEAK_NETWORK:$today", null),

                currentBps = store.getLong(KEY_CURRENT_BPS, 0L).coerceAtMost(MAX_PLAUSIBLE_BPS),
            )
        }

        fun totalForLastDays(context: Context, key: String, days: Int): Long {
            val store = prefs(context)
            val formatter = dayFormat()
            val calendar = Calendar.getInstance()
            return (0 until days).sumOf { offset ->
                calendar.timeInMillis = System.currentTimeMillis()
                calendar.add(Calendar.DAY_OF_YEAR, -offset)
                store.getLong("$key:" + formatter.format(calendar.time), 0L)
            }
        }

        /** Most-recent-first daily buckets, for the history list on the Data page. */
        fun dailySeries(context: Context, days: Int): List<DailyUsage> {
            val store = prefs(context)
            val keyFormatter = dayFormat()
            val labelFormatter = SimpleDateFormat("EEE dd MMM", Locale.getDefault())
            val calendar = Calendar.getInstance()
            return (0 until days).map { offset ->
                calendar.timeInMillis = System.currentTimeMillis()
                calendar.add(Calendar.DAY_OF_YEAR, -offset)
                val stamp = keyFormatter.format(calendar.time)
                DailyUsage(
                    stamp = stamp,
                    label = labelFormatter.format(calendar.time),
                    lte = store.getLong("$KEY_LTE_BYTES:$stamp", 0L).coerceAtLeast(0L),
                    fiveG = store.getLong("$KEY_5G_BYTES:$stamp", 0L).coerceAtLeast(0L),
                    peakBps = store.getLong("$KEY_PEAK_BPS:$stamp", 0L).coerceAtMost(MAX_PLAUSIBLE_BPS),
                )
            }
        }
    }
}
