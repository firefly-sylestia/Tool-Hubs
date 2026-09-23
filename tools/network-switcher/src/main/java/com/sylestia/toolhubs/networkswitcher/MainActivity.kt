package com.sylestia.toolhubs.networkswitcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.PhoneStateListener
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import androidx.core.content.ContextCompat
import com.sylestia.toolhubs.designsystem.CurioCream
import com.sylestia.toolhubs.designsystem.CurioInk
import com.sylestia.toolhubs.designsystem.CurioRoseDark
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
            Modifier.fillMaxSize().background(CurioCream).statusBarsPadding()
                .navigationBarsPadding().padding(20.dp)
        ) {
            Text("5G RECONNECT", color = CurioRoseDark, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Text("Re-trigger 5G after an LTE fallback.", color = CurioInk, fontSize = 27.sp)
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
                                    "5G switch confirmed."
                                } else if (result.startsWith("5G re-selection triggered")) {
                                    "Trigger sent, but 5G was not detected."
                                } else {
                                    result
                                }
                                running = false
                                pressed = false
                            }
                        }.start()
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().scale(buttonScale),
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
                if (Shizuku.pingBinder()) "Shizuku: connected" else "Shizuku: not running",
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
@Composable
private fun HistoryView(context: android.content.Context) {
    var entries by remember { mutableStateOf(readNetworkHistory(context)) }
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
                    Text("Network history")
                    Text("Recent 5G, LTE and reconnect events", fontSize = 11.sp, color = CurioInk.copy(alpha = .55f))
                }
                TextButton(onClick = {
                    context.getSharedPreferences(AutoReconnectService.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                        .edit().remove(AutoReconnectService.PREF_HISTORY).apply()
                    entries = emptyList()
                }) { Text("Clear") }
            }
            Spacer(Modifier.height(10.dp))
            if (entries.isEmpty()) {
                Text("No network changes recorded yet.", fontSize = 12.sp, color = CurioInk.copy(alpha = .5f))
            } else {
                entries.take(12).forEachIndexed { index, entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.network, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = CurioInk)
                            if (entry.detail.isNotBlank()) Text(entry.detail, fontSize = 10.sp, color = CurioInk.copy(alpha = .52f))
                        }
                        Text(entry.time, fontSize = 10.sp, color = CurioRoseDark)
                    }
                    if (index < entries.take(12).lastIndex) HorizontalDivider(color = CurioInk.copy(alpha = .08f))
                }
            }
        }
    }
}

private data class HistoryEntry(val time: String, val network: String, val detail: String)

private fun readNetworkHistory(context: android.content.Context): List<HistoryEntry> {
    val values = context.getSharedPreferences(AutoReconnectService.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .getStringSet(AutoReconnectService.PREF_HISTORY, emptySet()).orEmpty()
    val format = SimpleDateFormat("dd MMM · HH:mm:ss", Locale.getDefault())
    return values.mapNotNull { raw ->
        runCatching {
            val json = org.json.JSONObject(raw)
            HistoryEntry(format.format(Date(json.getLong("time"))), json.optString("network", "Unknown"), json.optString("detail", ""))
        }.getOrNull()
    }.sortedByDescending { entry ->
        runCatching { SimpleDateFormat("dd MMM · HH:mm:ss", Locale.getDefault()).parse(entry.time)?.time ?: 0L }.getOrDefault(0L)
    }
}


