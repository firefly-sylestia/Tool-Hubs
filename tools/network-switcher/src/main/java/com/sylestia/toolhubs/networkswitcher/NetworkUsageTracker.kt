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
            rebaseline(total, nowElapsed, network)
            return
        }

        val bytesPerSecond = delta * 1000L / intervalMs

        // Faster than the link can physically go: a counter discontinuity, not traffic. Exclude the
        // bytes, leave today's and the lifetime speed peak untouched, re-baseline, and let the
        // watcher record a visible note so the user is told instead of silently losing data.
        if (bytesPerSecond > MAX_PLAUSIBLE_BPS) {
            flushInterval()
            persist(dayFormat.format(Date(nowWall)), nowWall)
            onImplausibleBurst?.invoke(lastNetwork ?: network, bytesPerSecond)
            rebaseline(total, nowElapsed, network)
            return
        }

        when (lastNetwork) {
            LTE_LABEL -> pendingLte += delta
            FIVE_G_LABEL -> pending5G += delta
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
            .apply()
        pendingLte = 0L
        pending5G = 0L
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
                id = intervalId,
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
            val store = prefs(context)
            val day = dayFormat().format(Date(updated.startAt))
            val signed = if (updated.fake) -updated.bytes else updated.bytes
            val key = if (updated.network == FIVE_G_LABEL) KEY_5G_BYTES else KEY_LTE_BYTES
            store.edit()
                .putLong("$key:$day", (store.getLong("$key:$day", 0L) + signed).coerceAtLeast(0L))
                .putLong(key, (store.getLong(key, 0L) + signed).coerceAtLeast(0L))
                .apply()
            recomputeTodayPeak(context, day)
            return updated
        }

        fun resetToday(context: Context) {
            val today = todayStamp()
            val store = prefs(context)
            store.edit()
                .putLong("$KEY_LTE_BYTES:$today", 0L)
                .putLong("$KEY_5G_BYTES:$today", 0L)
                .putLong("$KEY_PEAK_BPS:$today", 0L)
                .putLong("$KEY_PEAK_AT:$today", 0L)
                .remove("$KEY_PEAK_NETWORK:$today")
                .putLong(KEY_CURRENT_BPS, 0L)
                .apply()
            UsageHistoryStore.clearDay(context, today)
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
