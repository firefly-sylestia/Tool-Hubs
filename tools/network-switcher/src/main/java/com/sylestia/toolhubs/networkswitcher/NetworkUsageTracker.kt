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

data class UsageInterval(
    val id: Long,
    val startAt: Long,
    val endAt: Long,
    val network: String,
    val bytes: Long,
    val peakBps: Long,
    val suspicious: Boolean,
    val fake: Boolean,
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
 */
class NetworkUsageTracker(context: Context) {
    private val prefsContext = context.applicationContext
    private val prefs = context.getSharedPreferences(AutoReconnectService.PREFS_NAME, Context.MODE_PRIVATE)

    private var lastPersistAt = 0L
    private var pendingLte = 0L
    private var pending5G = 0L

    // Session-local baselines. Intentionally not restored from disk — see the class comment.
    private var lastTotal = -1L
    private var lastNetwork: String? = null
    private var lastElapsed = -1L
    private var lastSpeedTotal = -1L
    private var lastSpeedElapsed = -1L
    private var lastSpeedPersistAt = 0L

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
            lastTotal = total
            lastElapsed = nowElapsed
            lastSpeedTotal = total
            lastSpeedElapsed = nowElapsed
            lastNetwork = network
            return
        }

        if (lastTotal >= 0L && lastElapsed >= 0L && nowElapsed > lastElapsed && total >= lastTotal) {
            val delta = total - lastTotal
            val intervalMs = nowElapsed - lastElapsed
            val bytesPerSecond = delta * 1000L / intervalMs

            // Keep suspicious bursts in the history so the user can inspect and mark
            // the exact interval as fake. Only an extreme value beyond the tracking ceiling is
            // treated as a counter discontinuity and excluded automatically.
            if (bytesPerSecond <= MAX_TRACKABLE_BPS) {
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
            }
        }

        if (lastSpeedTotal >= 0L && lastSpeedElapsed >= 0L && nowElapsed > lastSpeedElapsed && total >= lastSpeedTotal) {
            val intervalMs = nowElapsed - lastSpeedElapsed
            val bytesPerSecond = (total - lastSpeedTotal) * 1000L / intervalMs
            if (bytesPerSecond <= MAX_TRACKABLE_BPS) {
                recordSpeed(bytesPerSecond, network, nowWall)
            }
        }

        lastSpeedTotal = total
        lastSpeedElapsed = nowElapsed

        val day = dayFormat.format(Date(nowWall))
        if (network != lastNetwork || nowWall - lastPersistAt >= PERSIST_EVERY_MS) {
            persist(day, nowWall)
        }

        lastTotal = total
        lastNetwork = network
        lastElapsed = nowElapsed
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
        lastSpeedTotal = -1L
        lastSpeedElapsed = -1L
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

        /** Above this, treat the counter jump as a discontinuity instead of real traffic. */
        private const val MAX_TRACKABLE_BPS = 2L * 1024L * 1024L * 1024L

        /** 500 MiB/s+ is suspicious and is shown for manual review instead of being hidden. */
        const val SUSPECT_SPIKE_BPS = 500L * 1024L * 1024L

        const val INTERVAL_MS = 60_000L

        private fun prefs(context: Context) =
            context.getSharedPreferences(AutoReconnectService.PREFS_NAME, Context.MODE_PRIVATE)

        private fun dayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        /** The `yyyy-MM-dd` stamp for right now, shared with the watcher's warning logic. */
        fun todayStamp(): String = dayFormat().format(Date())

        /** Today's value for [key] (`KEY_LTE_BYTES` / `KEY_5G_BYTES`). Resets at midnight. */
        fun todayBytes(context: Context, key: String): Long {
            val store = prefs(context)
            val raw = store.getLong("$key:" + todayStamp(), 0L)
            val fake = readIntervals(context, 1)
                .filter { it.fake && it.network == if (key == KEY_5G_BYTES) FIVE_G_LABEL else LTE_LABEL }
                .sumOf { it.bytes }
            return (raw - fake).coerceAtLeast(0L)
        }

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

        private fun fakeBytes(context: Context, day: String, network: String): Long =
            UsageHistoryStore.read(context, System.currentTimeMillis() - 14L * 24L * 60L * 60L * 1000L)
                .filter { dayFormat().format(Date(it.startAt)) == day && it.fake && it.network == network }
                .sumOf { it.bytes }

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
                todayLte = (store.getLong("$KEY_LTE_BYTES:$today", 0L) - fakeBytes(context, today, LTE_LABEL)).coerceAtLeast(0L),
                todayFiveG = (store.getLong("$KEY_5G_BYTES:$today", 0L) - fakeBytes(context, today, FIVE_G_LABEL)).coerceAtLeast(0L),
                lifetimeLte = store.getLong(KEY_LTE_BYTES, 0L),
                lifetimeFiveG = store.getLong(KEY_5G_BYTES, 0L),
                todayPeakBps = store.getLong("$KEY_PEAK_BPS:$today", 0L).coerceAtMost(MAX_TRACKABLE_BPS),
                todayPeakAt = store.getLong("$KEY_PEAK_AT:$today", 0L),
                todayPeakNetwork = store.getString("$KEY_PEAK_NETWORK:$today", null),

                currentBps = store.getLong(KEY_CURRENT_BPS, 0L).coerceAtMost(MAX_TRACKABLE_BPS),
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
                    lte = (store.getLong("$KEY_LTE_BYTES:$stamp", 0L) - fakeBytes(context, stamp, LTE_LABEL)).coerceAtLeast(0L),
                    fiveG = (store.getLong("$KEY_5G_BYTES:$stamp", 0L) - fakeBytes(context, stamp, FIVE_G_LABEL)).coerceAtLeast(0L),
                    peakBps = store.getLong("$KEY_PEAK_BPS:$stamp", 0L).coerceAtMost(MAX_TRACKABLE_BPS),
                )
            }
        }
    }
}
