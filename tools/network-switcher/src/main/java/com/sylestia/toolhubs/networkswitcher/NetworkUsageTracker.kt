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
 */
class NetworkUsageTracker(context: Context) {
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

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun sample(network: String) {
        val total = TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()
        if (total < 0L) return

        val nowElapsed = SystemClock.elapsedRealtime()
        if (lastTotal >= 0L && total >= lastTotal && lastElapsed >= 0L && nowElapsed >= lastElapsed) {
            val delta = total - lastTotal
            when (lastNetwork) {
                LTE_LABEL -> pendingLte += delta
                FIVE_G_LABEL -> pending5G += delta
            }
        }

        val nowWall = System.currentTimeMillis()
        if (lastSpeedTotal >= 0L && total >= lastSpeedTotal && lastSpeedElapsed >= 0L && nowElapsed > lastSpeedElapsed) {
            val bytesPerSecond = ((total - lastSpeedTotal) * 1000L / (nowElapsed - lastSpeedElapsed)).coerceAtLeast(0L)
            recordSpeed(bytesPerSecond, network, nowWall)
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
        val total = TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()
        if (total < 0L) return
        val nowWall = System.currentTimeMillis()
        persist(dayFormat.format(Date(nowWall)), nowWall)
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

        private fun prefs(context: Context) =
            context.getSharedPreferences(AutoReconnectService.PREFS_NAME, Context.MODE_PRIVATE)

        private fun dayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        /** The `yyyy-MM-dd` stamp for right now, shared with the watcher's warning logic. */
        fun todayStamp(): String = dayFormat().format(Date())

        /** Today's value for [key] (`KEY_LTE_BYTES` / `KEY_5G_BYTES`). Resets at midnight. */
        fun todayBytes(context: Context, key: String): Long =
            prefs(context).getLong("$key:" + todayStamp(), 0L)

        fun readTotals(context: Context): UsageTotals {
            val store = prefs(context)
            val today = todayStamp()
            return UsageTotals(
                todayLte = store.getLong("$KEY_LTE_BYTES:$today", 0L),
                todayFiveG = store.getLong("$KEY_5G_BYTES:$today", 0L),
                lifetimeLte = store.getLong(KEY_LTE_BYTES, 0L),
                lifetimeFiveG = store.getLong(KEY_5G_BYTES, 0L),
                todayPeakBps = store.getLong("$KEY_PEAK_BPS:$today", 0L),
                todayPeakAt = store.getLong("$KEY_PEAK_AT:$today", 0L),
                todayPeakNetwork = store.getString("$KEY_PEAK_NETWORK:$today", null),
                currentBps = store.getLong(KEY_CURRENT_BPS, 0L),
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
                    lte = store.getLong("$KEY_LTE_BYTES:$stamp", 0L),
                    fiveG = store.getLong("$KEY_5G_BYTES:$stamp", 0L),
                    peakBps = store.getLong("$KEY_PEAK_BPS:$stamp", 0L),
                )
            }
        }
    }
}
