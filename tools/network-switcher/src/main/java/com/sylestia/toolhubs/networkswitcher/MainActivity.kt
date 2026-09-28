@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sylestia.toolhubs.networkswitcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.telephony.SubscriptionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sylestia.toolhubs.designsystem.CurioCream
import com.sylestia.toolhubs.designsystem.CurioInk
import com.sylestia.toolhubs.designsystem.CurioRoseDark
import com.sylestia.toolhubs.ui.HubScreenHeader
import kotlinx.coroutines.delay
import java.util.Locale
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 100 && grantResult == PackageManager.PERMISSION_GRANTED) {
            runOnUiThread { shizukuGranted = true }
        }
    }
    private var shizukuGranted by mutableStateOf(false)
    private val telephony by lazy { getSystemService(TelephonyManager::class.java) }
    @Volatile private var display5GSignal = false

    private val phoneStateListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        object : PhoneStateListener() {
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                display5GSignal = info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                    info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
            }
        }
    } else null

    private val telephonyCallback by lazy { createTelephonyCallback() }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createTelephonyCallback(): android.telephony.TelephonyCallback =
        object : android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                display5GSignal = info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                    info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 42)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)

        shizukuGranted = Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        ShizukuHealthMonitor.install()
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        registerNetworkObserver()
        setContent { com.sylestia.toolhubs.designsystem.ToolHubsTheme { NetworkScreen() } }
    }

    override fun onDestroy() {
        unregisterNetworkObserver()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun registerNetworkObserver() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephony.registerTelephonyCallback(mainExecutor, telephonyCallback)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                telephony.listen(phoneStateListener, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
            }
        }
    }

    private fun unregisterNetworkObserver() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) telephony.unregisterTelephonyCallback(telephonyCallback)
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                telephony.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    private fun isActually5G(): Boolean {
        val dataType = runCatching { telephony.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        return dataType == TelephonyManager.NETWORK_TYPE_NR || display5GSignal
    }

    private fun currentNetwork(): String = when {
        isActually5G() -> "5G"
        telephony.dataNetworkType == TelephonyManager.NETWORK_TYPE_LTE -> "4G / LTE"
        telephony.dataNetworkType == TelephonyManager.NETWORK_TYPE_HSPAP ||
            telephony.dataNetworkType == TelephonyManager.NETWORK_TYPE_HSPA ||
            telephony.dataNetworkType == TelephonyManager.NETWORK_TYPE_HSDPA ||
            telephony.dataNetworkType == TelephonyManager.NETWORK_TYPE_HSUPA ||
            telephony.dataNetworkType == TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        telephony.dataNetworkType == TelephonyManager.NETWORK_TYPE_EDGE ||
            telephony.dataNetworkType == TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        else -> "Unknown"
    }

    data class SimChoice(val id: Int, val label: String)

    private fun activeSims(): List<SimChoice> = runCatching {
        getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().map {
            SimChoice(it.subscriptionId, "SIM ${it.simSlotIndex + 1} · ${it.carrierName}")
        }
    }.getOrDefault(emptyList())

    private fun diagnostics(): List<Pair<String, String>> {
        val preferred = runCatching {
            val types = telephony.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER)
            if (types and TelephonyManager.NETWORK_TYPE_BITMASK_NR != 0L) "5G allowed" else "5G disabled"
        }.getOrDefault("Unavailable")
        val data = runCatching { if (telephony.isDataEnabled) "Enabled" else "Disabled" }.getOrDefault("Unavailable")
        val carrier5g = runCatching {
            when {
                telephony.dataNetworkType == TelephonyManager.NETWORK_TYPE_NR -> "NR / SA detected"
                display5GSignal -> "NSA advertised"
                else -> "Not currently advertised"
            }
        }.getOrDefault("Unavailable")
        return listOf(
            "Carrier 5G" to carrier5g,
            "Preferred radio" to preferred,
            "Mobile data" to data,
            "Active SIMs" to activeSims().size.toString()
        )
    }

    private fun prefs() = getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE)

    private fun startBackgroundWatcher() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, AutoReconnectService::class.java).setAction(AutoReconnectService.ACTION_START)
        )
    }

    private fun stopBackgroundWatcher() {
        startService(Intent(this, AutoReconnectService::class.java).setAction(AutoReconnectService.ACTION_STOP))
    }

    private fun refreshWatcherSettings() {
        if (prefs().getBoolean(AutoReconnectService.PREF_AUTO_ENABLED, false)) {
            startBackgroundWatcher()
        }
    }

    private fun openBatteryOptimization() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }.recoverCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun hasPhonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun batteryOptimizationAllowed(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun requestMissingPermission(kind: String) {
        when (kind) {
            "phone" -> if (!hasPhonePermission()) requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 42)
            "notifications" -> if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
            "shizuku" -> {
                if (!Shizuku.pingBinder()) {
                    startActivity(packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api"))
                } else Shizuku.requestPermission(100)
            }
            "battery" -> openBatteryOptimization()
        }
    }

    @Composable
    private fun NetworkScreen() {
        var page by rememberSaveable { mutableIntStateOf(0) }
        var refresh by remember { mutableIntStateOf(0) }

        LaunchedEffect(Unit) {
            while (true) {
                refresh++
                shizukuGranted = Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                delay(1000)
            }
        }

        Scaffold(
            containerColor = CurioCream,
            bottomBar = {
                NavigationBar(containerColor = Color.White.copy(alpha = .94f)) {
                    NavigationBarItem(page == 0, { page = 0 }, icon = { Icon(Icons.Rounded.Bolt, null) }, label = { Text("Control") })
                    NavigationBarItem(page == 1, { page = 1 }, icon = { Icon(Icons.Rounded.History, null) }, label = { Text("History") })
                    NavigationBarItem(page == 2, { page = 2 }, icon = { Icon(Icons.Rounded.Analytics, null) }, label = { Text("Usage") })
                    NavigationBarItem(page == 3, { page = 3 }, icon = { Icon(Icons.Rounded.Settings, null) }, label = { Text("Settings") })
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (page) {
                    0 -> ControlPage()
                    1 -> HistoryPage()
                    2 -> UsagePage()
                    else -> SettingsPage()
                }
            }
        }
    }

    @Composable
    private fun PermissionChecker() {
        val phone = hasPhonePermission()
        val notifications = hasNotificationPermission()
        val shizuku = ShizukuHealthMonitor.refresh() == ShizukuHealth.READY
        if (phone && notifications && shizuku) return

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(.72f))
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Required access", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("The checker disappears automatically once everything is ready.", fontSize = 11.sp, color = CurioInk.copy(.55f))
                Spacer(Modifier.height(12.dp))
                PermissionRow("Phone state", phone, "phone")
                PermissionRow("Notifications", notifications, "notifications")
                PermissionRow("Shizuku", shizuku, "shizuku")

            }
        }
    }

    @Composable
    private fun PermissionRow(label: String, allowed: Boolean, kind: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Medium)
                Text(if (allowed) "Allowed" else "Required", fontSize = 11.sp, color = if (allowed) Color(0xFF207561) else CurioRoseDark)
            }
            if (!allowed) TextButton(onClick = { requestMissingPermission(kind) }) { Text("Allow") }
        }
    }

    @Composable
    private fun ControlPage() {
        val context = this
        var network by remember { mutableStateOf(currentNetwork()) }
        var auto by rememberSaveable { mutableStateOf(prefs().getBoolean(AutoReconnectService.PREF_AUTO_ENABLED, false)) }
        var running by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Ready") }
        var pressed by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            while (true) {
                auto = prefs().getBoolean(AutoReconnectService.PREF_AUTO_ENABLED, false)
                network = prefs().getString(AutoReconnectService.PREF_CURRENT_NETWORK, null) ?: currentNetwork()
                status = prefs().getString(AutoReconnectService.PREF_STATUS, null) ?: when {
                    network == "5G" -> if (auto) "5G active · monitoring for LTE fallback" else "5G active"
                    network == "4G / LTE" && auto -> "4G / LTE detected · recovery watcher active"
                    network == "4G / LTE" -> "4G / LTE detected"
                    else -> network
                }
                delay(1000)
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            HubScreenHeader("5G Reconnect", "Automatic 5G recovery when LTE takes over.")
            Spacer(Modifier.height(18.dp))
            PermissionChecker()
            Spacer(Modifier.height(14.dp))

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.72f))) {
                Column(Modifier.padding(22.dp)) {
                    Text("CURRENT NETWORK", color = CurioRoseDark, fontSize = 10.sp)
                    Text(network, color = CurioInk, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(status, color = CurioInk.copy(.62f))
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatic 5G reconnect", fontWeight = FontWeight.Medium)
                            Text("Keeps watching after you leave the app.", fontSize = 11.sp, color = CurioInk.copy(.55f))
                        }
                        Switch(
                            checked = auto,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    auto = false
                                    stopBackgroundWatcher()
                                    status = "Background watcher stopped."
                                } else if (!hasPhonePermission()) {
                                    requestMissingPermission("phone")
                                } else if (ShizukuHealthMonitor.refresh() != ShizukuHealth.READY) {
                                    requestMissingPermission("shizuku")
                                } else {
                                    auto = true
                                    prefs().edit().putBoolean(AutoReconnectService.PREF_AUTO_ENABLED, true).apply()
                                    startBackgroundWatcher()
                                    status = "Background watcher started."
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            val buttonScale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .55f), label = "buttonScale")
            Button(
                onClick = {
                    pressed = true
                    if (ShizukuHealthMonitor.refresh() != ShizukuHealth.READY) {
                        requestMissingPermission("shizuku")
                        pressed = false
                    } else {
                        running = true
                        status = "Re-triggering 5G…"
                        Thread {
                            val result = NetworkSwitcherBridge(context).reconnect5G()
                            val success = waitFor5GBlocking(12_000L)
                            runOnUiThread {
                                status = if (success) "5G switch confirmed." else if (result.startsWith("5G re-selection triggered")) "Trigger sent, but 5G was not detected yet." else result
                                running = false
                                pressed = false
                            }
                        }.start()
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().height(58.dp).scale(buttonScale),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Rounded.Bolt, null)
                Spacer(Modifier.width(8.dp))
                Text(if (running) "Checking 5G…" else "Re-trigger 5G now")
            }

            Spacer(Modifier.height(14.dp))
            val lastEvent = prefs().getString(AutoReconnectService.PREF_LAST_EVENT_DETAIL, null)
            if (!lastEvent.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Last event · $lastEvent", fontSize = 11.sp, color = CurioInk.copy(.48f))
            }
            Text("Background state is owned by the foreground watcher service, not by this screen. Closing Recents therefore does not stop the watcher.", fontSize = 11.sp, color = CurioInk.copy(.48f))
        }
    }

    private fun waitFor5GBlocking(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isActually5G()) return true
            Thread.sleep(500)
        }
        return isActually5G()
    }

    @Composable
    private fun HistoryPage() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            HubScreenHeader("History", "Every observed network and recovery event.")
            Spacer(Modifier.height(14.dp))
            HistoryView(this@MainActivity)
        }
    }

    @Composable
    private fun UsagePage() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            HubScreenHeader("Usage", "Observed mobile traffic and recovery timing.")
            Spacer(Modifier.height(14.dp))
            DataTrackerView(this@MainActivity)
        }
    }

    @Composable
    private fun SettingsPage() {
        val context = this
        val sims = remember { activeSims() }
        var startAfterRestart by rememberSaveable { mutableStateOf(prefs().getBoolean(AutoReconnectService.PREF_START_AFTER_REBOOT, false)) }
        var selectedSim by rememberSaveable { mutableIntStateOf(prefs().getInt(AutoReconnectService.PREF_SELECTED_SUBSCRIPTION, SubscriptionManager.INVALID_SUBSCRIPTION_ID)) }
        var backupSim by rememberSaveable { mutableIntStateOf(prefs().getInt(AutoReconnectService.PREF_FAILOVER_TARGET, SubscriptionManager.INVALID_SUBSCRIPTION_ID)) }
        var simFailover by rememberSaveable { mutableStateOf(prefs().getBoolean(AutoReconnectService.PREF_FAILOVER_ENABLED, false)) }
        var intervalSeconds by rememberSaveable { mutableIntStateOf((prefs().getLong(AutoReconnectService.PREF_INTERVAL_MS, AutoReconnectService.DEFAULT_INTERVAL_MS) / 1000L).toInt()) }
        var recoveryCooldownMinutes by rememberSaveable { mutableIntStateOf((prefs().getLong(AutoReconnectService.PREF_RECOVERY_COOLDOWN_MS, AutoReconnectService.DEFAULT_RECOVERY_COOLDOWN_MS) / 60_000L).toInt()) }
        var maxAttempts by rememberSaveable { mutableIntStateOf(prefs().getInt(AutoReconnectService.PREF_MAX_ATTEMPTS, AutoReconnectService.DEFAULT_MAX_ATTEMPTS).coerceIn(1, 8)) }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            HubScreenHeader("Settings", "Watcher, SIM and recovery behaviour.")
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.62f))) {
                Column(Modifier.padding(18.dp)) {
                    Text("Recovery", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Retry delay: ${intervalSeconds}s", color = CurioInk.copy(.65f))
                    Slider(value = intervalSeconds.toFloat(), onValueChange = { intervalSeconds = it.toInt() }, onValueChangeFinished = { prefs().edit().putLong(AutoReconnectService.PREF_INTERVAL_MS, intervalSeconds * 1000L).apply(); refreshWatcherSettings() }, valueRange = 30f..600f, steps = 18)
                    Text("Attempts per recovery: $maxAttempts", color = CurioInk.copy(.65f))
                    Slider(value = maxAttempts.toFloat(), onValueChange = { maxAttempts = it.toInt() }, onValueChangeFinished = { prefs().edit().putInt(AutoReconnectService.PREF_MAX_ATTEMPTS, maxAttempts).apply(); refreshWatcherSettings() }, valueRange = 1f..8f, steps = 6)
                    Text("Cooldown: $recoveryCooldownMinutes min", color = CurioInk.copy(.65f))
                    Slider(value = recoveryCooldownMinutes.toFloat(), onValueChange = { recoveryCooldownMinutes = it.toInt() }, onValueChangeFinished = { prefs().edit().putLong(AutoReconnectService.PREF_RECOVERY_COOLDOWN_MS, recoveryCooldownMinutes * 60_000L).apply(); refreshWatcherSettings() }, valueRange = 1f..30f, steps = 28)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Start after restart", fontWeight = FontWeight.Medium)
                    Text("Restart the watcher after boot when automatic reconnect is enabled.", fontSize = 11.sp, color = CurioInk.copy(.55f))
                }
                Switch(checked = startAfterRestart, onCheckedChange = {
                    startAfterRestart = it
                    prefs().edit().putBoolean(AutoReconnectService.PREF_START_AFTER_REBOOT, it).apply()
                })
            }
            if (sims.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SimAndDiagnosticsCard(
                    sims, selectedSim, backupSim, simFailover, diagnostics(),
                    onSelected = { id -> selectedSim = id; prefs().edit().putInt(AutoReconnectService.PREF_SELECTED_SUBSCRIPTION, id).apply(); refreshWatcherSettings() },
                    onBackup = { id -> backupSim = id; prefs().edit().putInt(AutoReconnectService.PREF_FAILOVER_TARGET, id).apply() },
                    onFailover = { enabled -> simFailover = enabled; prefs().edit().putBoolean(AutoReconnectService.PREF_FAILOVER_ENABLED, enabled).apply() }
                )
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.55f))) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Battery optimization", fontWeight = FontWeight.Medium)
                        Text("For phones that aggressively stop background services.", fontSize = 11.sp, color = CurioInk.copy(.55f))
                    }
                    TextButton(onClick = { openBatteryOptimization() }) { Text("Open") }
                }
            }
        }
    }
}


