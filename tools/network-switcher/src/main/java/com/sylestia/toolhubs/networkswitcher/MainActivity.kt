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
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sylestia.toolhubs.designsystem.CurioCream
import com.sylestia.toolhubs.designsystem.CurioInk
import com.sylestia.toolhubs.designsystem.MaterialTheme.colorScheme.primary
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
        val haptic = LocalHapticFeedback.current
        val view = LocalView.current

        LaunchedEffect(Unit) {
            while (true) {
                shizukuGranted = Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                delay(1000)
            }
        }

        Box(Modifier.fillMaxSize()) {
            AnimatedBackdrop()

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0f),
                bottomBar = {
                    FloatingNavigation(
                        page = page,
                        onPageSelected = {
                            if (page != it) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                page = it
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    AnimatedContent(
                        targetState = page,
                        modifier = Modifier.fillMaxSize(),
                        label = "page_transition",
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInHorizontally(
                                    animationSpec = tween(360),
                                    initialOffsetX = { it / 12 }
                                ) + fadeIn(tween(260))) togetherWith
                                    (slideOutHorizontally(
                                        animationSpec = tween(260),
                                        targetOffsetX = { -it / 16 }
                                    ) + fadeOut(tween(180)))
                            } else {
                                (slideInHorizontally(
                                    animationSpec = tween(360),
                                    initialOffsetX = { -it / 12 }
                                ) + fadeIn(tween(260))) togetherWith
                                    (slideOutHorizontally(
                                        animationSpec = tween(260),
                                        targetOffsetX = { it / 16 }
                                    ) + fadeOut(tween(180)))
                            }
                        }
                    ) { currentPage ->
                        when (currentPage) {
                            0 -> ControlPage()
                            1 -> HistoryPage()
                            2 -> UsagePage()
                            else -> SettingsPage()
                        }
                    }
                }
            }
        }
    }

    private val MetalBase = Color(0xFFB9BDC1)
    private val MetalDark = Color(0xFF565B60)
    private val MetalDeep = Color(0xFF2D3135)
    private val MetalLight = Color(0xFFE8EAEB)
    private val MetalAccent = Color(0xFFB76E4A)

    @Composable
    private fun AnimatedBackdrop() {
        val infinite = rememberInfiniteTransition(label = "metal_ambient")
        val drift by infinite.animateFloat(
            -0.04f, 0.04f,
            infiniteRepeatable(tween(9000), RepeatMode.Reverse),
            label = "metal_drift"
        )
        val sheen by infinite.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(7000), RepeatMode.Restart),
            label = "metal_sheen"
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .background(MetalBase)
        ) {
            val w = size.width
            val h = size.height

            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val base = MaterialTheme.colorScheme.background
            val surface = MaterialTheme.colorScheme.surface
            val edge = MaterialTheme.colorScheme.outline
            drawRect(
                brush = Brush.linearGradient(
                    colors = if (dark) {
                        listOf(
                            Color(0xFF252A2F),
                            Color(0xFF171B1F),
                            Color(0xFF30363B),
                            Color(0xFF121519)
                        )
                    } else {
                        listOf(
                            Color(0xFFE9EBEC),
                            Color(0xFFB9BDC1),
                            Color(0xFF9FA4A8),
                            Color(0xFFE1E3E4)
                        )
                    },
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(w, h)
                )
            )

            repeat(32) { i ->
                val y = h * i / 32f
                drawLine(
                    color = Color.White.copy(alpha = if (i % 3 == 0) .055f else .028f),
                    start = androidx.compose.ui.geometry.Offset(0f, y),
                    end = androidx.compose.ui.geometry.Offset(w, y + w * .012f),
                    strokeWidth = if (i % 4 == 0) 1.5f else 0.7f
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        Color.White.copy(alpha = .24f),
                        Color.Transparent
                    )
                ),
                radius = w * .52f,
                center = androidx.compose.ui.geometry.Offset(
                    w * (.25f + drift),
                    h * .14f
                )
            )

            val sheenX = w * (sheen * 1.45f - .22f)
            drawRect(
                brush = Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = .09f), Color.Transparent),
                    start = androidx.compose.ui.geometry.Offset(sheenX - w * .22f, 0f),
                    end = androidx.compose.ui.geometry.Offset(sheenX + w * .22f, h)
                )
            )
        }
    }

    @Composable
    private fun FloatingNavigation(page: Int, onPageSelected: (Int) -> Unit) {
        val items = listOf(
            Triple(Icons.Rounded.Bolt, "WATCH", 0),
            Triple(Icons.Rounded.History, "EVENTS", 1),
            Triple(Icons.Rounded.Analytics, "DATA", 2),
            Triple(Icons.Rounded.Settings, "TUNE", 3)
        )
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(.45f)),
                        RoundedCornerShape(22.dp)
                    ),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                shadowElevation = 20.dp,
                tonalElevation = 0.dp
            ) {
                Row(
                    Modifier.fillMaxSize().padding(7.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items.forEach { (icon, label, index) ->
                        MetalNavButton(
                            icon = icon,
                            label = label,
                            selected = page == index,
                            onClick = { onPageSelected(index) },
                            modifier = Modifier.weight(if (page == index) 1.24f else .90f)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MetalNavButton(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier
    ) {
        val scale by animateFloatAsState(
            if (selected) 1f else .94f,
            spring(dampingRatio = .62f, stiffness = 600f),
            label = "metal_nav_scale"
        )
        val lift by animateDpAsState(
            if (selected) 0.dp else 2.dp,
            spring(dampingRatio = .65f, stiffness = 650f),
            label = "metal_nav_lift"
        )
        Box(
            modifier
                .fillMaxHeight()
                .padding(top = lift)
                .scale(scale)
                .clip(RoundedCornerShape(17.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(MetalLight, MetalBase, Color(0xFF9A9FA3))
                            ),
                            RoundedCornerShape(17.dp)
                        )
                        .border(
                            BorderStroke(1.dp, Color.White.copy(.76f)),
                            RoundedCornerShape(17.dp)
                        )
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(top = 2.dp)
                        .border(
                            BorderStroke(2.dp, Color.Black.copy(.15f)),
                            RoundedCornerShape(15.dp)
                        )
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    icon,
                    null,
                    tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(.55f),
                    modifier = Modifier.size(if (selected) 21.dp else 20.dp)
                )
                AnimatedVisibility(
                    visible = selected,
                    enter = fadeIn(tween(120)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(90)) + shrinkVertically(tween(130))
                ) {
                    Text(
                        label,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun MetalPanel(
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Surface(
            modifier = modifier
                .border(
                    BorderStroke(1.dp, Color.White.copy(.66f)),
                    RoundedCornerShape(24.dp)
                ),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(.94f),
            shadowElevation = 12.dp,
            tonalElevation = 0.dp
        ) {
            Column(
                Modifier
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.onSurface.copy(.07f),
                                Color.Transparent,
                                MaterialTheme.colorScheme.onSurface.copy(.06f)
                            )
                        )
                    )
                    .padding(18.dp),
                content = content
            )
        }
    }

    @Composable
    private fun TactileButton(
        text: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        var pressed by remember { mutableStateOf(false) }
        val haptic = LocalHapticFeedback.current
        val view = LocalView.current
        val scale by animateFloatAsState(
            if (pressed) .965f else 1f,
            spring(dampingRatio = .48f, stiffness = 750f),
            label = "tactile_scale"
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .scale(scale)
                .clip(RoundedCornerShape(18.dp))
                .clickable(enabled = enabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    pressed = true
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(MetalLight, Color(0xFFD0D3D5), Color(0xFF989DA1))
                        ),
                        RoundedCornerShape(18.dp)
                    )
                    .border(
                        BorderStroke(1.dp, Color.White.copy(.72f)),
                        RoundedCornerShape(18.dp)
                    )
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp)
                    .background(
                        MetalDark.copy(.34f),
                        RoundedCornerShape(50)
                    )
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MetalDeep)
                Spacer(Modifier.width(9.dp))
                Text(text, color = MetalDeep, fontWeight = FontWeight.Bold)
            }
        }
        LaunchedEffect(pressed) {
            if (pressed) {
                delay(90)
                pressed = false
            }
        }
    }

    @Composable
    private fun NetworkPulseOrb(network: String) {
        val infinite = rememberInfiniteTransition(label = "metal_orb")
        val pulse by infinite.animateFloat(
            .96f, 1.04f,
            infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "orb_pulse"
        )
        val tint = if (network == "5G") Color(0xFF5D806D)
        else if (network == "4G / LTE") MetalAccent
        else MetalDeep

        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(92.dp)
                    .scale(pulse)
                    .background(
                        Brush.radialGradient(
                            listOf(Color.White.copy(.52f), Color.Transparent)
                        ),
                        CircleShape
                    )
                    .border(
                        BorderStroke(1.dp, Color.White.copy(.66f)),
                        CircleShape
                    )
            )
            Box(
                Modifier
                    .size(72.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(MetalLight, Color(0xFF9B9FA3))
                        ),
                        CircleShape
                    )
                    .border(
                        BorderStroke(2.dp, tint.copy(.45f)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (network == "5G") "5G" else if (network == "4G / LTE") "LTE" else "—",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        color = tint
                    )
                    Text(
                        "RADIO",
                        fontSize = 7.sp,
                        letterSpacing = 1.1.sp,
                        color = MetalDeep.copy(.52f)
                    )
                }
            }
        }
    }

    @Composable
    private fun StatePill(label: String, active: Boolean) {
        Row(
            Modifier
                .background(
                    if (active) MetalDark else Color.Black.copy(.10f),
                    RoundedCornerShape(8.dp)
                )
                .border(
                    BorderStroke(1.dp, Color.White.copy(.28f)),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        if (active) Color(0xFF9BE7C0) else Color.White.copy(.34f),
                        CircleShape
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .9.sp,
                color = Color.White.copy(if (active) .86f else .48f)
            )
        }
    }

    @Composable
    private fun PermissionChecker() {
        val phone = hasPhonePermission()
        val notifications = hasNotificationPermission()
        val shizuku = ShizukuHealthMonitor.refresh() == ShizukuHealth.READY
        if (phone && notifications && shizuku) return

        MetalPanel(Modifier.fillMaxWidth()) {
                Text("Required access", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("The checker disappears automatically once everything is ready.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
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
                Text(if (allowed) "Allowed" else "Required", fontSize = 11.sp, color = if (allowed) Color(0xFF207561) else MaterialTheme.colorScheme.primary)
            }
            if (!allowed) TextButton(onClick = { requestMissingPermission(kind) }) { Text("Allow") }
        }
    }

    @Composable
    private fun ControlPage() {
        val context = this
        val haptic = LocalHapticFeedback.current
        val view = LocalView.current
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
                    network == "5G" -> if (auto) "5G active · watching for LTE fallback" else "5G active"
                    network == "4G / LTE" && auto -> "LTE detected · recovery watcher active"
                    network == "4G / LTE" -> "LTE detected"
                    else -> network
                }
                delay(1000)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            ExpressivePageHeader(
                title = "5G Reconnect",
                subtitle = "A tiny network guardian that stays awake for you.",
                icon = Icons.Rounded.Bolt
            )

            Spacer(Modifier.height(16.dp))
            PermissionChecker()
            Spacer(Modifier.height(14.dp))

            MetalPanel(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                        Color.Transparent,
                                        Color(0xFF5D806D).copy(alpha = 0.07f)
                                    )
                                ),
                                RoundedCornerShape(32.dp)
                            )
                    )

                    Column(Modifier.padding(22.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "CURRENT NETWORK",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.4.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    network,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 38.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    status,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            NetworkPulseOrb(network = network)
                        }

                        Spacer(Modifier.height(18.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatePill(
                                label = if (auto) "WATCHING" else "OFF",
                                active = auto
                            )
                            StatePill(
                                label = when {
                                    network == "5G" -> "5G READY"
                                    network == "4G / LTE" -> "LTE FALLBACK"
                                    else -> network.uppercase(Locale.getDefault())
                                },
                                active = network == "5G"
                            )
                        }

                        Spacer(Modifier.height(18.dp))

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.11f))
                                .border(
                                    BorderStroke(1.dp, Color.White.copy(.24f)),
                                    RoundedCornerShape(14.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("AUTOMATIC 5G RECONNECT", fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = .8.sp)
                                Text(
                                    "Keeps watching after you leave the app.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f)
                                )
                            }
                            Switch(
                                checked = auto,
                                onCheckedChange = { enabled ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
                                        prefs().edit()
                                            .putBoolean(AutoReconnectService.PREF_AUTO_ENABLED, true)
                                            .apply()
                                        startBackgroundWatcher()
                                        status = "Background watcher started."
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            val buttonScale by animateFloatAsState(
                if (pressed) 0.965f else 1f,
                spring(dampingRatio = 0.48f, stiffness = 700f),
                label = "manual_button_scale"
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .scale(buttonScale)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(enabled = !running) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
                                    status = when {
                                        success -> "5G switch confirmed."
                                        result.startsWith("5G re-selection triggered") ->
                                            "Trigger sent · waiting for confirmed 5G."
                                        else -> result
                                    }
                                    running = false
                                    pressed = false
                                }
                            }.start()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(MetalLight, Color(0xFFD0D3D5), Color(0xFF8F9498))
                            ),
                            RoundedCornerShape(20.dp)
                        )
                        .border(
                            BorderStroke(1.dp, Color.White.copy(.80f)),
                            RoundedCornerShape(20.dp)
                        )
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 14.dp)
                        .background(MetalDark.copy(.38f), RoundedCornerShape(50))
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = MetalDeep)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        if (running) "Checking for 5G…" else "RE-TRIGGER 5G",
                        color = MetalDeep,
                        fontWeight = FontWeight.Black,
                        letterSpacing = .6.sp
                    )
                }
            }
            LaunchedEffect(pressed) {
                if (pressed) {
                    delay(100)
                    pressed = false
                }
            }

            /* Legacy Material button retained only as historical reference. */
            /* Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
                                status = when {
                                    success -> "5G switch confirmed."
                                    result.startsWith("5G re-selection triggered") ->
                                        "Trigger sent · waiting for confirmed 5G."
                                    else -> result
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
                    .height(60.dp)
                    .scale(buttonScale),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CurioInk,
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Rounded.Bolt, null)
                Spacer(Modifier.width(9.dp))
                Text(
                    if (running) "Checking for 5G…" else "Re-trigger 5G now",
                    fontWeight = FontWeight.SemiBold
                )
            }
            */

            Spacer(Modifier.height(12.dp))

            val lastEvent = prefs().getString(AutoReconnectService.PREF_LAST_EVENT_DETAIL, null)
            if (!lastEvent.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.52f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "LAST EVENT",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.1.sp
                            )
                            Text(
                                lastEvent,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "The foreground watcher owns monitoring. Closing Recents does not turn it off.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.44f),
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }

    @Composable
    private fun ExpressivePageHeader(
        title: String,
        subtitle: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                Color.White.copy(alpha = 0.52f)
                            )
                        ),
                        RoundedCornerShape(17.dp)
                    )
                    .border(
                        BorderStroke(1.dp, Color.White.copy(alpha = 0.78f)),
                        RoundedCornerShape(17.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
            ExpressivePageHeader("History", "Every observed network and recovery event.", Icons.Rounded.History)
            Spacer(Modifier.height(14.dp))
            HistoryView(this@MainActivity)
        }
    }

    @Composable
    private fun UsagePage() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            ExpressivePageHeader("Usage", "Observed traffic, fallback time and recovery timing.", Icons.Rounded.Analytics)
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
            ExpressivePageHeader("Settings", "Watcher, SIM and recovery behaviour.", Icons.Rounded.Settings)
            Spacer(Modifier.height(14.dp))
            MetalPanel(Modifier.fillMaxWidth()) {
                Text("Recovery", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Retry delay: ${intervalSeconds}s", color = MaterialTheme.colorScheme.onSurface.copy(.65f))
                    Slider(value = intervalSeconds.toFloat(), onValueChange = { intervalSeconds = it.toInt() }, onValueChangeFinished = { prefs().edit().putLong(AutoReconnectService.PREF_INTERVAL_MS, intervalSeconds * 1000L).apply(); refreshWatcherSettings() }, valueRange = 30f..600f, steps = 18)
                    Text("Attempts per recovery: $maxAttempts", color = MaterialTheme.colorScheme.onSurface.copy(.65f))
                    Slider(value = maxAttempts.toFloat(), onValueChange = { maxAttempts = it.toInt() }, onValueChangeFinished = { prefs().edit().putInt(AutoReconnectService.PREF_MAX_ATTEMPTS, maxAttempts).apply(); refreshWatcherSettings() }, valueRange = 1f..8f, steps = 6)
                    Text("Cooldown: $recoveryCooldownMinutes min", color = MaterialTheme.colorScheme.onSurface.copy(.65f))
                    Slider(value = recoveryCooldownMinutes.toFloat(), onValueChange = { recoveryCooldownMinutes = it.toInt() }, onValueChangeFinished = { prefs().edit().putLong(AutoReconnectService.PREF_RECOVERY_COOLDOWN_MS, recoveryCooldownMinutes * 60_000L).apply(); refreshWatcherSettings() }, valueRange = 1f..30f, steps = 28)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Start after restart", fontWeight = FontWeight.Medium)
                    Text("Restart the watcher after boot when automatic reconnect is enabled.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
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
            MetalPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Battery optimization", fontWeight = FontWeight.Medium)
                        Text("For phones that aggressively stop background services.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
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
    MetalPanel(Modifier.fillMaxWidth()) {
        Column {
            Text("SIM & eligibility", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("Choose the SIM monitored for 5G. Failover only runs after 45 seconds with no cellular service.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
            Spacer(Modifier.height(10.dp))
            Text("Monitor this SIM", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            SimPicker(sims, selected, onSelected)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Switch to backup SIM when service disappears"); Text("A 15-minute safety cooldown prevents SIM flapping.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }
                Switch(checked = failover, onCheckedChange = onFailover)
            }
            if (failover) {
                Text("Backup data SIM", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { sims.filter { it.id != selected }.forEach { sim -> FilterChip(selected = backup == sim.id, onClick = { onBackup(sim.id) }, label = { Text(sim.label) }) } }
            }
            Spacer(Modifier.height(10.dp))
            diagnostics.forEach { (label, value) -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(.62f)); Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface) } }
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
    MetalPanel(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Reconnect activity")
                    Text("A quick read on the watcher", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
                }
                TextButton(onClick = { showAll = true }) { Text("View all") }
            }
            Spacer(Modifier.height(10.dp))
            if (entries.isEmpty()) {
                Text("No network changes recorded yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
            } else {
                val successes = entries.count { it.type == "success" || it.detail.contains("confirmed", true) }
                val failures = entries.count { it.type == "failed" || it.detail.contains("failed", true) || it.detail.contains("error", true) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActivityMetric("Success", successes.toString(), Color(0xFF207561), Modifier.weight(1f))
                    ActivityMetric("Failed", failures.toString(), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                }
                entries.firstOrNull()?.let { latest ->
                    Spacer(Modifier.height(13.dp))
                    Text("LATEST", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                    Text(latest.detail.ifBlank { latest.network }, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(latest.time, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
                }
            }
        }
    }
    if (showAll) ModalBottomSheet(onDismissRequest = { showAll = false }, containerColor = MaterialTheme.colorScheme.surface) {
        var filter by rememberSaveable { mutableStateOf("All") }
        var expandedTime by rememberSaveable { mutableLongStateOf(-1L) }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp)); Text("Full activity", fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, buildNetworkReport(context, entries)), "Export reconnect report")) }) { Text("Export") }
                TextButton(onClick = { context.getSharedPreferences(AutoReconnectService.PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().remove(AutoReconnectService.PREF_HISTORY).apply(); entries = emptyList() }) { Text("Clear") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("All", "Success", "Failed", "Network", "System").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }) } }
            val shown = entries.filter { entry -> when (filter) { "Success" -> entry.type == "success"; "Failed" -> entry.type == "failed"; "Network" -> entry.type == "network"; "System" -> entry.type == "system"; else -> true } }
            shown.take(80).forEachIndexed { index, entry ->
                Row(Modifier.fillMaxWidth().background(if (index == 0) MaterialTheme.colorScheme.primary.copy(.10f) else Color.Transparent, RoundedCornerShape(14.dp)).clickable { expandedTime = if (expandedTime == entry.timestamp) -1L else entry.timestamp }.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) { Text(entry.network, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold); Text(entry.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(.62f)); if (expandedTime == entry.timestamp) Text("Type: ${entry.type} · Recorded network: ${entry.network}\nTap to collapse. Additional radio and carrier details are shown in SIM & eligibility.", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
                    Text(entry.time, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable private fun ActivityMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(color.copy(.10f), RoundedCornerShape(16.dp)).padding(12.dp)) { Text(value, fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = color); Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(.60f)) }
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
    MetalPanel(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Analytics, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Column { Text("Mobile data tracker"); Text("Measured while the watcher is on", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(.55f)) } }
            Spacer(Modifier.height(12.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { ActivityMetric("observed on LTE", formatBytes(lte), MaterialTheme.colorScheme.primary, Modifier.weight(1f)); ActivityMetric("observed on 5G", formatBytes(fiveG), Color(0xFF207561), Modifier.weight(1f)) }
            Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { ActivityMetric("LTE fallback minutes", fallbackMinutes.toString(), MaterialTheme.colorScheme.primary, Modifier.weight(1f)); ActivityMetric("avg. trigger → 5G", if (averageRecovery == 0L) "—" else "${averageRecovery}s", Color(0xFF207561), Modifier.weight(1f)) }
            Text("Last 7 days · LTE ${formatBytes(weeklyLte)} · 5G ${formatBytes(weekly5G)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(.62f), modifier = Modifier.padding(top = 9.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text("Billing cycle starts on day $billingDay", fontSize = 11.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(.62f)); TextButton(onClick = { billingDay = if (billingDay == 28) 1 else billingDay + 1; prefs.edit().putInt(AutoReconnectService.PREF_BILLING_DAY, billingDay).apply() }) { Text("Change") } }
            Text("Observed mobile traffic attributed to the sampled radio state; this is not a carrier billing counter.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(.48f), modifier = Modifier.padding(top = 10.dp))
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
