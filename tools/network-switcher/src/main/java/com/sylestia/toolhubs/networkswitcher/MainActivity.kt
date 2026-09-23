package com.sylestia.toolhubs.networkswitcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sylestia.toolhubs.designsystem.CurioCream
import com.sylestia.toolhubs.designsystem.CurioInk
import com.sylestia.toolhubs.designsystem.CurioRoseDark
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
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

        setContent {
            com.sylestia.toolhubs.designsystem.ToolHubsTheme {
                NetworkScreen()
            }
        }
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
        ContextCompat.startForegroundService(
            this,
            Intent(this, AutoReconnectService::class.java).apply {
                action = AutoReconnectService.ACTION_START
            }
        )
    }

    private fun stopBackgroundWatcher() {
        stopService(Intent(this, AutoReconnectService::class.java).apply {
            action = AutoReconnectService.ACTION_STOP
        })
    }

    @Composable
    private fun NetworkScreen() {
        val context = this
        var auto by rememberSaveable { mutableStateOf(false) }
        var network by remember { mutableStateOf(currentNetwork()) }
        var status by remember { mutableStateOf("Ready") }
        var running by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            while (true) {
                network = currentNetwork()
                delay(2_000)
            }
        }

        Column(
            Modifier.fillMaxSize()
                .background(CurioCream)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            Text("5G RECONNECT", color = CurioRoseDark, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Re-trigger 5G after an LTE fallback.",
                color = CurioInk,
                fontSize = 27.sp
            )

            Spacer(Modifier.height(24.dp))

            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = .72f)
                )
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text("CURRENT NETWORK", color = CurioRoseDark, fontSize = 10.sp)
                    Text(network, color = CurioInk, fontSize = 34.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = CurioInk.copy(alpha = .62f))
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic 5G reconnect")
                    Text(
                        "Runs in the background · max " + AutoReconnectService.LIMIT + " automatic attempts/day.",
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
                        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
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

            Button(
                onClick = {
                    if (!Shizuku.pingBinder()) {
                        status = "Start Shizuku first."
                    } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                        status = "Requesting Shizuku permission…"
                        Shizuku.requestPermission(100)
                    } else {
                        running = true
                        status = "Re-triggering 5G…"
                        Thread {
                            val result = NetworkSwitcherBridge(context).reconnect5G()
                            runOnUiThread {
                                status = result
                                running = false
                            }
                        }.start()
                    }
                },
                enabled = !running,
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("Re-trigger 5G now")
            }

            Spacer(Modifier.height(12.dp))

            Text(
                if (Shizuku.pingBinder()) "Shizuku: connected" else "Shizuku: not running",
                fontSize = 12.sp,
                color = CurioInk.copy(alpha = .55f)
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Automatic limit resets at midnight. Manual reconnect remains available.",
                fontSize = 11.sp,
                color = CurioInk.copy(alpha = .45f)
            )
        }
    }
}