private fun shizukuHealthLabel(state: ShizukuHealth) = when (state) {
    ShizukuHealth.UNAVAILABLE -> "unavailable"
    ShizukuHealth.PERMISSION_REQUIRED -> "permission required"
    ShizukuHealth.BINDING -> "binding"
    ShizukuHealth.READY -> "ready"
    ShizukuHealth.SERVICE_DIED -> "service died — retry available"
}

@Composable
private fun SimPicker(sims: List<MainActivity.SimChoice>, selected: Int, onSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = sims.firstOrNull { it.id == selected } ?: sims.first()
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Rounded.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(current.label, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.History, contentDescription = "Choose SIM")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sims.forEach { sim ->
                DropdownMenuItem(text = { Text(sim.label) }, onClick = { onSelected(sim.id); expanded = false }, leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) })
            }
        }
    }
}

@Composable
private fun SimAndDiagnosticsCard(
    sims: List<MainActivity.SimChoice>, selected: Int, backup: Int, failover: Boolean,
    diagnostics: List<Pair<String, String>>, onSelected: (Int) -> Unit,
    onBackup: (Int) -> Unit, onFailover: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.62f))) {
        Column(Modifier.padding(18.dp)) {
            Text("SIM & eligibility", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("Choose the SIM monitored for 5G. Failover only runs after 45 seconds with no cellular service.", fontSize = 11.sp, color = CurioInk.copy(.55f))
            Spacer(Modifier.height(10.dp))
            Text("Monitor this SIM", fontSize = 12.sp, color = CurioRoseDark)
            SimPicker(sims, selected, onSelected)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Switch to backup SIM when service disappears"); Text("A 15-minute safety cooldown prevents SIM flapping.", fontSize = 11.sp, color = CurioInk.copy(.55f)) }
                Switch(checked = failover, onCheckedChange = onFailover)
            }
            if (failover) {
                Text("Backup data SIM", fontSize = 12.sp, color = CurioRoseDark, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { sims.filter { it.id != selected }.forEach { sim -> FilterChip(selected = backup == sim.id, onClick = { onBackup(sim.id) }, label = { Text(sim.label) }) } }
            }
            Spacer(Modifier.height(10.dp))
            diagnostics.forEach { (label, value) -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, fontSize = 12.sp, color = CurioInk.copy(.62f)); Text(value, fontSize = 12.sp, color = CurioInk) } }
        }
    }
}
@Composable
private fun HistoryView(context: android.content.Context) {
    var entries by remember { mutableStateOf(readNetworkHistory(context)) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            entries = readNetworkHistory(context)
            delay(2_000)
        }
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .62f))
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, null, tint = CurioRoseDark)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Reconnect activity")
                    Text("A quick read on the watcher", fontSize = 11.sp, color = CurioInk.copy(alpha = .55f))
                }
                TextButton(onClick = { showAll = true }) { Text("View all") }
            }
            Spacer(Modifier.height(10.dp))
            if (entries.isEmpty()) {
                Text("No network changes recorded yet.", fontSize = 12.sp, color = CurioInk.copy(alpha = .5f))
            } else {
                val successes = entries.count { it.type == "success" || it.detail.contains("confirmed", true) }
                val failures = entries.count { it.type == "failed" || it.detail.contains("failed", true) || it.detail.contains("error", true) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActivityMetric("Success", successes.toString(), Color(0xFF207561), Modifier.weight(1f))
                    ActivityMetric("Failed", failures.toString(), CurioRoseDark, Modifier.weight(1f))
                }
                entries.firstOrNull()?.let { latest ->
                    Spacer(Modifier.height(13.dp))
                    Text("LATEST", fontSize = 10.sp, color = CurioRoseDark)
                    Text(latest.detail.ifBlank { latest.network }, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = CurioInk)
                    Text(latest.time, fontSize = 11.sp, color = CurioInk.copy(alpha = .55f))
                }
            }
        }
    }
    if (showAll) ModalBottomSheet(onDismissRequest = { showAll = false }, containerColor = CurioCream) {
        var filter by rememberSaveable { mutableStateOf("All") }
        var expandedTime by rememberSaveable { mutableLongStateOf(-1L) }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, null, tint = CurioRoseDark)
                Spacer(Modifier.width(8.dp)); Text("Full activity", fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, buildNetworkReport(context, entries)), "Export reconnect report")) }) { Text("Export") }
                TextButton(onClick = { context.getSharedPreferences(AutoReconnectService.PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().remove(AutoReconnectService.PREF_HISTORY).apply(); entries = emptyList() }) { Text("Clear") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("All", "Success", "Failed", "Network", "System").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }) } }
            val shown = entries.filter { entry -> when (filter) { "Success" -> entry.type == "success"; "Failed" -> entry.type == "failed"; "Network" -> entry.type == "network"; "System" -> entry.type == "system"; else -> true } }
            shown.take(80).forEachIndexed { index, entry ->
                Row(Modifier.fillMaxWidth().background(if (index == 0) CurioRoseDark.copy(.10f) else Color.Transparent, RoundedCornerShape(14.dp)).clickable { expandedTime = if (expandedTime == entry.timestamp) -1L else entry.timestamp }.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) { Text(entry.network, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold); Text(entry.detail, fontSize = 12.sp, color = CurioInk.copy(.62f)); if (expandedTime == entry.timestamp) Text("Type: ${entry.type} · Recorded network: ${entry.network}\nTap to collapse. Additional radio and carrier details are shown in SIM & eligibility.", fontSize = 11.sp, color = CurioRoseDark, modifier = Modifier.padding(top = 6.dp)) }
                    Text(entry.time, fontSize = 10.sp, color = CurioRoseDark)
                }
            }
        }
    }
}

