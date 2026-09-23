package com.sylestia.toolhubs.datawarden

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataWardenService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val checker = object : Runnable {
        override fun run() {
            enforceThreshold()
            handler.postDelayed(this, 60000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(801, notification("Data protection is active"))
        handler.post(checker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        enforceThreshold()
        return START_STICKY
    }

    private fun enforceThreshold() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString(KEY_BLOCKED_DAY, "") != today()) {
            prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, emptySet()).putString(KEY_BLOCKED_DAY, today()).apply()
            if (ShizukuFirewall.isReady()) ShizukuFirewall.setFirewallEnabled(this, true)
        }
        if (!usageAccessGranted() || !ShizukuFirewall.isReady()) return

        ShizukuFirewall.setFirewallEnabled(this, true)
        val threshold = prefs.getFloat(KEY_THRESHOLD_MB, 500f)
        val includeSystem = prefs.getBoolean(KEY_INCLUDE_SYSTEM, true)
        val blocked = prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()).orEmpty().toMutableSet()
        val limit = (threshold * 1024f * 1024f).toLong()

        usage(includeSystem).filter { it.bytes >= limit && !blocked.contains(it.packageName) }.forEach {
            if (ShizukuFirewall.setPackageNetworking(this, it.packageName, false)) {
                blocked.add(it.packageName)
            }
        }

        prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, blocked).putString(KEY_BLOCKED_DAY, today()).apply()
        updateNotification(if (blocked.isEmpty()) "Watching app data usage" else "Blocking " + blocked.size + " app" + if (blocked.size == 1) "" else "s")
    }

    private data class Usage(val packageName: String, val bytes: Long)

    private fun usage(includeSystem: Boolean): List<Usage> {
        val packages = packageManager.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        ).filter { it.uid >= 10000 && (includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0) }
        val totals = HashMap<Int, Long>()
        val nsm = getSystemService(NetworkStatsManager::class.java)
        val end = System.currentTimeMillis()
        val start = end - 86400000L

        fun collect(type: Int) {
            runCatching { nsm.queryDetails(type, null, start, end) }.getOrNull()?.use { stats ->
                val b = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(b)
                    totals[b.uid] = (totals[b.uid] ?: 0L) + b.rxBytes + b.txBytes
                }
            }
        }
        collect(ConnectivityManager.TYPE_WIFI)
        collect(ConnectivityManager.TYPE_MOBILE)
        return packages.map { Usage(it.packageName, totals[it.uid] ?: 0L) }
    }

    private fun usageAccessGranted(): Boolean {
        val ops = getSystemService(android.app.AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("data_warden_protection", "Data Warden protection", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, "data_warden_protection").setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Data Warden").setContentText(text).setOngoing(true).build()

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(801, notification(text))

    override fun onDestroy() { handler.removeCallbacks(checker); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.sylestia.toolhubs.datawarden.START"
        const val ACTION_STOP = "com.sylestia.toolhubs.datawarden.STOP"
        const val ACTION_REFRESH = "com.sylestia.toolhubs.datawarden.REFRESH"
        const val PREFS = "data_warden"
        const val KEY_THRESHOLD_MB = "threshold_mb"
        const val KEY_INCLUDE_SYSTEM = "include_system"
        const val KEY_BACKGROUND = "background_enabled"
        const val KEY_BLOCKED_PACKAGES = "blocked_packages"
        const val KEY_BLOCKED_DAY = "blocked_day"
    }
}
