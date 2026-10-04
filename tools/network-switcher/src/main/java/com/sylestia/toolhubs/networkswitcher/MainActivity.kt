@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sylestia.toolhubs.networkswitcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.app.DatePickerDialog
import android.provider.Settings as AndroidSettings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.sylestia.toolhubs.designsystem.ToolHubsTheme
import com.sylestia.toolhubs.designsystem.hubAccents
import com.sylestia.toolhubs.ui.HubBackdrop
import com.sylestia.toolhubs.ui.HubBottomNav
import com.sylestia.toolhubs.ui.HubEmptyState
import com.sylestia.toolhubs.ui.HubGlassCard
import com.sylestia.toolhubs.ui.HubHairline
import com.sylestia.toolhubs.ui.HubMeter
import com.sylestia.toolhubs.ui.HubNavItem
import com.sylestia.toolhubs.ui.HubPill
import com.sylestia.toolhubs.ui.HubPrimaryButton
import com.sylestia.toolhubs.ui.HubProgressRing
import com.sylestia.toolhubs.ui.HubQuietButton
import com.sylestia.toolhubs.ui.HubReveal
import com.sylestia.toolhubs.ui.HubRowDivider
import com.sylestia.toolhubs.ui.HubHeroHeader
import com.sylestia.toolhubs.ui.HubSectionTitle
import com.sylestia.toolhubs.ui.HubSegmented
import com.sylestia.toolhubs.ui.HubSliderRow
import com.sylestia.toolhubs.ui.HubStatTile
import com.sylestia.toolhubs.ui.HubStatusDot
import com.sylestia.toolhubs.ui.HubToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Snapshot of everything the dashboard polls out of the watcher's shared preferences. */
private data class WatcherSnapshot(
    val network: String,
    val status: String,
    val autoEnabled: Boolean,
    val serviceActive: Boolean,
    val paused: Boolean,
    val circuitOpen: Boolean,
    val lastEvent: String?,
    val nextAttemptRemainingMs: Long,
)

internal data class SimChoice(val id: Int, val label: String)

private data class HistoryEntry(
    val timestamp: Long,
    val time: String,
    val network: String,
    val detail: String,
    val type: String,
)

class MainActivity : ComponentActivity() {

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            runOnUiThread { shizukuPermissionResult = true }
            ShizukuHealthMonitor.refresh()
        }
    }

    @Suppress("unused")
    private var shizukuPermissionResult by mutableStateOf(false)
    private var themeMode by mutableStateOf("system")

    private val telephony by lazy { getSystemService(TelephonyManager::class.java) }

    /** Shared with the screen so manual re-triggers can confirm 5G without a second listener. */
    var radioWatcher: RadioWatcher? = null
        private set

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

        themeMode = store(this).getString(AutoReconnectService.PREF_THEME_MODE, "system") ?: "system"
        shizukuPermissionResult = ShizukuHealthMonitor.isReady
        ShizukuHealthMonitor.install()
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        radioWatcher = runCatching {
            RadioWatcher(telephony, ContextCompat.getMainExecutor(this)).also { it.start() }
        }.getOrNull()

        setContent {
            val dark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            ToolHubsTheme(darkTheme = dark) {
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView)
                        .isAppearanceLightStatusBars = !dark
                }
                NetworkApp(
                    context = this,
                    radioWatcher = radioWatcher,
                    themeMode = themeMode,
                    onThemeMode = { mode ->
                        themeMode = mode
                        store(this).edit().putString(AutoReconnectService.PREF_THEME_MODE, mode).apply()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        runCatching { radioWatcher?.stop() }
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    fun hasPhonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun batteryOptimizationExempt(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    fun openBatteryOptimization() {
        runCatching {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }.recoverCatching {
            startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun openShizuku() {
        packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let { startActivity(it) }
    }

    fun requestShizuku() {
        ShizukuHealthMonitor.refresh()
        when (ShizukuHealthMonitor.state) {
            ShizukuHealth.READY -> Unit
            ShizukuHealth.PERMISSION_REQUIRED -> runCatching {
                if (!Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
            else -> openShizuku()
        }
    }

    fun resumeWatcher() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, AutoReconnectService::class.java).setAction(AutoReconnectService.ACTION_RESUME),
        )
    }

    fun stopWatcher() {
        startService(Intent(this, AutoReconnectService::class.java).setAction(AutoReconnectService.ACTION_STOP))
    }

    fun startWatcher() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, AutoReconnectService::class.java).setAction(AutoReconnectService.ACTION_START),
        )
    }

    internal fun currentSims(): List<SimChoice> = runCatching {
        getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().map {
            SimChoice(it.subscriptionId, "SIM " + (it.simSlotIndex + 1) + " / " + it.carrierName)
        }
    }.getOrDefault(emptyList())

    fun diagnostics(state: RadioState): List<Pair<String, String>> {
        val preferred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val types = telephony.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER)
                if (types and TelephonyManager.NETWORK_TYPE_BITMASK_NR != 0L) "5G allowed" else "5G not allowed"
            }.getOrDefault("Unavailable")
        } else {
            "Unavailable on this Android version"
        }
        val data = runCatching { if (telephony.isDataEnabled) "Enabled" else "Disabled" }.getOrDefault("Unavailable")
        val carrier = when {
            state.dataType == TelephonyManager.NETWORK_TYPE_NR -> "NR / standalone detected"
            state.is5G -> "NSA advertised"
            state.detectionReliable -> "Not currently on 5G"
            else -> "Not detectable on this Android version"
        }
        return listOf(
            "Carrier 5G" to carrier,
            "Preferred radio" to preferred,
            "Mobile data" to data,
            "Active SIMs" to currentSims().size.toString(),
        )
    }

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_REQUEST_CODE = 100
        const val MANUAL_CONFIRM_TIMEOUT_MS = 12_000L

        fun store(context: Context) =
            context.getSharedPreferences(AutoReconnectService.PREFS_NAME, Context.MODE_PRIVATE)
    }
}