@Composable private fun ActivityMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(color.copy(.10f), RoundedCornerShape(16.dp)).padding(12.dp)) { Text(value, fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = color); Text(label, fontSize = 11.sp, color = CurioInk.copy(.60f)) }
}

@Composable private fun DataTrackerView(context: android.content.Context) {
    val prefs = context.getSharedPreferences(AutoReconnectService.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); refresh++ } }
    val lte = refresh.let { prefs.getLong(NetworkUsageTracker.KEY_LTE_BYTES, 0L) }
    val fiveG = refresh.let { prefs.getLong(NetworkUsageTracker.KEY_5G_BYTES, 0L) }
    val liveFallbackSeconds = run {
        val started = prefs.getLong(AutoReconnectService.PREF_FALLBACK_STARTED, 0L)
        if (started > 0L) (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L) / 1000L else 0L
    }
    val fallbackMinutes = prefs.getLong(AutoReconnectService.PREF_LTE_FALLBACK_MS, 0L) / 60_000L + liveFallbackSeconds / 60L
    val recoveries = prefs.getInt(AutoReconnectService.PREF_RECOVERY_COUNT, 0)
    val triggerCount = prefs.getInt(AutoReconnectService.PREF_TRIGGER_RECOVERY_COUNT, 0)
    val averageRecovery = if (triggerCount == 0) 0L else prefs.getLong(AutoReconnectService.PREF_TRIGGER_RECOVERY_TOTAL_MS, 0L) / triggerCount / 1_000L
    val weeklyLte = NetworkUsageTracker.totalForLastDays(context, NetworkUsageTracker.KEY_LTE_BYTES, 7)
    val weekly5G = NetworkUsageTracker.totalForLastDays(context, NetworkUsageTracker.KEY_5G_BYTES, 7)
    var billingDay by rememberSaveable { mutableIntStateOf(prefs.getInt(AutoReconnectService.PREF_BILLING_DAY, 1)) }
    Card(Modifier.fillMaxWidth().padding(top = 14.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.62f))) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Analytics, null, tint = CurioRoseDark); Spacer(Modifier.width(8.dp)); Column { Text("Mobile data tracker"); Text("Measured while the watcher is on", fontSize = 11.sp, color = CurioInk.copy(.55f)) } }
            Spacer(Modifier.height(12.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { ActivityMetric("observed on LTE", formatBytes(lte), CurioRoseDark, Modifier.weight(1f)); ActivityMetric("observed on 5G", formatBytes(fiveG), Color(0xFF207561), Modifier.weight(1f)) }
            Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { ActivityMetric("LTE fallback minutes", fallbackMinutes.toString(), CurioRoseDark, Modifier.weight(1f)); ActivityMetric("avg. trigger → 5G", if (averageRecovery == 0L) "—" else "${averageRecovery}s", Color(0xFF207561), Modifier.weight(1f)) }
            Text("Last 7 days · LTE ${formatBytes(weeklyLte)} · 5G ${formatBytes(weekly5G)}", fontSize = 11.sp, color = CurioInk.copy(.62f), modifier = Modifier.padding(top = 9.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text("Billing cycle starts on day $billingDay", fontSize = 11.sp, modifier = Modifier.weight(1f), color = CurioInk.copy(.62f)); TextButton(onClick = { billingDay = if (billingDay == 28) 1 else billingDay + 1; prefs.edit().putInt(AutoReconnectService.PREF_BILLING_DAY, billingDay).apply() }) { Text("Change") } }
            Text("Observed mobile traffic attributed to the sampled radio state; this is not a carrier billing counter.", fontSize = 10.sp, color = CurioInk.copy(.48f), modifier = Modifier.padding(top = 10.dp))
        }
    }
}

