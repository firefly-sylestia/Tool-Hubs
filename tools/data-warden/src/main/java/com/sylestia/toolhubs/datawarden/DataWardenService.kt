package com.sylestia.toolhubs.datawarden

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataWardenService : VpnService() {
    private val handler = Handler(Looper.getMainLooper())
    private var vpnInterface: android.os.ParcelFileDescriptor? = null

    private val checker = object : Runnable {
        override fun run() {
            enforceThreshold()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Data protection is active"))
        handler.post(checker)
        rebuildFirewall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopFirewall()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH, ACTION_START -> rebuildFirewall()
        }
        return START_STICKY
    }

    private fun enforceThreshold() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString(KEY_BLOCKED_DAY, "") != today()) {
            prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, emptySet()).putString(KEY_BLOCKED_DAY, today()).apply()
            rebuildFirewall()
        }
        if (!usageAccessGranted()) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val threshold = prefs.getFloat(KEY_THRESHOLD_MB, 500f)
        val includeSystem = prefs.getBoolean(KEY_INCLUDE_SYSTEM, true)
        val blocked = blockedPackages().toMutableSet()
        val over = usage(includeSystem).filter { it.bytes >= threshold * 1024f * 1024f }
        over.forEach { blocked.add(it.packageName) }
        if (blocked != blockedPackages()) {
            prefs.edit()
                .putStringSet(KEY_BLOCKED_PACKAGES, blocked)
                .putString(KEY_BLOCKED_DAY, today())
                .apply()
            rebuildFirewall()
        }
        updateNotification("Protected " + blocked.size + " app" + if (blocked.size == 1) "" else "s")
    }

    private data class Usage(val packageName: String, val bytes: Long)

    private fun usage(includeSystem: Boolean): List<Usage> {
        val pm = packageManager
        val packages = pm.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        ).filter {
            it.uid >= 10000 && (includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0)
        }
        val totals = HashMap<Int, Long>()
        val nsm = getSystemService(NetworkStatsManager::class.java)
        val end = System.currentTimeMillis()
        val start = end - 86_400_000L

        fun collect(type: Int) {
            runCatching { nsm.queryDetails(type, null, start, end) }.getOrNull()?.use { stats ->
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    totals[bucket.uid] = (totals[bucket.uid] ?: 0L) + bucket.rxBytes + bucket.txBytes
                }
            }
        }
        collect(ConnectivityManager.TYPE_WIFI)
        collect(ConnectivityManager.TYPE_MOBILE)

        return packages.map {
            Usage(it.packageName, totals[it.uid] ?: 0L)
        }
    }

    /**
     * The VPN is intentionally local and has no upstream tunnel.
     *
     * Only blocked packages are added to the VPN's allowlist. Their traffic is
     * routed into the TUN and is never forwarded, while every other app keeps
     * using the normal network directly.
     */
    private fun rebuildFirewall() {
        if (VpnService.prepare(this) != null) {
            updateNotification("VPN permission required for hard blocking")
            return
        }

        val blocked = blockedPackages().filter { packageExists(it) }.toList()
        vpnInterface?.close()
        vpnInterface = null

        if (blocked.isEmpty()) {
            updateNotification("No apps are currently blocked")
            return
        }

        try {
            val builder = Builder()
                .setSession("Data Warden")
                .setMtu(1500)
                .addAddress("10.88.0.1", 32)
                .addAddress("fd00:88:88::1", 128)
                .addRoute("0.0.0.0", 0)

            if (android.os.Build.VERSION.SDK_INT >= 21) {
                builder.addRoute("::", 0)
            }

            blocked.forEach { builder.addAllowedApplication(it) }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                updateNotification("Could not establish the firewall VPN")
            } else {
                updateNotification("Hard blocking " + blocked.size + " app" + if (blocked.size == 1) "" else "s")
            }
        } catch (t: Throwable) {
            updateNotification("Firewall error: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun packageExists(packageName: String): Boolean =
        runCatching { packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)) }.isSuccess

    private fun blockedPackages(): Set<String> {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString(KEY_BLOCKED_DAY, "") != today()) return emptySet()
        return prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()) ?: emptySet()
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun usageAccessGranted(): Boolean {
        val ops = getSystemService(android.app.AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Data Warden protection", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Data Warden")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    private fun stopFirewall() {
        vpnInterface?.close()
        vpnInterface = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(checker)
        stopFirewall()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopFirewall()
        super.onRevoke()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

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
        private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L
        private const val CHANNEL_ID = "data_warden_protection"
        private const val NOTIFICATION_ID = 801
    }
}