// ── root screen ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun NetworkApp(
    context: MainActivity,
    radioWatcher: RadioWatcher?,
    themeMode: String,
    onThemeMode: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(readSnapshot(context)) }
    var radio by remember { mutableStateOf(runCatching { radioWatcher?.snapshot() }.getOrNull()) }
    var shizuku by remember { mutableStateOf(ShizukuHealthMonitor.refresh()) }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = readSnapshot(context)
            radio = runCatching { radioWatcher?.snapshot() }.getOrNull()
            shizuku = runCatching { ShizukuHealthMonitor.refresh() }.getOrDefault(ShizukuHealth.UNAVAILABLE)
            delay(1_000)
        }
    }

    val accents = MaterialTheme.hubAccents
    val accent = when {
        radio?.is5G == true -> accents.success
        radio?.isLteFallback == true -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    HubBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            // Insets are applied by the pages themselves, so Scaffold must not add its own
            // system-bar padding on top and push the content down twice.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                HubBottomNav(
                    items = listOf(
                        HubNavItem(Icons.Rounded.NetworkCheck, "Watch"),
                        HubNavItem(Icons.Rounded.Timeline, "Events"),
                        HubNavItem(Icons.Rounded.DataUsage, "Data"),
                        HubNavItem(Icons.Rounded.Tune, "Tune"),
                    ),
                    selectedIndex = page,
                    onSelect = {
                        if (it != page) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            page = it
                        }
                    },
                )
            },
        ) { padding ->
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    (fadeIn(tween(240, easing = FastOutSlowInEasing)) + slideInHorizontally { it / 14 }) togetherWith
                        (fadeOut(tween(150)) + slideOutHorizontally { -it / 14 })
                },
                label = "page",
                modifier = Modifier.fillMaxSize().padding(padding),
            ) { target ->
                when (target) {
                    0 -> WatchPage(context, snapshot, radio, shizuku, accent)
                    1 -> EventsPage(context)
                    2 -> DataPage(context)
                    else -> TunePage(context, themeMode, onThemeMode, radio, shizuku)
                }
            }
        }
    }
}

private fun readSnapshot(context: Context): WatcherSnapshot {
    val store = MainActivity.store(context)
    val nowWall = System.currentTimeMillis()
    val nowElapsed = SystemClock.elapsedRealtime()
    val deadlineWall = store.getLong(AutoReconnectService.PREF_NEXT_ATTEMPT_WALL, 0L)
    val remaining = if (deadlineWall > nowWall) {
        deadlineWall - nowWall
    } else {
        val deadlineElapsed = store.getLong(AutoReconnectService.PREF_NEXT_ATTEMPT, 0L)
        if (deadlineElapsed > nowElapsed) deadlineElapsed - nowElapsed else 0L
    }
    return WatcherSnapshot(
        network = store.getString(AutoReconnectService.PREF_CURRENT_NETWORK, null) ?: "Unknown",
        status = store.getString(AutoReconnectService.PREF_STATUS, null) ?: "Watcher idle",
        autoEnabled = store.getBoolean(AutoReconnectService.PREF_AUTO_ENABLED, false),
        serviceActive = store.getBoolean(AutoReconnectService.PREF_SERVICE_ACTIVE, false),
        paused = store.getBoolean(AutoReconnectService.PREF_PAUSED, false),
        circuitOpen = store.getBoolean(AutoReconnectService.PREF_CIRCUIT_OPEN, false),
        lastEvent = store.getString(AutoReconnectService.PREF_LAST_EVENT_DETAIL, null),
        nextAttemptRemainingMs = remaining,
    )
}

