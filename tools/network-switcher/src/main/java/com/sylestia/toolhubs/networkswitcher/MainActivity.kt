package com.sylestia.toolhubs.networkswitcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.core.content.ContextCompat
import com.sylestia.toolhubs.designsystem.CurioCream
import com.sylestia.toolhubs.designsystem.CurioInk
import com.sylestia.toolhubs.designsystem.CurioRoseDark
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 100 && grantResult == PackageManager.PERMISSION_GRANTED) {
            runOnUiThread { shizukuGranted = true }
        }
    }
    private var shizukuGranted by mutableStateOf(false)
    private val telephony by lazy { getSystemService(TelephonyManager::class.java) }

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
        setContent {
            com.sylestia.toolhubs.designsystem.ToolHubsTheme { NetworkScreen() }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun currentNetwork(): String = when (telephony.dataNetworkType) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G / LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
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
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0 && seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }

    @Composable
    private fun NetworkScreen() {
        val context = this
        var auto by rememberSaveable { mutableStateOf(false) }
        var network by remember { mutableStateOf(currentNetwork()) }
        var status by remember { mutableStateOf("Ready") }
        var running by remember { mutableStateOf(false) }
        var pressed by remember { mutableStateOf(false) }
        var intervalSeconds by rememberSaveable {
            mutableIntStateOf((getSharedPreferences(AutoReconnectService.PREFS_NAME, MODE_PRIVATE)
                .getLong(AutoReconnectService.PREF_INTERVAL_MS, AutoReconnectService.DEFAULT_INTERVAL_MS) / 1000L).toInt())
        }

        LaunchedEffect(Unit) {
            while (true) {
                network = currentNetwork()
                delay(2_000)
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

            Spacer(Modifier.height(20.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic 5G reconnect")
                    Text(
                        "Background monitoring · max 2 automatic attempts per minute.",
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
                                "Wait this long before another automatic trigger.",
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
                                .putLong(AutoReconnectService.PREF_INTERVAL_MS, intervalSeconds * 1000L)
                                .apply()
                        },
                        valueRange = 30f..600f,
                        steps = 18
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("30 sec", fontSize = 10.sp, color = CurioInk.copy(alpha = .45f))
                        Text("10 min", fontSize = 10.sp, color = CurioInk.copy(alpha = .45f))
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
                targetValue = if (pressed) CurioRoseDark.copy(alpha = .88f) else MaterialTheme.colorScheme.primary,
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
                            runOnUiThread {
                                status = result
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
                Text(if (running) "Re-triggering…" else "Re-trigger 5G now")
            }

            Spacer(Modifier.height(14.dp))

            Text(
                if (Shizuku.pingBinder()) "Shizuku: connected" else "Shizuku: not running",
                fontSize = 12.sp,
                color = CurioInk.copy(alpha = .55f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Automatic mode allows at most 2 triggers in any rolling 60-second window. The interval above controls the normal wait between triggers.",
                fontSize = 11.sp,
                color = CurioInk.copy(alpha = .45f)
            )
        }
    }
}
