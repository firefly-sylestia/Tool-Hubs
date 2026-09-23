package com.sylestia.toolhubs.datawarden

import android.app.*
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import rikka.shizuku.Shizuku

class DataWardenService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            enforce()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Background protection is active"))
        handler.post(check)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun enforce() {
        if (!usageAccessGranted()) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val threshold = prefs.getFloat(KEY_THRESHOLD_MB, 500f)
        val includeSystem = prefs.getBoolean(KEY_INCLUDE_SYSTEM, true)
        if (!shizukuReady()) {
            updateNotification("Shizuku permission required to enforce limits")
            return
        }
        val apps = usage(this, includeSystem)
        val limit = (threshold * 1024f * 1024f).toLong()
        val over = apps.count { it.bytes >= limit }
        apps.filter { it.bytes >= limit && !blockedUids().contains(it.uid) }.forEach { block(it.uid) }
        updateNotification("Watching " + over + " app" + (if (over == 1) "" else "s") + " over limit")
    }

    private data class Usage(val uid: Int, val bytes: Long)

    private fun usage(c: Context, includeSystem: Boolean): List<Usage> {
        val pm = c.packageManager
        val packages = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            .filter { it.uid >= 10000 && (includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0) }
        val end = System.currentTimeMillis()
        val start = end - 86_400_000L
        val nsm = c.getSystemService(NetworkStatsManager::class.java)
        val totals = HashMap<Int, Long>()
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
        return packages.map { Usage(it.uid, totals[it.uid] ?: 0L) }
    }

    private fun block(uid: Int) {
        runCatching {
            val p = Shizuku.newProcess(arrayOf("sh", "-c", "cmd netpolicy add restrict-background-blacklist " + uid), null, null)
            p.inputStream.close()
            p.waitFor()
            val set = blockedUids().toMutableSet()
            set.add(uid)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putStringSet(KEY_BLOCKED, set.map(Int::toString).toSet())
                .putString(KEY_BLOCKED_DAY, today())
                .apply()
        }
    }

    private fun blockedUids(): Set<Int> {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (p.getString(KEY_BLOCKED_DAY, "") != today()) return emptySet()
        return p.getStringSet(KEY_BLOCKED, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun usageAccessGranted(): Boolean {
        val ops = getSystemService(android.app.AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun shizukuReady() = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Data Warden protection", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String) = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle("Data Warden")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    override fun onDestroy() {
        handler.removeCallbacks(check)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.sylestia.toolhubs.datawarden.STOP"
        const val PREFS = "data_warden"
        const val KEY_THRESHOLD_MB = "threshold_mb"
        const val KEY_INCLUDE_SYSTEM = "include_system"
        const val KEY_BLOCKED = "blocked_uids"
        const val KEY_BLOCKED_DAY = "blocked_day"
        private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L
        private const val CHANNEL_ID = "data_warden_protection"
        private const val NOTIFICATION_ID = 801
    }
}