// ── page 1: watch ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun WatchPage(
    context: MainActivity,
    snapshot: WatcherSnapshot,
    radio: RadioState?,
    shizuku: ShizukuHealth,
    accent: Color,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var thermalStatus by remember { mutableIntStateOf(PowerManager.THERMAL_STATUS_NONE) }

    LaunchedEffect(Unit) {
        while (true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                thermalStatus = context.getSystemService(PowerManager::class.java)?.currentThermalStatus
                    ?: PowerManager.THERMAL_STATUS_NONE
            }
            delay(2_000)
        }
    }

    val network = radio?.label ?: snapshot.network
    val live5G = radio?.is5G == true
    val fallback = radio?.isLteFallback == true

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 14.dp, bottom = 22.dp),
    ) {
        HubReveal(0) {
            HubHeroHeader(
                kicker = "5G Reconnect",
                title = "Radio guardian",
                subtitle = "Keeps watch for LTE fallback and pulls you back onto 5G.",
                icon = Icons.Rounded.Bolt,
            )
        }

        Spacer(Modifier.height(16.dp))
        HubReveal(1) { ReadinessCard(context, shizuku) }
        Spacer(Modifier.height(14.dp))

        HubReveal(2) {
            HubGlassCard(accent = accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "CURRENT NETWORK",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.8.sp,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            network,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            snapshot.status,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    RadioOrb(network = network, accent = accent)
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HubPill(
                        label = when {
                            snapshot.paused -> "Paused"
                            snapshot.autoEnabled -> "Watching"
                            else -> "Off"
                        },
                        active = snapshot.autoEnabled && !snapshot.paused,
                        accent = accent,
                    )
                    HubPill(
                        label = when {
                            live5G -> "5G ready"
                            fallback -> "LTE fallback"
                            radio?.detectionReliable == false -> "5G unknown"
                            else -> network
                        },
                        active = live5G,
                        accent = accent,
                    )
                    if (snapshot.circuitOpen) {
                        HubPill("Cooling down", active = false, accent = MaterialTheme.colorScheme.tertiary)
                    }
                }

                if (snapshot.autoEnabled && !snapshot.paused && snapshot.nextAttemptRemainingMs > 0L) {
                    Spacer(Modifier.height(14.dp))
                    HubHairline()
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HubProgressRing(
                            progress = (1f - (snapshot.nextAttemptRemainingMs.toFloat() / AutoReconnectService.MAX_BACKOFF_MS)).coerceIn(0f, 1f),
                            modifier = Modifier.size(38.dp),
                            accent = accent,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Next recovery attempt in " + formatDuration(snapshot.nextAttemptRemainingMs),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                HubHairline()
                Spacer(Modifier.height(14.dp))

                HubToggleRow(
                    title = "Automatic 5G reconnect",
                    subtitle = "Keeps watching after you leave the app.",
                    checked = snapshot.autoEnabled && !snapshot.paused,
                    onCheckedChange = { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (!enabled) {
                            context.stopWatcher()
                            message = "Watcher stopped."
                        } else {
                            // Arm the watcher unconditionally. It gates its own attempts on Shizuku
                            // being ready, so refusing to enable used to leave the user toggling the
                            // switch once they finally started Shizuku.
                            if (!context.hasPhonePermission()) {
                                context.requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 42)
                            }
                            if (shizuku != ShizukuHealth.READY) context.requestShizuku()
                            context.startWatcher()
                            message = if (shizuku == ShizukuHealth.READY) {
                                "Watcher started."
                            } else {
                                "Armed · recovery starts as soon as Shizuku is ready."
                            }
                        }
                    },
                )

                AnimatedVisibility(visible = snapshot.paused && snapshot.autoEnabled) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        HubQuietButton(
                            text = "Resume watching",
                            icon = Icons.Rounded.Refresh,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { context.resumeWatcher() },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        HubReveal(3) {
            HubPrimaryButton(
                text = if (busy) "Checking for 5G…" else "Re-trigger 5G now",
                icon = Icons.Rounded.Bolt,
                loading = busy,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (ShizukuHealthMonitor.state != ShizukuHealth.READY) {
                        context.requestShizuku()
                        message = ShizukuHealthMonitor.state.describe()
                    } else {
                        busy = true
                        message = "Re-triggering 5G…"
                        scope.launch {
                            val watcher = context.radioWatcher
                            val result = withContext(Dispatchers.IO) {
                                runCatching { NetworkSwitcherBridge(context).reconnect5G() }
                                    .getOrElse { "Reconnect failed: " + it.javaClass.simpleName }
                            }
                            val confirmed = withContext(Dispatchers.Default) {
                                val deadline = SystemClock.elapsedRealtime() + MainActivity.MANUAL_CONFIRM_TIMEOUT_MS
                                while (SystemClock.elapsedRealtime() < deadline && watcher?.snapshot()?.is5G != true) {
                                    delay(400)
                                }
                                watcher?.snapshot()?.is5G == true
                            }
                            message = when {
                                confirmed -> "5G switch confirmed."
                                result.startsWith("5G re-selection triggered") -> "Trigger sent · waiting for 5G to confirm."
                                else -> result
                            }
                            busy = false
                        }
                    }
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        HubReveal(4) {
            HubGlassCard(accent = MaterialTheme.colorScheme.tertiary) {
                Text(
                    "Experimental RF recovery",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Open Samsung's RF band selector. Restricting to a stable 5G band can reduce modem hunting and heat, but this is firmware-dependent.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(10.dp))
                HubQuietButton(
                    text = "Open Samsung Band Selection",
                    icon = Icons.Rounded.Settings,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            message = withContext(Dispatchers.IO) {
                                runCatching { NetworkSwitcherBridge(context).openSamsungBandSelection() }
                                    .getOrElse { "Band selection failed: " + it.javaClass.simpleName }
                            }
                        }
                    },
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "This does not disable thermal protection. If the modem is thermally throttling 5G, the app will continue using its normal recovery path rather than bypassing the protection.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Thermal service experiment",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Reported thermal status: $thermalStatus",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ThermalActionButton(
                        text = "Force status 0",
                        icon = Icons.Rounded.Warning,
                        modifier = Modifier.weight(1f),
                        destructive = true,
                        onClick = {
                            scope.launch {
                                message = withContext(Dispatchers.IO) {
                                    runCatching { NetworkSwitcherBridge(context).setThermalStatusOverride(0) }
                                        .getOrElse { "Thermal override failed: " + it.javaClass.simpleName }
                                }
                            }
                        },
                    )
                    ThermalActionButton(
                        text = "Reset",
                        icon = Icons.Rounded.Refresh,
                        modifier = Modifier.weight(1f),
                        destructive = false,
                        onClick = {
                            scope.launch {
                                message = withContext(Dispatchers.IO) {
                                    runCatching { NetworkSwitcherBridge(context).resetThermalStatus() }
                                        .getOrElse { "Thermal reset failed: " + it.javaClass.simpleName }
                                }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "⚠ Status 0 tells Android that the device is not thermally throttling and locks that reported status until reset. It does NOT cool the modem or remove hardware protection. Use only for controlled testing and press Reset afterward.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 10.sp,
                )
            }
        }

        message?.let { text ->
            Spacer(Modifier.height(12.dp))
            HubReveal(0) {
                HubGlassCard(accent = MaterialTheme.colorScheme.primary, contentPadding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HubStatusDot(color = MaterialTheme.colorScheme.primary, pulsing = true)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "STATUS",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.4.sp,
                            )
                            Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        snapshot.lastEvent?.takeIf { it.isNotBlank() }?.let { event ->
            Spacer(Modifier.height(12.dp))
            HubReveal(0) {
                HubGlassCard(contentPadding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HubStatusDot(color = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "LAST EVENT",
                                color = MaterialTheme.colorScheme.tertiary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.4.sp,
                            )
                            Text(
                                event,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        HubReveal(4) { TelemetryGlance(context) }

        Spacer(Modifier.height(14.dp))
        Text(
            "The foreground watcher owns monitoring, so closing Recents does not turn it off.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun ReadinessCard(context: MainActivity, shizuku: ShizukuHealth) {
    val phone = context.hasPhonePermission()
    val notifications = context.hasNotificationPermission()
    val battery = context.batteryOptimizationExempt()

    if (phone && notifications && shizuku == ShizukuHealth.READY) return

    HubGlassCard(accent = MaterialTheme.colorScheme.tertiary) {
        Text("Setup checklist", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "These rows disappear once everything is ready.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(12.dp))

        ChecklistRow(
            label = "Shizuku",
            detail = shizuku.describe(),
            ok = shizuku == ShizukuHealth.READY,
            actionLabel = when (shizuku) {
                ShizukuHealth.PERMISSION_REQUIRED -> "Grant"
                ShizukuHealth.READY -> null
                else -> "Open"
            },
            onAction = { context.requestShizuku() },
        )
        ChecklistRow(
            label = "Phone state",
            detail = if (phone) "Allowed" else "Required to read the radio state",
            ok = phone,
            actionLabel = if (phone) null else "Allow",
            onAction = { context.requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 42) },
        )
        ChecklistRow(
            label = "Notifications",
            detail = if (notifications) "Allowed" else "Needed for the watcher controls",
            ok = notifications,
            actionLabel = if (notifications) null else "Allow",
            onAction = {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
                }
            },
        )
        ChecklistRow(
            label = "Battery optimisation",
            detail = if (battery) "Exempt" else "Recommended so the watcher survives",
            ok = battery,
            actionLabel = if (battery) null else "Open",
            onAction = { context.openBatteryOptimization() },
        )
    }
}

@Composable
private fun ChecklistRow(label: String, detail: String, ok: Boolean, actionLabel: String?, onAction: () -> Unit) {
    val tint = if (ok) MaterialTheme.hubAccents.success else MaterialTheme.colorScheme.tertiary
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (actionLabel != null) HubQuietButton(text = actionLabel, onClick = onAction)
    }
}

/** Breathing radio orb — the single most animated element in the app. */
@Composable
private fun RadioOrb(network: String, accent: Color) {
    val accents = MaterialTheme.hubAccents
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "orb_pulse",
    )
    val breathe by transition.animateFloat(
        0.96f,
        1.04f,
        infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "orb_breathe",
    )
    val tint by animateColorAsState(accent, tween(500), label = "orb_tint")

    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val center = size.center
            val minR = size.minDimension / 2f
            repeat(2) { index ->
                val phase = (pulse + index * 0.5f) % 1f
                drawCircle(
                    color = tint.copy(alpha = 0.30f * (1f - phase)),
                    radius = minR * (0.45f + 0.55f * phase),
                    center = center,
                    style = Stroke(width = 2.5f + 3f * (1f - phase)),
                )
            }
        }
        Box(
            Modifier
                .size(84.dp)
                .graphicsLayer {
                    scaleX = breathe
                    scaleY = breathe
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(tint.copy(alpha = 0.42f), accents.glassTop.copy(alpha = 0.95f))
                    )
                )
                .border(2.dp, tint.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        network == "5G" -> "5G"
                        network == "4G / LTE" -> "LTE"
                        network == "Unknown" -> "—"
                        else -> network.take(3).uppercase(Locale.getDefault())
                    },
                    color = tint,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(5.dp))
                SignalBars(active = activeBars(network), accent = tint)
            }
        }
    }
}

/** How many of the five signal bars light up for a given network label. */
private fun activeBars(network: String): Int = when (network) {
    "5G" -> 5
    "4G / LTE" -> 3
    "3G" -> 2
    "2G" -> 1
    else -> 0
}

/**
 * Five animated signal bars that ramp with the radio generation.
 *
 * The active bars shimmer with a left-to-right falloff, so the orb reads as a live radio rather than
 * a static badge: five bars for 5G, three for LTE, fewer for older radios.
 */
@Composable
private fun SignalBars(active: Int, accent: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "signal_bars")
    val shimmer by transition.animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(1_500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bar_shimmer",
    )
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.width(40.dp).height(17.dp)) {
        val bars = 5
        val gap = size.width * 0.09f
        val barWidth = (size.width - gap * (bars - 1)) / bars
        for (index in 0 until bars) {
            val isActive = index < active
            val heightFraction = 0.32f + 0.17f * index
            val barHeight = size.height * heightFraction
            val intensity = if (isActive) {
                val falloff = 1f - (index.toFloat() / (bars - 1)) * 0.45f
                (0.5f + 0.5f * shimmer * falloff).coerceIn(0f, 1f)
            } else {
                0.16f
            }
            drawRoundRect(
                color = if (isActive) accent.copy(alpha = intensity) else inactiveColor.copy(alpha = intensity),
                topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
private fun TelemetryGlance(context: Context) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }
    val history = tick.let { readNetworkHistory(context) }
    val totals = tick.let { NetworkUsageTracker.readTotals(context) }

    Column {
        HubSectionTitle("Telemetry")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HubStatTile("5G today", formatBytes(totals.todayFiveG), MaterialTheme.hubAccents.success, Modifier.weight(1f))
            HubStatTile("LTE today", formatBytes(totals.todayLte), MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
        }
        Text(
            "Resets at midnight · " + formatBytes(totals.lifetimeFiveG + totals.lifetimeLte) + " observed all time",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
        Spacer(Modifier.height(10.dp))
        HubGlassCard(contentPadding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "LIVE SPEED",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.3.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (totals.currentBps > 0L) formatSpeed(totals.currentBps) else "0 B/s",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Today's peak " + (if (totals.todayPeakBps > 0L) formatSpeed(totals.todayPeakBps) else "—"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                HubProgressRing(
                    progress = if (totals.todayPeakBps > 0L) (totals.todayPeakBps / 60_000_000f).coerceIn(0.05f, 1f) else 0.05f,
                    modifier = Modifier.size(46.dp),
                    accent = MaterialTheme.hubAccents.success,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HubSectionTitle("Recent events")
        Spacer(Modifier.height(6.dp))
        HubGlassCard(contentPadding = 6.dp) {
            if (history.isEmpty()) {
                HubEmptyState("No events yet", "Network changes will appear here as they happen.")
            } else {
                val recent = history.take(3)
                recent.forEachIndexed { index, entry ->
                    HubReveal(index) { EventRow(entry, compact = true) }
                    if (index < recent.lastIndex) HubRowDivider(Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

// ── page 2: events ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun EventsPage(context: Context) {
    var entries by remember { mutableStateOf(readNetworkHistory(context)) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var showExport by remember { mutableStateOf(false) }
    val filters = listOf("All", "Success", "Failed", "Network")

    LaunchedEffect(Unit) {
        while (true) {
            entries = readNetworkHistory(context)
            delay(2_000)
        }
    }

    val shown = entries.filter { entry ->
        when (filter) {
            "Success" -> entry.type == "success"
            "Failed" -> entry.type == "failed"
            "Network" -> entry.type == "network"
            else -> true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 14.dp, bottom = 22.dp),
    ) {
        HubReveal(0) {
            HubHeroHeader(
                kicker = "History",
                title = "Every recovery event",
                subtitle = "Filter, inspect and export what the watcher observed.",
                icon = Icons.Rounded.History,
            )
        }
        Spacer(Modifier.height(16.dp))
        HubReveal(1) {
            HubSegmented(
                options = filters,
                selectedIndex = filters.indexOf(filter).coerceAtLeast(0),
                onSelect = { filter = filters[it] },
            )
        }
        Spacer(Modifier.height(14.dp))
        HubReveal(2) {
            HubGlassCard(contentPadding = 6.dp) {
                if (shown.isEmpty()) {
                    HubEmptyState("Nothing recorded", "Events appear here as the radio changes state.")
                } else {
                    val visible = shown.take(80)
                    visible.forEachIndexed { index, entry ->
                        HubReveal(minOf(index, 8)) { EventRow(entry, compact = false) }
                        if (index < visible.lastIndex) HubRowDivider(Modifier.padding(horizontal = 14.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HubQuietButton(
                text = "Export report",
                icon = Icons.Rounded.OpenInNew,
                modifier = Modifier.weight(1f),
                onClick = { showExport = true },
            )
            HubQuietButton(
                text = "Clear history",
                modifier = Modifier.weight(1f),
                onClick = {
                    MainActivity.store(context).edit().remove(AutoReconnectService.PREF_HISTORY).apply()
                    entries = emptyList()
                },
            )
        }
    }

    if (showExport) {
        ModalBottomSheet(
            onDismissRequest = { showExport = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
                Text(
                    "Reconnect report",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "A JSON summary of observed traffic, fallback time and recovery timing.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(16.dp))
                HubPrimaryButton(
                    text = "Share as JSON",
                    icon = Icons.Rounded.OpenInNew,
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).setType("application/json")
                            .putExtra(Intent.EXTRA_TEXT, buildNetworkReport(context, entries))
                        context.startActivity(Intent.createChooser(share, "Export reconnect report"))
                        showExport = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EventRow(entry: HistoryEntry, compact: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = if (compact) 9.dp else 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        EventBadge(entry)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.network,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 12.sp else 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    entry.time,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                entry.detail.ifBlank { "Network state observed" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = if (compact) 10.sp else 11.sp,
                maxLines = if (compact) 1 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EventBadge(entry: HistoryEntry) {
    val accents = MaterialTheme.hubAccents
    val label: String
    val tint: Color
    when (entry.type) {
        "success" -> {
            label = "OK"
            tint = accents.success
        }
        "failed" -> {
            label = "FAIL"
            tint = MaterialTheme.colorScheme.error
        }
        "attempt" -> {
            label = "TRY"
            tint = MaterialTheme.colorScheme.secondary
        }
        "network" -> {
            label = "MOVE"
            tint = MaterialTheme.colorScheme.tertiary
        }
        "sim_failover" -> {
            label = "SIM"
            tint = MaterialTheme.colorScheme.tertiary
        }
        "warning" -> {
            label = "WARN"
            tint = MaterialTheme.hubAccents.warning
        }
        else -> {
            label = entry.network.replace(" / LTE", "").take(4).uppercase(Locale.getDefault())
            tint = MaterialTheme.colorScheme.primary
        }
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.26f), RoundedCornerShape(9.dp))
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        Text(label, color = tint, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp)
    }
}

// ── page 3: data ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun DataPage(context: Context) {
    val store = remember { MainActivity.store(context) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }
    val history = tick.let { readNetworkHistory(context) }
    val totals = tick.let { NetworkUsageTracker.readTotals(context) }
    val daily = tick.let { NetworkUsageTracker.dailySeries(context, 7) }
    var billingDay by rememberSaveable { mutableIntStateOf(store.getInt(AutoReconnectService.PREF_BILLING_DAY, 1)) }

    val warningEnabled = store.getBoolean(AutoReconnectService.PREF_LTE_WARNING_ENABLED, false)
    val warningLimit = store.getLong(AutoReconnectService.PREF_LTE_WARNING_BYTES, 0L)

    val sessions = history.sortedBy { it.timestamp }.zipWithNext()
        .map { pair -> pair.first.network to (pair.second.timestamp - pair.first.timestamp).coerceAtLeast(0L) }
    val liveFallback = run {
        val started = store.getLong(AutoReconnectService.PREF_FALLBACK_STARTED, 0L)
        val now = SystemClock.elapsedRealtime()
        if (started > 0L && started <= now) now - started else 0L
    }
    val fiveGTime = sessions.filter { it.first == "5G" }.sumOf { it.second }
    val fallbackTotal = store.getLong(AutoReconnectService.PREF_LTE_FALLBACK_MS, 0L) + liveFallback
    val triggerCount = store.getInt(AutoReconnectService.PREF_TRIGGER_RECOVERY_COUNT, 0)
    val avgRecovery = if (triggerCount == 0) 0L else
        store.getLong(AutoReconnectService.PREF_TRIGGER_RECOVERY_TOTAL_MS, 0L) / triggerCount / 1_000L
    val weeklyLte = daily.sumOf { it.lte }
    val weekly5G = daily.sumOf { it.fiveG }
    val todayTotal = totals.todayLte + totals.todayFiveG

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 14.dp, bottom = 22.dp),
    ) {
        HubReveal(0) {
            HubHeroHeader(
                kicker = "Usage",
                title = "Observed traffic",
                subtitle = "Day-by-day mobile traffic, sampled by the radio that carried it.",
                icon = Icons.Rounded.Analytics,
            )
        }
        Spacer(Modifier.height(16.dp))
        HubReveal(1) {
            HubGlassCard(accent = MaterialTheme.hubAccents.success) {
                Text(
                    "TODAY · RESETS AT MIDNIGHT",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.hubAccents.success,
                )
                Text(
                    formatBytes(todayTotal),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    formatBytes(totals.todayFiveG) + " on 5G · " + formatBytes(totals.todayLte) + " on LTE",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(14.dp))
                NetworkSplitBar(lte = totals.todayLte, fiveG = totals.todayFiveG)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HubStatTile("5G time", formatDuration(fiveGTime), MaterialTheme.hubAccents.success, Modifier.weight(1f))
            HubStatTile("LTE fallback", formatDuration(fallbackTotal), MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        HubReveal(3) {
            HubGlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "LIVE SPEED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (totals.currentBps > 0L) formatSpeed(totals.currentBps) else "0 B/s",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Today's peak " + (if (totals.todayPeakBps > 0L) formatSpeed(totals.todayPeakBps) else "—"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    HubProgressRing(
                        progress = if (totals.todayPeakBps > 0L) (totals.todayPeakBps / 60_000_000f).coerceIn(0.05f, 1f) else 0.05f,
                        modifier = Modifier.size(54.dp),
                        accent = MaterialTheme.hubAccents.success,
                    )
                }
                Spacer(Modifier.height(10.dp))
                HubHairline()
                Spacer(Modifier.height(10.dp))
                Text(
                    if (totals.todayPeakBps > 0L) {
                        (totals.todayPeakNetwork ?: "Mobile") + " · peak at " + formatTimestamp(totals.todayPeakAt)
                    } else {
                        "No peak recorded yet today."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Measured from successive mobile-traffic samples while the watcher runs; resets each day.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HubReveal(4) { LteWarningCard(warningEnabled, warningLimit, totals.todayLte) }
        Spacer(Modifier.height(12.dp))
        HubSectionTitle("Last 7 days")
        Spacer(Modifier.height(8.dp))
        HubReveal(5) {
            HubGlassCard(contentPadding = 6.dp) {
                daily.forEachIndexed { index, day ->
                    DailyUsageRow(day)
                    if (index < daily.lastIndex) HubRowDivider(Modifier.padding(horizontal = 14.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HubReveal(6) {
            HubGlassCard {
                Text(
                    "RECOVERY TIMING",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubStatTile(
                        "Successful recoveries",
                        store.getInt(AutoReconnectService.PREF_RECOVERY_COUNT, 0).toString(),
                        MaterialTheme.hubAccents.success,
                        Modifier.weight(1f),
                    )
                    HubStatTile(
                        "Avg trigger to 5G",
                        if (avgRecovery == 0L) "—" else avgRecovery.toString() + "s",
                        MaterialTheme.colorScheme.secondary,
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                HubHairline()
                Spacer(Modifier.height(12.dp))
                Text(
                    "Last 7 days · LTE " + formatBytes(weeklyLte) + " · 5G " + formatBytes(weekly5G),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Billing cycle starts on day " + billingDay,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    HubQuietButton(text = "Choose date") {
                        val today = Calendar.getInstance()
                        DatePickerDialog(
                            context,
                            { _, _, _, dayOfMonth ->
                                billingDay = dayOfMonth
                                store.edit().putInt(AutoReconnectService.PREF_BILLING_DAY, billingDay).apply()
                            },
                            today.get(Calendar.YEAR),
                            today.get(Calendar.MONTH),
                            billingDay.coerceIn(1, today.getActualMaximum(Calendar.DAY_OF_MONTH)),
                        ).apply {
                            setTitle("Billing cycle start")
                        }.show()
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Android exposes aggregate mobile traffic, not carrier-billing bytes split by radio. Each " +
                "delta is attributed to the radio state active during the sample, so this is an " +
                "observation rather than an exact billing counter.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun NetworkSplitBar(lte: Long, fiveG: Long, height: Dp = 12.dp, showLegend: Boolean = true) {
    val accents = MaterialTheme.hubAccents
    val total = (lte + fiveG).coerceAtLeast(1L)
    val fraction by animateFloatAsState(
        (fiveG.toFloat() / total).coerceIn(0f, 1f),
        spring(dampingRatio = 0.85f, stiffness = 170f),
        label = "split",
    )
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.30f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(accents.success, accents.auroraStart))),
            )
        }
        if (showLegend) {
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(accents.success, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("5G", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("LTE", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LteWarningCard(enabled: Boolean, limitBytes: Long, usedToday: Long) {
    val reached = enabled && limitBytes > 0L && usedToday >= limitBytes
    val accent = if (reached) MaterialTheme.hubAccents.warning else MaterialTheme.colorScheme.secondary
    HubGlassCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "4G DATA WARNING",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = accent,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        !enabled -> "Off"
                        reached -> "Limit reached today"
                        else -> "Watching today's LTE usage"
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        if (enabled && limitBytes > 0L) {
            Spacer(Modifier.height(12.dp))
            HubMeter(fraction = (usedToday.toFloat() / limitBytes).coerceIn(0f, 1f), accent = accent)
            Spacer(Modifier.height(8.dp))
            Text(
                formatBytes(usedToday) + " of " + formatBytes(limitBytes) + " today",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                "Set a daily LTE limit in Tune to get a heads-up before you run over.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun DailyUsageRow(day: DailyUsage) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                day.label,
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Text(
                if (day.peakBps > 0L) "peak " + formatSpeed(day.peakBps) else "no peak",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            formatBytes(day.fiveG) + " on 5G · " + formatBytes(day.lte) + " on LTE",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(7.dp))
        NetworkSplitBar(lte = day.lte, fiveG = day.fiveG, height = 8.dp, showLegend = false)
    }
}

// ── page 4: tune ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun TunePage(
    context: MainActivity,
    themeMode: String,
    onThemeMode: (String) -> Unit,
    radio: RadioState?,
    shizuku: ShizukuHealth,
) {
    val store = MainActivity.store(context)
    val sims = remember { context.currentSims() }
    val watcherOn = store.getBoolean(AutoReconnectService.PREF_AUTO_ENABLED, false)

    var startAfterRestart by rememberSaveable {
        mutableStateOf(store.getBoolean(AutoReconnectService.PREF_START_AFTER_REBOOT, false))
    }
    var selectedSim by rememberSaveable {
        mutableIntStateOf(store.getInt(AutoReconnectService.PREF_SELECTED_SUBSCRIPTION, SubscriptionManager.INVALID_SUBSCRIPTION_ID))
    }
    var backupSim by rememberSaveable {
        mutableIntStateOf(store.getInt(AutoReconnectService.PREF_FAILOVER_TARGET, SubscriptionManager.INVALID_SUBSCRIPTION_ID))
    }
    var failover by rememberSaveable {
        mutableStateOf(store.getBoolean(AutoReconnectService.PREF_FAILOVER_ENABLED, false))
    }
    var intervalSeconds by rememberSaveable {
        mutableIntStateOf((store.getLong(AutoReconnectService.PREF_INTERVAL_MS, AutoReconnectService.DEFAULT_INTERVAL_MS) / 1000L).toInt())
    }
    var cooldownMinutes by rememberSaveable {
        mutableIntStateOf((store.getLong(AutoReconnectService.PREF_RECOVERY_COOLDOWN_MS, AutoReconnectService.DEFAULT_RECOVERY_COOLDOWN_MS) / 60_000L).toInt())
    }
    var maxAttempts by rememberSaveable {
        mutableIntStateOf(store.getInt(AutoReconnectService.PREF_MAX_ATTEMPTS, AutoReconnectService.DEFAULT_MAX_ATTEMPTS).coerceIn(1, 8))
    }
    var warningEnabled by rememberSaveable {
        mutableStateOf(store.getBoolean(AutoReconnectService.PREF_LTE_WARNING_ENABLED, false))
    }
    var warningGb by rememberSaveable {
        mutableFloatStateOf(
            (store.getLong(AutoReconnectService.PREF_LTE_WARNING_BYTES, AutoReconnectService.DEFAULT_LTE_WARNING_BYTES).toFloat() / 1_073_741_824f)
                .coerceIn(0.5f, 20f)
        )
    }
    var lteFailover by rememberSaveable {
        mutableStateOf(store.getBoolean(AutoReconnectService.PREF_LTE_FAILOVER_ENABLED, false))
    }
    var lteFailoverSim by rememberSaveable {
        mutableIntStateOf(store.getInt(AutoReconnectService.PREF_LTE_FAILOVER_TARGET, SubscriptionManager.INVALID_SUBSCRIPTION_ID))
    }

    fun refreshWatcher() {
        if (store.getBoolean(AutoReconnectService.PREF_AUTO_ENABLED, false)) context.startWatcher()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 14.dp, bottom = 22.dp),
    ) {
        HubReveal(0) {
            HubHeroHeader(
                kicker = "Settings",
                title = "Tune the guardian",
                subtitle = "Appearance, recovery behaviour, SIM choice and diagnostics.",
                icon = Icons.Rounded.Settings,
            )
        }

        Spacer(Modifier.height(16.dp))

        HubReveal(1) {
            HubGlassCard {
                Text("Appearance", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "The whole surface cross-fades when you switch, in both directions.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(12.dp))
                HubSegmented(
                    options = listOf("System", "Light", "Dark"),
                    selectedIndex = listOf("system", "light", "dark").indexOf(themeMode).coerceAtLeast(0),
                    onSelect = { onThemeMode(listOf("system", "light", "dark")[it]) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        HubReveal(2) {
            HubGlassCard {
                Text("Recovery", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "How hard the watcher tries before it cools down.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(6.dp))
                HubSliderRow(
                    title = "Base retry delay",
                    valueLabel = intervalSeconds.toString() + "s",
                    value = intervalSeconds.toFloat(),
                    range = 30f..600f,
                    steps = 18,
                    onValueChange = { intervalSeconds = it.toInt() },
                    onValueChangeFinished = {
                        store.edit().putLong(AutoReconnectService.PREF_INTERVAL_MS, intervalSeconds * 1000L).apply()
                        refreshWatcher()
                    },
                )
                HubSliderRow(
                    title = "Failed attempts before cooling down",
                    valueLabel = maxAttempts.toString(),
                    value = maxAttempts.toFloat(),
                    range = 1f..8f,
                    steps = 6,
                    onValueChange = { maxAttempts = it.toInt() },
                    onValueChangeFinished = {
                        store.edit().putInt(AutoReconnectService.PREF_MAX_ATTEMPTS, maxAttempts).apply()
                        refreshWatcher()
                    },
                )
                HubSliderRow(
                    title = "Cooldown",
                    valueLabel = cooldownMinutes.toString() + " min",
                    value = cooldownMinutes.toFloat(),
                    range = 1f..30f,
                    steps = 28,
                    onValueChange = { cooldownMinutes = it.toInt() },
                    onValueChangeFinished = {
                        store.edit().putLong(AutoReconnectService.PREF_RECOVERY_COOLDOWN_MS, cooldownMinutes * 60_000L).apply()
                        refreshWatcher()
                    },
                )
                HubHairline()
                Spacer(Modifier.height(10.dp))
                HubToggleRow(
                    title = "Start after restart",
                    subtitle = "Re-arm the watcher after a reboot, once the radio has settled.",
                    checked = startAfterRestart,
                    onCheckedChange = {
                        startAfterRestart = it
                        store.edit().putBoolean(AutoReconnectService.PREF_START_AFTER_REBOOT, it).apply()
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        HubReveal(3) {
            val ready = shizuku == ShizukuHealth.READY
            HubGlassCard(accent = if (ready) MaterialTheme.hubAccents.success else MaterialTheme.colorScheme.tertiary) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Privileged access", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(
                            shizuku.describe(),
                            color = if (ready) MaterialTheme.hubAccents.success else MaterialTheme.colorScheme.tertiary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    HubStatusDot(
                        color = if (ready) MaterialTheme.hubAccents.success else MaterialTheme.colorScheme.tertiary,
                        pulsing = !ready,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubQuietButton("Recheck", icon = Icons.Rounded.Refresh, modifier = Modifier.weight(1f)) {
                        ShizukuHealthMonitor.refresh()
                    }
                    HubQuietButton("Open Shizuku", icon = Icons.Rounded.OpenInNew, modifier = Modifier.weight(1f)) {
                        context.openShizuku()
                    }
                }
                if (!ready) {
                    Spacer(Modifier.height(10.dp))
                    HubPrimaryButton(text = "Grant Shizuku permission", icon = Icons.Rounded.CheckCircle) {
                        context.requestShizuku()
                    }
                }
            }
        }

        if (sims.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HubReveal(4) {
                HubGlassCard {
                    Text("SIM & eligibility", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        "Pick the SIM whose 5G state is monitored. Failover only runs after 45 seconds with no service.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Monitor this SIM", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        sims.forEach { sim ->
                            SimChip(
                                label = sim.label,
                                selected = sim.id == selectedSim,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    selectedSim = sim.id
                                    store.edit().putInt(AutoReconnectService.PREF_SELECTED_SUBSCRIPTION, sim.id).apply()
                                    refreshWatcher()
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    HubToggleRow(
                        title = "Switch to a backup SIM when service disappears",
                        subtitle = "A 15-minute cooldown prevents SIM flapping.",
                        checked = failover,
                        onCheckedChange = {
                            failover = it
                            store.edit().putBoolean(AutoReconnectService.PREF_FAILOVER_ENABLED, it).apply()
                        },
                    )
                    if (failover) {
                        Spacer(Modifier.height(8.dp))
                        Text("Backup data SIM", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            sims.filter { it.id != selectedSim }.forEach { sim ->
                                SimChip(
                                    label = sim.label,
                                    selected = sim.id == backupSim,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        backupSim = sim.id
                                        store.edit().putInt(AutoReconnectService.PREF_FAILOVER_TARGET, sim.id).apply()
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HubHairline()
                    Spacer(Modifier.height(12.dp))
                    Text("Diagnostics", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    val state = radio ?: RadioState("Unknown", 0, false, false, false)
                    context.diagnostics(state).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.first, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(row.second, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        HubReveal(5) {
            HubGlassCard {
                Text("Data guard", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "Watch today's observed 4G traffic and act before you run over.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(10.dp))
                HubToggleRow(
                    title = "Warn when 4G reaches a daily limit",
                    subtitle = "One notification a day, the first time today's LTE traffic crosses the limit.",
                    checked = warningEnabled,
                    onCheckedChange = { enabled ->
                        warningEnabled = enabled
                        val editor = store.edit().putBoolean(AutoReconnectService.PREF_LTE_WARNING_ENABLED, enabled)
                        if (enabled && store.getLong(AutoReconnectService.PREF_LTE_WARNING_BYTES, 0L) <= 0L) {
                            // Seed the default so the switch works even if the slider is never touched.
                            editor.putLong(AutoReconnectService.PREF_LTE_WARNING_BYTES, (warningGb * 1_073_741_824f).toLong())
                        }
                        editor.apply()
                        refreshWatcher()
                    },
                )
                if (warningEnabled && !watcherOn) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Turn on “Automatic 5G reconnect” on the Watch tab so the limit can be checked while the app is closed.",
                        color = MaterialTheme.hubAccents.warning,
                        fontSize = 11.sp,
                    )
                }
                if (warningEnabled) {
                    HubSliderRow(
                        title = "Daily 4G limit",
                        valueLabel = String.format(Locale.getDefault(), "%.1f GB", warningGb),
                        value = warningGb,
                        range = 0.5f..20f,
                        steps = 38,
                        onValueChange = { warningGb = it },
                        onValueChangeFinished = {
                            store.edit().putLong(
                                AutoReconnectService.PREF_LTE_WARNING_BYTES,
                                (warningGb * 1_073_741_824f).toLong(),
                            ).apply()
                            refreshWatcher()
                        },
                    )
                }
                if (sims.size >= 2) {
                    Spacer(Modifier.height(6.dp))
                    HubHairline()
                    Spacer(Modifier.height(10.dp))
                    HubToggleRow(
                        title = "Use a backup SIM while on 4G",
                        subtitle = "Move mobile data to the backup SIM during an LTE fallback, then back once 5G returns.",
                        checked = lteFailover,
                        onCheckedChange = { enabled ->
                            lteFailover = enabled
                            store.edit().putBoolean(AutoReconnectService.PREF_LTE_FAILOVER_ENABLED, enabled).apply()
                            refreshWatcher()
                        },
                    )
                    if (lteFailover) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (selectedSim != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                                "Backup SIM"
                            } else {
                                "Pick a SIM to monitor above first"
                            },
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            sims.filter { it.id != selectedSim }.forEach { sim ->
                                SimChip(
                                    label = sim.label,
                                    selected = sim.id == lteFailoverSim,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        lteFailoverSim = sim.id
                                        store.edit().putInt(AutoReconnectService.PREF_LTE_FAILOVER_TARGET, sim.id).apply()
                                        refreshWatcher()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        HubReveal(6) {
            HubGlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Battery optimisation", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "For phones that aggressively stop background services.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    HubQuietButton("Open") { context.openBatteryOptimization() }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "5G detection follows Android's TelephonyDisplayInfo, so non-standalone 5G is only " +
                    "distinguishable on Android 11 and newer.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ThermalActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(stiffness = 700f, dampingRatio = 0.72f),
        label = "thermal_button_scale",
    )
    val container = if (destructive) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (destructive) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurface
    val borderColor = if (destructive) MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
    else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)

    Row(
        modifier
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = content, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SimChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

// ── shared helpers ───────────────────────────────────────────────────────────────────────────────

private fun readNetworkHistory(context: Context): List<HistoryEntry> {
    val values = MainActivity.store(context)
        .getStringSet(AutoReconnectService.PREF_HISTORY, emptySet()).orEmpty()
    val format = SimpleDateFormat("dd MMM · HH:mm:ss", Locale.getDefault())
    return values.mapNotNull { raw ->
        runCatching {
            val json = org.json.JSONObject(raw)
            val timestamp = json.getLong("time")
            HistoryEntry(
                timestamp = timestamp,
                time = format.format(Date(timestamp)),
                network = json.optString("network", "Unknown"),
                detail = json.optString("detail", ""),
                type = json.optString("type", "system"),
            )
        }.getOrNull()
    }.sortedByDescending { it.timestamp }
}

private fun buildNetworkReport(context: Context, entries: List<HistoryEntry>): String {
    val store = MainActivity.store(context)
    return org.json.JSONObject().apply {
        put("generatedAt", System.currentTimeMillis())
        put("lteFallbackBytes", store.getLong(NetworkUsageTracker.KEY_LTE_BYTES, 0L))
        put("fiveGBytes", store.getLong(NetworkUsageTracker.KEY_5G_BYTES, 0L))
        put("fallbackMinutes", store.getLong(AutoReconnectService.PREF_LTE_FALLBACK_MS, 0L) / 60_000L)
        put("averageTriggerTo5GSeconds", run {
            val count = store.getInt(AutoReconnectService.PREF_TRIGGER_RECOVERY_COUNT, 0)
            if (count == 0) 0L else store.getLong(AutoReconnectService.PREF_TRIGGER_RECOVERY_TOTAL_MS, 0L) / count / 1_000L
        })
        put("events", org.json.JSONArray().apply {
            entries.forEach { entry ->
                put(
                    org.json.JSONObject()
                        .put("time", entry.timestamp)
                        .put("network", entry.network)
                        .put("type", entry.type)
                        .put("detail", entry.detail)
                )
            }
        })
    }.toString(2)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824f)
    bytes >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576f)
    bytes >= 1_024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1_024f)
    else -> bytes.toString() + " B"
}

private fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000L -> String.format(Locale.getDefault(), "%.1f MB/s", bytesPerSecond / 1_000_000f)
    bytesPerSecond >= 1_000L -> String.format(Locale.getDefault(), "%.0f KB/s", bytesPerSecond / 1_000f)
    else -> bytesPerSecond.toString() + " B/s"
}

private fun formatTimestamp(timestamp: Long): String =
    if (timestamp <= 0L) "—" else SimpleDateFormat("dd MMM · HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000L
    return when {
        seconds >= 3600L -> (seconds / 3600).toString() + "h " + ((seconds % 3600) / 60).toString() + "m"
        seconds >= 60L -> (seconds / 60).toString() + "m " + (seconds % 60).toString() + "s"
        else -> seconds.toString() + "s"
    }
}
