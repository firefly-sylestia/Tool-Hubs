package com.sylestia.toolhubs.networkswitcher

import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tracks device-wide mobile traffic observed while the watcher is alive.
 *
 * Android exposes aggregate mobile traffic, not carrier-billing bytes split by
 * RAT. Each counter delta is attributed to the radio state active during the
 * preceding sample interval. This is an observation, not an exact billing counter.
 */
class NetworkUsageTracker(context: Context) {
    private val prefs = context.getSharedPreferences(AutoReconnectService.PREFS_NAME, Context.MODE_PRIVATE)
    private var lastPersistAt = 0L
    private var pendingLte = 0L
    private var pending5G = 0L
    private var lastTotal = prefs.getLong(KEY_LAST_TOTAL, -1L)
    private var lastNetwork = prefs.getString(KEY_LAST_NETWORK, null)
    private var lastElapsed = prefs.getLong(KEY_LAST_ELAPSED, -1L)
    private var lastSpeedTotal = prefs.getLong(KEY_LAST_SPEED_TOTAL, -1L)
    private var lastSpeedElapsed = prefs.getLong(KEY_LAST_SPEED_ELAPSED, -1L)

    fun sample(network: String) {
        val total = TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()
        if (total < 0L) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (lastTotal >= 0L && total >= lastTotal && lastElapsed >= 0L && nowElapsed >= lastElapsed) {
            when (lastNetwork) {
                "4G / LTE" -> pendingLte += total - lastTotal
                "5G" -> pending5G += total - lastTotal
            }
        }
        val nowWall = System.currentTimeMillis()
        if (lastSpeedTotal >= 0L && total >= lastSpeedTotal && lastSpeedElapsed >= 0L && nowElapsed > lastSpeedElapsed) {
            val bytesPerSecond = ((total - lastSpeedTotal) * 1000L / (nowElapsed - lastSpeedElapsed)).coerceAtLeast(0L)
            if (bytesPerSecond > prefs.getLong(KEY_PEAK_BPS, 0L)) {
                prefs.edit()
                    .putLong(KEY_PEAK_BPS, bytesPerSecond)
                    .putLong(KEY_PEAK_AT, nowWall)
                    .putString(KEY_PEAK_NETWORK, network)
                    .apply()
            }
        }
        lastSpeedTotal = total
        lastSpeedElapsed = nowElapsed
        if (network != lastNetwork || nowWall - lastPersistAt >= PERSIST_EVERY_MS) {
            persist(total, network, nowElapsed, nowWall)
        }
        lastTotal = total
        lastNetwork = network
        lastElapsed = nowElapsed
    }

    private fun persist(total: Long, network: String, elapsed: Long, wall: Long) {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(wall))
        prefs.edit()
            .putLong(KEY_LAST_TOTAL, total)
            .putString(KEY_LAST_NETWORK, network)
            .putLong(KEY_LAST_ELAPSED, elapsed)
            .putLong(KEY_LAST_SPEED_TOTAL, lastSpeedTotal)
            .putLong(KEY_LAST_SPEED_ELAPSED, lastSpeedElapsed)
            .putLong(KEY_LTE_BYTES, prefs.getLong(KEY_LTE_BYTES, 0L) + pendingLte)
            .putLong(KEY_5G_BYTES, prefs.getLong(KEY_5G_BYTES, 0L) + pending5G)
            .putLong("$KEY_LTE_BYTES:" + day, prefs.getLong("$KEY_LTE_BYTES:" + day, 0L) + pendingLte)
            .putLong("$KEY_5G_BYTES:" + day, prefs.getLong("$KEY_5G_BYTES:" + day, 0L) + pending5G)
            .apply()
        pendingLte = 0L
        pending5G = 0L
        lastPersistAt = wall
    }

    fun flush() {
        val total = TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()
        if (total >= 0L) persist(total, lastNetwork ?: "Unknown", SystemClock.elapsedRealtime(), System.currentTimeMillis())
    }

    companion object {
        private const val KEY_LAST_TOTAL = "usage_last_mobile_total"
        private const val KEY_LAST_NETWORK = "usage_last_network"
        private const val KEY_LAST_ELAPSED = "usage_last_elapsed"
        const val KEY_LTE_BYTES = "usage_lte_bytes"
        const val KEY_5G_BYTES = "usage_5g_bytes"
        const val KEY_PEAK_BPS = "usage_peak_bps"
        const val KEY_PEAK_AT = "usage_peak_at"
        const val KEY_PEAK_NETWORK = "usage_peak_network"
        private const val KEY_LAST_SPEED_TOTAL = "usage_last_speed_total"
        private const val KEY_LAST_SPEED_ELAPSED = "usage_last_speed_elapsed"
        const val PERSIST_EVERY_MS = 30_000L

        fun totalForLastDays(context: Context, key: String, days: Int): Long {
            val prefs = context.getSharedPreferences(AutoReconnectService.PREFS_NAME, Context.MODE_PRIVATE)
            val calendar = Calendar.getInstance()
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            return (0 until days).sumOf { offset ->
                calendar.timeInMillis = System.currentTimeMillis()
                calendar.add(Calendar.DAY_OF_YEAR, -offset)
                prefs.getLong("$key:" + formatter.format(calendar.time), 0L)
            }
        }
    }
}
