package com.sylestia.toolhubs.networkswitcher

import android.content.Context
import android.net.TrafficStats

/**
 * Attributes mobile traffic to the radio state observed by the foreground watcher.
 * This is intentionally a measurement, not a claim about a carrier's billing data.
 */
class NetworkUsageTracker(context: Context) {
    private val prefs = context.getSharedPreferences(AutoReconnectService.PREFS_NAME, Context.MODE_PRIVATE)

    fun sample(network: String) {
        val total = TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()
        if (total < 0) return
        val previous = prefs.getLong(KEY_LAST_TOTAL, -1L)
        val previousNetwork = prefs.getString(KEY_LAST_NETWORK, null)
        if (previous >= 0 && total >= previous) {
            val delta = total - previous
            when (previousNetwork) {
                "4G / LTE" -> prefs.edit().putLong(KEY_LTE_BYTES, prefs.getLong(KEY_LTE_BYTES, 0L) + delta).apply()
                "5G" -> prefs.edit().putLong(KEY_5G_BYTES, prefs.getLong(KEY_5G_BYTES, 0L) + delta).apply()
            }
        }
        prefs.edit().putLong(KEY_LAST_TOTAL, total).putString(KEY_LAST_NETWORK, network).apply()
    }

    companion object {
        private const val KEY_LAST_TOTAL = "usage_last_mobile_total"
        private const val KEY_LAST_NETWORK = "usage_last_network"
        const val KEY_LTE_BYTES = "usage_lte_bytes"
        const val KEY_5G_BYTES = "usage_5g_bytes"
    }
}
