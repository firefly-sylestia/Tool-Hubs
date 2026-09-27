package com.sylestia.toolhubs.networkswitcher

import android.content.Context
import android.net.TrafficStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Attributes mobile traffic to observed radio state; it is not carrier billing data. */
class NetworkUsageTracker(context: Context) {
    private val prefs = context.getSharedPreferences(AutoReconnectService.PREFS_NAME, Context.MODE_PRIVATE)
    private var lastPersistAt = 0L
    private var pendingLte = 0L
    private var pending5G = 0L
    private var lastTotal = prefs.getLong(KEY_LAST_TOTAL, -1L)
    private var lastNetwork = prefs.getString(KEY_LAST_NETWORK, null)

    fun sample(network: String) {
        val total = TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()
        if (total < 0) return
        if (lastTotal >= 0 && total >= lastTotal) {
            when (lastNetwork) { "4G / LTE" -> pendingLte += total - lastTotal; "5G" -> pending5G += total - lastTotal }
        }
        val now = System.currentTimeMillis()
        if (network != lastNetwork || now - lastPersistAt >= PERSIST_EVERY_MS) persist(total, network, now)
        lastTotal = total
        lastNetwork = network
    }

    private fun persist(total: Long, network: String, now: Long) {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        prefs.edit().putLong(KEY_LAST_TOTAL, total).putString(KEY_LAST_NETWORK, network)
            .putLong(KEY_LTE_BYTES, prefs.getLong(KEY_LTE_BYTES, 0L) + pendingLte)
            .putLong(KEY_5G_BYTES, prefs.getLong(KEY_5G_BYTES, 0L) + pending5G)
            .putLong("$KEY_LTE_BYTES:$day", prefs.getLong("$KEY_LTE_BYTES:$day", 0L) + pendingLte)
            .putLong("$KEY_5G_BYTES:$day", prefs.getLong("$KEY_5G_BYTES:$day", 0L) + pending5G).apply()
        pendingLte = 0L; pending5G = 0L; lastPersistAt = now
    }

    companion object {
        private const val KEY_LAST_TOTAL = "usage_last_mobile_total"
        private const val KEY_LAST_NETWORK = "usage_last_network"
        const val KEY_LTE_BYTES = "usage_lte_bytes"
        const val KEY_5G_BYTES = "usage_5g_bytes"
        const val PERSIST_EVERY_MS = 30_000L
    }
}
