package com.sylestia.toolhubs.networkswitcher

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.provider.Settings
import java.util.Calendar

internal data class AppNetworkUsage(
    val appName: String,
    val packageName: String,
    val bytes: Long,
)

internal object PerAppDataUsage {
    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun openUsageAccess(context: Context) {
        runCatching {
            context.startActivity(
                android.content.Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Today's mobile bytes by UID. Android requires the user to grant Usage Access first. */
    @Suppress("DEPRECATION")
    fun today(context: Context, limit: Int = 8): List<AppNetworkUsage> {
        if (!hasUsageAccess(context)) return emptyList()
        val manager = context.getSystemService(NetworkStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        return context.packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { it.uid >= 0 }
            .distinctBy { it.uid }
            .mapNotNull { app ->
                val bytes = runCatching {
                    manager.queryDetailsForUid(
                        ConnectivityManager.TYPE_MOBILE,
                        null,
                        start,
                        now,
                        app.uid,
                    ).use { stats ->
                        var total = 0L
                        val bucket = NetworkStats.Bucket()
                        while (stats.hasNextBucket()) {
                            if (stats.getNextBucket(bucket)) {
                                total += bucket.rxBytes.coerceAtLeast(0L)
                                total += bucket.txBytes.coerceAtLeast(0L)
                            }
                        }
                        total
                    }
                }.getOrDefault(0L)
                if (bytes <= 0L) return@mapNotNull null
                val label = runCatching {
                    context.packageManager.getApplicationLabel(app).toString()
                }.getOrDefault(app.packageName)
                AppNetworkUsage(label, app.packageName, bytes)
            }
            .groupBy { it.packageName }
            .map { (_, entries) ->
                val first = entries.first()
                first.copy(bytes = entries.sumOf { it.bytes })
            }
            .sortedByDescending { it.bytes }
            .take(limit)
    }
}
