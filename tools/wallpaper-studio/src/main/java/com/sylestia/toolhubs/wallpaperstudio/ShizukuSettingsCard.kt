package com.sylestia.toolhubs.wallpaperstudio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.ui.HubGlassCard
import com.sylestia.toolhubs.ui.HubQuietButton
import rikka.shizuku.Shizuku

@Composable
fun ShizukuSettingsCard() {
    val context = LocalContext.current
    var state by remember {
        mutableStateOf(
            if (WallpaperShizuku.isInstalled(context)) WallpaperShizuku.state()
            else ShizukuState.NOT_INSTALLED,
        )
    }

    DisposableEffect(context) {
        val refresh = { state = WallpaperShizuku.state() }
        val received = Shizuku.OnBinderReceivedListener { refresh() }
        val dead = Shizuku.OnBinderDeadListener { state = ShizukuState.NOT_RUNNING }
        val permission = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == WallpaperShizuku.REQUEST_CODE) refresh()
        }
        runCatching {
            Shizuku.addBinderReceivedListener(received)
            Shizuku.addBinderDeadListener(dead)
            Shizuku.addRequestPermissionResultListener(permission)
        }
        onDispose {
            runCatching {
                Shizuku.removeBinderReceivedListener(received)
                Shizuku.removeBinderDeadListener(dead)
                Shizuku.removeRequestPermissionResultListener(permission)
            }
        }
    }

    val title = when (state) {
        ShizukuState.NOT_INSTALLED -> "Shizuku not installed"
        ShizukuState.NOT_RUNNING -> "Shizuku service is offline"
        ShizukuState.UNSUPPORTED -> "Update Shizuku to v11 or newer"
        ShizukuState.PERMISSION_REQUIRED -> "Permission needed"
        ShizukuState.CONNECTED -> "System bridge connected"
    }
    val detail = when (state) {
        ShizukuState.NOT_INSTALLED -> "Optional advanced integration. Install Shizuku to expose a controlled ADB or root bridge."
        ShizukuState.NOT_RUNNING -> "Start Shizuku from its app. On non-rooted phones, wireless debugging can start it without a PC on Android 11+."
        ShizukuState.UNSUPPORTED -> "This Shizuku version is too old for the supported permission flow. Update it and reconnect."
        ShizukuState.PERMISSION_REQUIRED -> "Wallpaper Studio can request its own Shizuku permission. Nothing is executed until you approve it."
        ShizukuState.CONNECTED -> "Connected through \${WallpaperShizuku.identity()}. Standard WallpaperManager remains the safe, fast path for applying images."
    }

    HubGlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("SHIZUKU SYSTEM BRIDGE", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text(
                    title,
                    color = if (state == ShizukuState.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(10.dp))
        HubQuietButton(
            text = when (state) {
                ShizukuState.NOT_INSTALLED, ShizukuState.NOT_RUNNING -> "Open Shizuku"
                ShizukuState.PERMISSION_REQUIRED -> "Grant access"
                ShizukuState.UNSUPPORTED -> "Update Shizuku"
                ShizukuState.CONNECTED -> "Refresh status"
            },
            icon = Icons.Rounded.Tune,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                when (state) {
                    ShizukuState.NOT_INSTALLED, ShizukuState.NOT_RUNNING, ShizukuState.UNSUPPORTED ->
                        WallpaperShizuku.openManager(context)
                    ShizukuState.PERMISSION_REQUIRED -> WallpaperShizuku.requestPermission()
                    ShizukuState.CONNECTED -> state = WallpaperShizuku.state()
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Shizuku is optional. It cannot identify whether Face Unlock or a fingerprint dismissed the lock screen, and it does not bypass Android's biometric privacy boundaries.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
    }
}
