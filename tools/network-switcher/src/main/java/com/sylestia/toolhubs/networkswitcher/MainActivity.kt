@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sylestia.toolhubs.networkswitcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.telephony.SubscriptionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Analytics
import androidx.core.content.ContextCompat
import com.sylestia.toolhubs.designsystem.CurioCream
import com.sylestia.toolhubs.designsystem.CurioInk
import com.sylestia.toolhubs.designsystem.CurioRoseDark
import com.sylestia.toolhubs.ui.HubScreenHeader
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
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
                display5GSignal =
                    info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                    info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
            }
        }
    } else {
        null
    }

    private val telephonyCallback by lazy { createTelephonyCallback() }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createTelephonyCallback(): android.telephony.TelephonyCallback = object : android.telephony.TelephonyCallback(),
        android.telephony.TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
            display5GSignal =
                info.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
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
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
        }
        shizukuGranted = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        ShizukuHealthMonitor.install()
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        registerNetworkObserver()
        setContent {
            com.sylestia.toolhubs.designsystem.ToolHubsTheme { NetworkScreen() }
        }
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
                telephony.listen(
                    phoneStateListener,
                    PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED
                )
            }
        }
    }

    private fun unregisterNetworkObserver() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephony.unregisterTelephonyCallback(telephonyCallback)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                telephony.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    private fun isActually5G(): Boolean {
        val dataType = runCatching { telephony.dataNetworkType }
            .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
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
            when { telephony.dataNetworkType == TelephonyManager.NETWORK_TYPE_NR -> "NR / SA detected"; display5GSignal -> "NSA advertised"; else -> "Not currently advertised" }
        }.getOrDefault("Unavailable")
        return listOf(
            "Carrier 5G" to carrier5g,
            "Preferred radio" to preferred,
            "Mobile data" to data,
            "Active SIMs" to activeSims().size.toString()
        )
    }

    private fun startBackgroundWatcher() {
        ContextCompat.startForegroundService(this, Intent(this, AutoReconnectService::class.java).apply {
            action = AutoReconnectService.ACTION_START
        })
    }

    private fun stopBackgroundWatcher() {
        stopService(Intent(this, AutoReconnectService::class.java).apply {
            action = AutoReconnectService.ACTION_STOP
        })
    }

    private fun formatInterval(seconds: Int): String = when {
        seconds < 60 -> seconds.toString() + "s"
        seconds % 60 == 0 && seconds < 3600 -> (seconds / 60).toString() + " min"
        else -> (seconds / 60).toString() + "m " + (seconds % 60) + "s"
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
    private fun NetworkScreen() {
        val context = this
        var auto by rememberSaveable { mutableStateOf(getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE).getBoolean(AutoReconnectService.PREF_AUTO_ENABLED, false)) }
        var startAfterRestart by rememberSaveable { mutableStateOf(getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE).getBoolean(AutoReconnectService.PREF_START_AFTER_REBOOT, false)) }
        val sims = remember { activeSims() }
        var selectedSim by rememberSaveable { mutableIntStateOf(getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE).getInt(AutoReconnectService.PREF_SELECTED_SUBSCRIPTION, SubscriptionManager.INVALID_SUBSCRIPTION_ID)) }
        var backupSim by rememberSaveable { mutableIntStateOf(getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE).getInt(AutoReconnectService.PREF_FAILOVER_TARGET, SubscriptionManager.INVALID_SUBSCRIPTION_ID)) }
        var simFailover by rememberSaveable { mutableStateOf(getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE).getBoolean(AutoReconnectService.PREF_FAILOVER_ENABLED, false)) }
        var network by remember { mutableStateOf(currentNetwork()) }
        var status by remember { mutableStateOf("Ready") }
        var running by remember { mutableStateOf(false) }
        var pressed by remember { mutableStateOf(false) }
        var intervalSeconds by rememberSaveable {
            mutableIntStateOf(
                (getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE)
                    .getLong(
                        AutoReconnectService.PREF_INTERVAL_MS,
                        AutoReconnectService.DEFAULT_INTERVAL_MS
                    ) / 1000L).toInt()
            )
        }
        var maxAttempts by rememberSaveable {
            mutableIntStateOf(
                getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE)
                    .getInt(
                        AutoReconnectService.PREF_MAX_ATTEMPTS,
                        AutoReconnectService.DEFAULT_MAX_ATTEMPTS
                    )
                    .coerceIn(
                        AutoReconnectService.MIN_MAX_ATTEMPTS,
                        AutoReconnectService.MAX_MAX_ATTEMPTS
                    )
            )
        }

        LaunchedEffect(Unit) {
            while (true) {
                network = currentNetwork()
                delay(1_000)
            }
        }

        Column(
            Modifier.fillMaxSize()
                .background(CurioCream)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            HubScreenHeader("5G Reconnect", "Re-trigger 5G after an LTE fallback.")
            Spacer(Modifier.height(24.dp))

            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .72f))
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text("CURRENT NETWORK", color = CurioRoseDark, fontSize = 10.sp)
                    Text(network, color = CurioInk, fontSize = 34.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = CurioInk.copy(alpha = .62f))
                }
            }

            Spacer(Modifier.height(18.dp))

            HistoryView(context)

            Spacer(Modifier.height(20.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic 5G reconnect")
                    Text(
                        "Background monitoring · configurable attempts per minute.",
                        fontSize = 12.sp,
                        color = CurioInk.copy(alpha = .55f)
                    )
                }
                Switch(
                    checked = auto,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            auto = false
                            stopBackgroundWatcher()
                            status = "Background watcher stopped."
                        } else if (!Shizuku.pingBinder()) {
                            status = "Start Shizuku first."
                        } else if (!shizukuGranted) {
                            status = "Requesting Shizuku permission…"
                            Shizuku.requestPermission(100)
                        } else {
                            auto = true
                            startBackgroundWatcher()
                            status = "Watching in background…"
                        }
                    }
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Start after restart")
                    Text("Restarts the enabled watcher about 30 seconds after your phone boots.", fontSize = 12.sp, color = CurioInk.copy(alpha = .55f))
                }
                Switch(checked = startAfterRestart, onCheckedChange = { enabled ->
                    startAfterRestart = enabled
                    getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE).edit().putBoolean(AutoReconnectService.PREF_START_AFTER_REBOOT, enabled).apply()
                })
            }

            if (sims.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SimAndDiagnosticsCard(sims, selectedSim, backupSim, simFailover, diagnostics(),
                    onSelected = { id -> selectedSim = id; getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE).edit().putInt(AutoReconnectService.PREF_SELECTED_SUBSCRIPTION, id).apply(); if (auto) startBackgroundWatcher() },
                    onBackup = { id -> backupSim = id; getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE).edit().putInt(AutoReconnectService.PREF_FAILOVER_TARGET, id).apply() },
                    onFailover = { enabled -> simFailover = enabled; getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE).edit().putBoolean(AutoReconnectService.PREF_FAILOVER_ENABLED, enabled).apply() }
                )
            }

            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.55f))) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Keep background monitoring reliable"); Text("Some phone makers stop background work aggressively. You can exclude 5G Reconnect from battery optimisation in Android settings.", fontSize = 11.sp, color = CurioInk.copy(.58f)) }
                    TextButton(onClick = { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) { Text("Settings") }
                }
            }

            Spacer(Modifier.height(18.dp))

            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .62f))
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Settings, null, tint = CurioRoseDark)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Reconnect interval")
                            Text(
                                "Wait this long after a failed attempt before retrying.",
                                fontSize = 11.sp,
                                color = CurioInk.copy(alpha = .55f)
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            formatInterval(intervalSeconds),
                            color = CurioRoseDark,
                            fontSize = 13.sp
                        )
                    }
                    Slider(
                        value = intervalSeconds.toFloat(),
                        onValueChange = { intervalSeconds = it.toInt() },
                        onValueChangeFinished = {
                            getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE)
                                .edit()
                                .putLong(
                                    AutoReconnectService.PREF_INTERVAL_MS,
                                    intervalSeconds * 1000L
                                )
                                .apply()
                            if (auto) startBackgroundWatcher()
                        },
                        valueRange = 30f..600f,
                        steps = 18
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("30 sec", fontSize = 10.sp, color = CurioInk.copy(alpha = .45f))
                        Text("10 min", fontSize = 10.sp, color = CurioInk.copy(alpha = .45f))
                    }

                    Spacer(Modifier.height(18.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Maximum attempts")
                            Text(
                                "Rolling 60-second limit for automatic triggers.",
                                fontSize = 11.sp,
                                color = CurioInk.copy(alpha = .55f)
                            )
                        }
                        Text(
                            maxAttempts.toString(),
                            color = CurioRoseDark,
                            fontSize = 13.sp
                        )
                    }
                    Slider(
                        value = maxAttempts.toFloat(),
                        onValueChange = {
                            maxAttempts = it.toInt().coerceIn(
                                AutoReconnectService.MIN_MAX_ATTEMPTS,
                                AutoReconnectService.MAX_MAX_ATTEMPTS
                            )
                        },
                        onValueChangeFinished = {
                            getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE)
                                .edit()
                                .putInt(AutoReconnectService.PREF_MAX_ATTEMPTS, maxAttempts)
                                .apply()
                            if (auto) startBackgroundWatcher()
                        },
                        valueRange = AutoReconnectService.MIN_MAX_ATTEMPTS.toFloat()..
                            AutoReconnectService.MAX_MAX_ATTEMPTS.toFloat(),
                        steps = AutoReconnectService.MAX_MAX_ATTEMPTS -
                            AutoReconnectService.MIN_MAX_ATTEMPTS - 1
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            AutoReconnectService.MIN_MAX_ATTEMPTS.toString(),
                            fontSize = 10.sp,
                            color = CurioInk.copy(alpha = .45f)
                        )
                        Text(
                            AutoReconnectService.MAX_MAX_ATTEMPTS.toString(),
                            fontSize = 10.sp,
                            color = CurioInk.copy(alpha = .45f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            val buttonScale by animateFloatAsState(
                targetValue = if (pressed) .96f else 1f,
                animationSpec = spring(dampingRatio = .55f),
                label = "buttonScale"
            )
            val buttonColor by animateColorAsState(
                targetValue = if (pressed) CurioRoseDark.copy(alpha = .88f)
                else MaterialTheme.colorScheme.primary,
                label = "buttonColor"
            )

            Button(
                onClick = {
                    pressed = true
                    if (!Shizuku.pingBinder()) {
                        status = "Start Shizuku first."
                        pressed = false
                    } else if (!shizukuGranted) {
                        status = "Requesting Shizuku permission…"
                        Shizuku.requestPermission(100)
                        pressed = false
                    } else {
                        running = true
                        status = "Re-triggering 5G…"
                        Thread {
                            val result = NetworkSwitcherBridge(context).reconnect5G()
                            val success = waitFor5GBlocking(12_000L)
                            runOnUiThread {
                                status = if (success) {
                                    saveNetworkHistory(this@MainActivity, "5G", "Manual reconnect confirmed")
                                    "5G switch confirmed."
                                } else if (result.startsWith("5G re-selection triggered")) {
                                    saveNetworkHistory(this@MainActivity, "4G / LTE", "Manual reconnect attempt failed")
                                    "Trigger sent, but 5G was not detected."
                                } else {
                                    saveNetworkHistory(this@MainActivity, "4G / LTE", "Manual reconnect error: $result")
                                    result
                                }
                                running = false
                                pressed = false
                            }
                        }.start()
                    }
                },
                enabled = !running,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .scale(buttonScale),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(vertical = 17.dp)
            ) {
                Icon(Icons.Rounded.Bolt, null)
                Spacer(Modifier.width(8.dp))
                Text(if (running) "Checking 5G…" else "Re-trigger 5G now")
            }

            Spacer(Modifier.height(14.dp))

            Text(
                "Shizuku: ${shizukuHealthLabel(ShizukuHealthMonitor.refresh())}",
                fontSize = 12.sp,
                color = CurioInk.copy(alpha = .55f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "5G is confirmed from Android's network state, including 5G NSA display information. A successful Shizuku command alone is not reported as success.",
                fontSize = 11.sp,
                color = CurioInk.copy(alpha = .45f)
            )
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { sims.forEach { sim -> FilterChip(selected = selected == sim.id, onClick = { onSelected(sim.id) }, label = { Text(sim.label) }) } }
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
    DataTrackerView(context)
    if (showAll) ModalBottomSheet(onDismissRequest = { showAll = false }, containerColor = CurioCream) {
        var filter by rememberSaveable { mutableStateOf("All") }
        var expandedTime by rememberSaveable { mutableLongStateOf(-1L) }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
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
    val fallbackMinutes = prefs.getLong(AutoReconnectService.PREF_LTE_FALLBACK_MS, 0L) / 60_000L
    val recoveries = prefs.getInt(AutoReconnectService.PREF_RECOVERY_COUNT, 0)
    val averageRecovery = if (recoveries == 0) 0L else prefs.getLong(AutoReconnectService.PREF_RECOVERY_TOTAL_MS, 0L) / recoveries / 1_000L
    val weeklyLte = NetworkUsageTracker.totalForLastDays(context, NetworkUsageTracker.KEY_LTE_BYTES, 7)
    val weekly5G = NetworkUsageTracker.totalForLastDays(context, NetworkUsageTracker.KEY_5G_BYTES, 7)
    var billingDay by rememberSaveable { mutableIntStateOf(prefs.getInt(AutoReconnectService.PREF_BILLING_DAY, 1)) }
    Card(Modifier.fillMaxWidth().padding(top = 14.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.62f))) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Analytics, null, tint = CurioRoseDark); Spacer(Modifier.width(8.dp)); Column { Text("Mobile data tracker"); Text("Measured while the watcher is on", fontSize = 11.sp, color = CurioInk.copy(.55f)) } }
            Spacer(Modifier.height(12.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { ActivityMetric("during LTE fallback", formatBytes(lte), CurioRoseDark, Modifier.weight(1f)); ActivityMetric("while on 5G", formatBytes(fiveG), Color(0xFF207561), Modifier.weight(1f)) }
            Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { ActivityMetric("LTE fallback minutes", fallbackMinutes.toString(), CurioRoseDark, Modifier.weight(1f)); ActivityMetric("avg. 5G recovery", if (averageRecovery == 0L) "—" else "${averageRecovery}s", Color(0xFF207561), Modifier.weight(1f)) }
            Text("Last 7 days · LTE fallback ${formatBytes(weeklyLte)} · 5G ${formatBytes(weekly5G)}", fontSize = 11.sp, color = CurioInk.copy(.62f), modifier = Modifier.padding(top = 9.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text("Billing cycle starts on day $billingDay", fontSize = 11.sp, modifier = Modifier.weight(1f), color = CurioInk.copy(.62f)); TextButton(onClick = { billingDay = if (billingDay == 28) 1 else billingDay + 1; prefs.edit().putInt(AutoReconnectService.PREF_BILLING_DAY, billingDay).apply() }) { Text("Change") } }
            Text("Observed mobile traffic by radio state; this is not a carrier billing or counterfactual savings estimate.", fontSize = 10.sp, color = CurioInk.copy(.48f), modifier = Modifier.padding(top = 10.dp))
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
        put("events", org.json.JSONArray().apply { entries.forEach { put(org.json.JSONObject().put("time", it.timestamp).put("network", it.network).put("type", it.type).put("detail", it.detail)) } })
    }.toString(2)
}