private fun formatBytes(bytes: Long): String = when { bytes >= 1_073_741_824L -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824f); bytes >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576f); else -> "$bytes B" }

private data class HistoryEntry(val timestamp: Long, val time: String, val network: String, val detail: String, val type: String)

private fun readNetworkHistory(context: android.content.Context): List<HistoryEntry> {
    val values = context.getSharedPreferences(AutoReconnectService.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .getStringSet(AutoReconnectService.PREF_HISTORY, emptySet()).orEmpty()
    val format = SimpleDateFormat("dd MMM · HH:mm:ss", Locale.getDefault())
    return values.mapNotNull { raw ->
        runCatching {
            val json = org.json.JSONObject(raw)
            val timestamp = json.getLong("time")
            HistoryEntry(timestamp, format.format(Date(timestamp)), json.optString("network", "Unknown"), json.optString("detail", ""), json.optString("type", "system"))
        }.getOrNull()
    }.sortedByDescending { it.timestamp }
}

private fun saveNetworkHistory(context: android.content.Context, network: String, detail: String) {
    val prefs = context.getSharedPreferences(AutoReconnectService.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val entry = org.json.JSONObject().put("time", System.currentTimeMillis()).put("network", network).put("detail", detail).toString()
    val values = prefs.getStringSet(AutoReconnectService.PREF_HISTORY, emptySet()).orEmpty().toMutableSet()
    values.add(entry); prefs.edit().putStringSet(AutoReconnectService.PREF_HISTORY, values).apply()
}

private fun buildNetworkReport(context: android.content.Context, entries: List<HistoryEntry>): String {
    val prefs = context.getSharedPreferences(AutoReconnectService.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    return org.json.JSONObject().apply {
        put("generatedAt", System.currentTimeMillis())
        put("lteFallbackBytes", prefs.getLong(NetworkUsageTracker.KEY_LTE_BYTES, 0L))
        put("fiveGBytes", prefs.getLong(NetworkUsageTracker.KEY_5G_BYTES, 0L))
        put("fallbackMinutes", prefs.getLong(AutoReconnectService.PREF_LTE_FALLBACK_MS, 0L) / 60_000L)
        put("averageTriggerTo5GSeconds", run {
            val count = prefs.getInt(AutoReconnectService.PREF_TRIGGER_RECOVERY_COUNT, 0)
            if (count == 0) 0L else prefs.getLong(AutoReconnectService.PREF_TRIGGER_RECOVERY_TOTAL_MS, 0L) / count / 1_000L
        })
        put("events", org.json.JSONArray().apply { entries.forEach { put(org.json.JSONObject().put("time", it.timestamp).put("network", it.network).put("type", it.type).put("detail", it.detail)) } })
    }.toString(2)
}
