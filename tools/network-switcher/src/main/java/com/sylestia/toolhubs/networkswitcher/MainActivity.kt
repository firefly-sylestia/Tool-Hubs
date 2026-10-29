@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sylestia.toolhubs.networkswitcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.app.DatePickerDialog
import android.provider.Settings as AndroidSettings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timeline
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
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import androidx.core.view.WindowCompat
import com.sylestia.toolhubs.designsystem.ToolHubsTheme
import com.sylestia.toolhubs.designsystem.hubAccents
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

/**
 * One line of the watcher's event log, as the pages and the analytics read it.
 *
 * It is internal rather than private because the derivations in [NetworkInsights] and the graph
 * buckets in [NetworkGraphs] work on exactly these entries and must not re-parse the log themselves.
 */
internal data class HistoryEntry(
    val timestamp: Long,
    val time: String,
    val network: String,
    val detail: String,
    val type: String,
)

/**
 * Not a tab index. The settings page is pushed above the four tabs, so navigation never points at
 * it and its state lives outside [MainActivity]'s rememberSaveable page counter.
 */
private const val SETTINGS_PAGE = 4

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
                NetworkSwitcherExpressiveTheme(darkTheme = dark) {
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
    }

    override fun onDestroy() {
        runCatching { radioWatcher?.stop() }
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    fun hasPhonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /**
     * Current signal level (0–4), or -1 when the platform will not report it.
     *
     * Guarded twice on purpose: `getSignalStrength` only exists from API 28, and on several devices it
     * still throws when the location permission it quietly depends on is missing. A graph must never be
     * the reason the screen crashes.
     */
    fun signalLevel(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return -1
        return runCatching { telephony.signalStrength?.level ?: -1 }.getOrDefault(-1)
    }

    /** The label the live samples should be stamped with, read straight from the radio watcher. */
    fun liveLabel(): String = runCatching { radioWatcher?.snapshot()?.label }.getOrNull() ?: "Unknown"

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

    fun openNotificationSettings() {
        runCatching {
            startActivity(
                Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName),
            )
        }
    }

    /**
     * True when the tray can actually show the watcher's status. When this is false the ongoing
     * notification is invisible even though the foreground service is running, which is one reason
     * a cooldown can look like it happened with no indicator at all.
     */
    fun notificationsEnabled(): Boolean = runCatching {
        hasNotificationPermission() &&
            getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() == true
    }.getOrDefault(false)

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

    /** Hands every active failover back: the data SIM first, then the Wi-Fi radio. */
    fun revertFailovers() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, AutoReconnectService::class.java).setAction(AutoReconnectService.ACTION_REVERT),
        )
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
    var page by rememberSaveable { mutableIntStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
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

    SwitcherBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            // Insets are applied by the pages themselves, so Scaffold must not add its own
            // system-bar padding on top and push the content down twice.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                // Settings is pushed on top of the tabs, so the bar steps out of the way and the
                // page reads as its own destination rather than a fifth tab.
                if (!settingsOpen) {
                    SwitcherBottomNav(
                        items = listOf(
                            HubNavItem(Icons.Rounded.NetworkCheck, "Watch"),
                            HubNavItem(Icons.Rounded.Timeline, "Events"),
                            HubNavItem(Icons.Rounded.DataUsage, "Data"),
                            HubNavItem(Icons.Rounded.History, "History"),
                        ),
                        selectedIndex = page,
                        onSelect = { page = it },
                    )
                }
            },
        ) { padding ->
            AnimatedContent(
                targetState = if (settingsOpen) SETTINGS_PAGE else page,
                transitionSpec = {
                    when {
                        targetState == SETTINGS_PAGE ->
                            (fadeIn(tween(220, easing = FastOutSlowInEasing)) + slideInHorizontally { it / 8 }) togetherWith
                                (fadeOut(tween(120)) + slideOutHorizontally { -it / 20 })
                        initialState == SETTINGS_PAGE ->
                            (fadeIn(tween(220, easing = FastOutSlowInEasing)) + slideInHorizontally { -it / 8 }) togetherWith
                                (fadeOut(tween(120)) + slideOutHorizontally { it / 20 })
                        else ->
                            (fadeIn(tween(240, easing = FastOutSlowInEasing)) + slideInHorizontally { it / 14 }) togetherWith
                                (fadeOut(tween(150)) + slideOutHorizontally { -it / 14 })
                    }
                },
                label = "page",
                modifier = Modifier.fillMaxSize().padding(padding),
            ) { target ->
                when (target) {
                    0 -> WatchPage(context, snapshot, radio, shizuku, accent) { settingsOpen = true }
                    1 -> EventsPage(context) { settingsOpen = true }
                    2 -> DataPage(
                        context,
                        onOpenHistory = { page = 3 },
                        onOpenSettings = { settingsOpen = true },
                    )
                    3 -> UsageHistoryPage(context) { settingsOpen = true }
                    else -> SettingsPage(context, themeMode, onThemeMode, radio, shizuku) { settingsOpen = false }
                }
            }
        }
    }

    // Settings is a pushed page, so the system back gesture closes it before it leaves the app.
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
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
    onOpenSettings: () -> Unit,
) {
    val scroll = rememberScrollState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val network = radio?.label ?: snapshot.network
    val live5G = radio?.is5G == true
    val fallback = radio?.isLteFallback == true

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 14.dp, bottom = 22.dp),
    ) {
        // The header pins itself, so it has to paint above the cards that scroll underneath it.
        HubReveal(0, Modifier.zIndex(1f)) {
            SwitcherHeader(
                kicker = "5G Reconnect",
                title = "Radio guardian",
                subtitle = "Keeps watch for LTE fallback and pulls you back onto 5G.",
                icon = Icons.Rounded.Bolt,
                scrollState = scroll,
                onOpenSettings = onOpenSettings,
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

        Spacer(Modifier.height(16.dp))
        HubReveal(5) { LiveGraphCard(context, accent) }

        Spacer(Modifier.height(16.dp))
        HubReveal(6) { HealthCard(context) }

        Spacer(Modifier.height(16.dp))
        HubReveal(7) { WhyChangedCard(context) }

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
                    HubReveal(index) {
                        EventGroupCard(
                            group = EventGroup(entry, 1, entry.timestamp),
                            expanded = false,
                            showChevron = false,
                            onToggle = {},
                        )
                    }
                    if (index < recent.lastIndex) HubRowDivider(Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

// ── page 2: events ───────────────────────────────────────────────────────────────────────────────

private data class EventGroup(
    val first: HistoryEntry,
    val count: Int,
    val lastTimestamp: Long,
)

@Composable
private fun EventsPage(context: Context, onOpenSettings: () -> Unit) {
    val scroll = rememberScrollState()
    var entries by remember { mutableStateOf(readNetworkHistory(context)) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var showExport by remember { mutableStateOf(false) }
    var selectedHour by rememberSaveable { mutableStateOf<Int?>(null) }
    var expandedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    val filters = listOf("All", "Success", "Failed", "Network", "SIM", "Recovery")

    LaunchedEffect(Unit) {
        while (true) {
            entries = readNetworkHistory(context)
            delay(2_000)
        }
    }

    // 24 hourly buckets, oldest first. The hour the user picks is an index into exactly this list,
    // which is what keeps the graph and the list from disagreeing about which hour is open.
    val buckets = remember(entries) { incidentBuckets(entries, System.currentTimeMillis()) }
    val hourStartAt = selectedHour?.let { buckets.getOrNull(it)?.startAt }

    // The hour filter is applied *first* and the type filter second, so the segmented control always
    // describes the events of the selected hour rather than a slice of the whole day.
    val inHour = if (hourStartAt == null) entries else entries.filter {
        it.timestamp >= hourStartAt && it.timestamp < hourStartAt + INCIDENT_BUCKET_MS
    }
    val filtered = inHour.filter { matchesEventFilter(it, filter) }
    val groups = collapseDuplicateEvents(filtered)
    val failureCount = filtered.count { it.type == "failed" }
    val recoveryCount = filtered.count { it.type == "success" || it.type == "failed" || it.type == "attempt" }
    val flaggedHours = buckets.count { it.failures > 0 }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding()
            .padding(horizontal = 18.dp).padding(top = 14.dp, bottom = 22.dp),
    ) {
        HubReveal(0, Modifier.zIndex(1f)) {
            SwitcherHeader(
                kicker = "Activity",
                title = "Network timeline",
                subtitle = "Repeated watcher noise is grouped into one event so the useful signal stays visible.",
                icon = Icons.Rounded.Timeline,
                scrollState = scroll,
                onOpenSettings = onOpenSettings,
            )
        }
        Spacer(Modifier.height(16.dp))

        HubReveal(1) {
            HubGlassCard(accent = if (flaggedHours > 0) MaterialTheme.colorScheme.error else MaterialTheme.hubAccents.success) {
                Text(
                    "INCIDENT TIMELINE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.4.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                IncidentGraph(
                    buckets = buckets,
                    bucketMs = INCIDENT_BUCKET_MS,
                    now = System.currentTimeMillis(),
                    selectedIndex = selectedHour,
                    onSelect = {
                        selectedHour = it
                        expandedAt = null
                    },
                )
                if (selectedHour != null) {
                    Spacer(Modifier.height(10.dp))
                    HubQuietButton(
                        text = "Clear hour filter",
                        icon = Icons.Rounded.Refresh,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { selectedHour = null },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        /*
         * These two rows live in a Column, not loose inside the reveal.
         *
         * HubReveal draws its content into a Box, and a Box lays several children on top of each
         * other — so the two rows of stats were literally overlapping, which is what "the grouped /
         * hours cards overlap" was. One child, laid out as a column of rows.
         *
         * The rows measure to their tallest tile and every tile fills that height, so a tile whose
         * label wraps ("hours with failures") no longer stands taller than its neighbour.
         */
        HubReveal(2) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HubStatTile(
                        if (hourStartAt == null) "Events · 24h" else "Events · hour",
                        filtered.size.toString(),
                        MaterialTheme.colorScheme.primary,
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                    HubStatTile(
                        "Grouped",
                        groups.size.toString(),
                        MaterialTheme.hubAccents.success,
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                    HubStatTile(
                        "Failed",
                        failureCount.toString(),
                        MaterialTheme.colorScheme.error,
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HubStatTile(
                        "Recovery events",
                        recoveryCount.toString(),
                        MaterialTheme.colorScheme.secondary,
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                    HubStatTile(
                        "Hours with failures",
                        flaggedHours.toString(),
                        if (flaggedHours == 0) MaterialTheme.hubAccents.success else MaterialTheme.hubAccents.warning,
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        HubReveal(3) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                filters.forEach { option ->
                    EventFilterChip(
                        label = option,
                        selected = option == filter,
                        onClick = {
                            filter = option
                            expandedAt = null
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        HubReveal(4) {
            HubGlassCard {
                Text(
                    if (hourStartAt == null) "ACTIVITY" else "ACTIVITY · " + formatIntervalClock(hourStartAt),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.4.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (expandedAt == null) "Tap an event to see the sequence behind it" else "Tap again to close the detail",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(8.dp))
                if (groups.isEmpty()) {
                    HubEmptyState("Nothing recorded", "Events appear here as the radio changes state.")
                } else {
                    groups.take(60).forEachIndexed { index, group ->
                        val open = expandedAt == group.first.timestamp
                        EventGroupCard(
                            group = group,
                            expanded = open,
                            onToggle = { expandedAt = if (open) null else group.first.timestamp },
                        )
                        if (open) {
                            IncidentDetailCard(group = group, entries = entries)
                        }
                        if (index < minOf(groups.size, 60) - 1) HubRowDivider(Modifier.padding(vertical = 7.dp))
                    }
                    if (groups.size > 60) {
                        Spacer(Modifier.height(8.dp))
                        Text("Showing the latest 60 grouped events.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        // Advice reads the whole log, not the filtered slice: a filter is a way of looking at the
        // events, not a claim about which ones matter.
        HubReveal(5) { EventGuidanceCard(context, entries, entries.count { it.type == "failed" }) }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HubQuietButton("Export report", icon = Icons.Rounded.OpenInNew, modifier = Modifier.weight(1f),
                onClick = { showExport = true })
            HubQuietButton("Clear history", modifier = Modifier.weight(1f), onClick = {
                MainActivity.store(context).edit().remove(AutoReconnectService.PREF_HISTORY).apply()
                entries = emptyList()
            })
        }
    }

    if (showExport) {
        ModalBottomSheet(onDismissRequest = { showExport = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
                Text("Reconnect report", fontSize = 22.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                Text("A JSON summary of observed traffic, fallback time and recovery timing.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                HubPrimaryButton(text = "Share as JSON", icon = Icons.Rounded.OpenInNew, onClick = {
                    val share = Intent(Intent.ACTION_SEND).setType("application/json")
                        .putExtra(Intent.EXTRA_TEXT, buildNetworkReport(context, entries))
                    context.startActivity(Intent.createChooser(share, "Export reconnect report"))
                    showExport = false
                })
            }
        }
    }
}

private fun collapseDuplicateEvents(entries: List<HistoryEntry>): List<EventGroup> {
    if (entries.isEmpty()) return emptyList()
    val chronological = entries.sortedBy { it.timestamp }
    val result = mutableListOf<EventGroup>()
    var current = chronological.first()
    var count = 1
    var last = current.timestamp

    for (entry in chronological.drop(1)) {
        val sameKind = entry.type == current.type &&
            entry.network == current.network &&
            normalizeEventDetail(entry.detail) == normalizeEventDetail(current.detail)
        val closeEnough = entry.timestamp - last <= 90_000L
        if (sameKind && closeEnough) {
            count++
            last = entry.timestamp
        } else {
            result += EventGroup(current, count, last)
            current = entry
            count = 1
            last = entry.timestamp
        }
    }
    result += EventGroup(current, count, last)
    return result.sortedByDescending { it.first.timestamp }
}

private fun normalizeEventDetail(detail: String): String =
    detail.replace(Regex("\\b\\d+(?:\\.\\d+)?s\\b"), "<time>")
        .replace(Regex("\\b\\d+\\s*min\\b"), "<time>")
        .replace(Regex("\\b\\d+\\s*attempts?\\b"), "<attempts>")
        .trim().lowercase(Locale.getDefault())

/** One hour of the incident timeline. */
private const val INCIDENT_BUCKET_MS = 60L * 60L * 1000L

/** How much observed radio time the health score needs before it reports a number. */
private const val HEALTH_WINDOW_MS = 24L * 60L * 60L * 1000L

/**
 * Groups the log into the 24 hourly buckets the incident graph draws.
 *
 * Failures are counted separately from every other event because they are the only kind that
 * warrant a second look; lumping a warning in with a failed recovery would make a quiet hour look
 * like a broken one.
 */
private fun incidentBuckets(entries: List<HistoryEntry>, now: Long): List<IncidentBucket> {
    val firstStart = ((now - 24L * INCIDENT_BUCKET_MS) / INCIDENT_BUCKET_MS) * INCIDENT_BUCKET_MS
    return (0 until 24).map { index ->
        val from = firstStart + index * INCIDENT_BUCKET_MS
        val rows = entries.filter { it.timestamp >= from && it.timestamp < from + INCIDENT_BUCKET_MS }
        IncidentBucket(
            startAt = from,
            network = rows.count { it.type == "network" },
            successes = rows.count { it.type == "success" },
            failures = rows.count { it.type == "failed" },
            sim = rows.count { it.type == "sim_failover" || it.type == "wifi_failover" },
            other = rows.count {
                it.type !in setOf("network", "success", "failed", "sim_failover", "wifi_failover")
            },
        )
    }
}

private fun matchesEventFilter(entry: HistoryEntry, filter: String): Boolean = when (filter) {
    "Success" -> entry.type == "success"
    "Failed" -> entry.type == "failed"
    "Network" -> entry.type == "network"
    "SIM" -> entry.type == "sim_failover" || entry.type == "wifi_failover"
    "Recovery" -> entry.type == "success" || entry.type == "failed" || entry.type == "attempt"
    else -> true
}

/** Scrollable filter chip, because six options do not fit a [HubSegmented] row. */
@Composable
private fun EventFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1) },
        shape = RoundedCornerShape(14.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun EventGroupCard(
    group: EventGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    showChevron: Boolean = true,
) {
    val entry = group.first
    val tint = eventTint(entry.type)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (showChevron) Modifier.clickable { onToggle() } else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.network, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text(eventTypeLabel(entry.type), color = tint, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 0.7.sp)
                if (group.count > 1) {
                    Spacer(Modifier.width(7.dp))
                    HubPill(group.count.toString() + "×", true, tint)
                }
            }
            Text(entry.detail.ifBlank { "Network state observed" }, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (group.count > 1) formatTimestamp(entry.timestamp) + " → " + formatTimestamp(group.lastTimestamp)
                else formatTimestamp(entry.timestamp),
                color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        if (showChevron) {
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Hide incident detail" else "Show incident detail",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun eventTint(type: String): Color = when (type) {
    "success" -> MaterialTheme.hubAccents.success
    "failed" -> MaterialTheme.colorScheme.error
    "warning" -> MaterialTheme.hubAccents.warning
    "network" -> MaterialTheme.colorScheme.tertiary
    "sim_failover", "wifi_failover" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun eventTypeLabel(type: String): String = when (type) {
    "success" -> "SUCCESS"
    "failed" -> "FAILED"
    "network" -> "NETWORK"
    "sim_failover" -> "SIM"
    "wifi_failover" -> "WIFI"
    "warning" -> "WARNING"
    "attempt" -> "ATTEMPT"
    else -> "SYSTEM"
}

/**
 * The recorded sequence behind one group: what the radio was doing before, what happened, what the
 * watcher did about it, and what came next.
 *
 * Every line is an entry that is actually in the log. There is no per-event signal, speed or thermal
 * snapshot to show, because the watcher never stored one — inventing those numbers here is the one
 * thing a diagnostic view must never do.
 */
@Composable
private fun IncidentDetailCard(group: EventGroup, entries: List<HistoryEntry>) {
    val chronological = remember(entries, group) { entries.sortedBy { it.timestamp } }
    val before = remember(chronological, group) { chronological.lastOrNull { it.timestamp < group.first.timestamp } }
    val after = remember(chronological, group) { chronological.firstOrNull { it.timestamp > group.lastTimestamp } }
    val tint = eventTint(group.first.type)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 2.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
    ) {
        Text(
            "INCIDENT DETAIL",
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.3.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SequenceRow(
            step = "BEFORE",
            title = before?.let { it.network + " · " + it.detail } ?: "Nothing older in the retained log",
            detail = before?.let { formatTimestamp(it.timestamp) }
                ?: "The log keeps the most recent events only.",
            accent = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SequenceRow(
            step = if (group.count > 1) "EVENT ×" + group.count else "EVENT",
            title = group.first.network + " · " + group.first.detail.ifBlank { "Network state observed" },
            detail = if (group.count > 1) {
                formatTimestamp(group.first.timestamp) + " → " + formatTimestamp(group.lastTimestamp) +
                    " · " + formatDuration((group.lastTimestamp - group.first.timestamp).coerceAtLeast(0L)) + " span"
            } else {
                formatTimestamp(group.first.timestamp)
            },
            accent = tint,
        )
        SequenceRow(
            step = "ACTION",
            title = eventAction(group.first),
            detail = "Read from the event's own type.",
            accent = MaterialTheme.colorScheme.primary,
        )
        SequenceRow(
            step = "AFTER",
            title = after?.let { it.network + " · " + it.detail } ?: "Nothing newer recorded yet",
            detail = after?.let { formatTimestamp(it.timestamp) } ?: "This is the most recent event in the log.",
            accent = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SequenceRow(step: String, title: String, detail: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Box(
            Modifier
                .width(2.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(step, color = accent, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun eventAction(entry: HistoryEntry): String = when (entry.type) {
    "attempt" -> "The watcher triggered a 5G re-selection."
    "success" -> "The modem confirmed 5G after the action."
    "failed" -> "The attempt did not end on 5G, so it counts against the retry budget."
    "sim_failover" -> "Mobile data was moved between subscriptions."
    "wifi_failover" -> "The Wi-Fi radio was raised or lowered."
    "warning" -> "A data limit was crossed, so a warning was posted."
    "network" -> "No action taken: this is the radio reporting its own state."
    else -> "Recorded for context; no switching action."
}

/**
 * Settings worth changing, given what the log actually shows.
 *
 * This replaced a card that recommended building features which now exist, so it was listing work
 * rather than advice. Every line here names a switch that already exists in Tune.
 */
@Composable
private fun EventGuidanceCard(context: Context, entries: List<HistoryEntry>, failureCount: Int) {
    val store = MainActivity.store(context)
    val sims = runCatching {
        context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().size
    }.getOrDefault(0)
    val pauseDuringCall = store.getBoolean(AutoReconnectService.PREF_PAUSE_DURING_CALL, false)
    val lteFailover = store.getBoolean(AutoReconnectService.PREF_LTE_FAILOVER_ENABLED, false)
    val wifiFailover = store.getBoolean(AutoReconnectService.PREF_WIFI_FAILOVER_ENABLED, false)
    val steps = mutableListOf<Pair<String, String>>()

    if (failureCount >= 2 && sims >= 2 && !lteFailover) {
        steps += "Move data off a 4G fallback" to
            "The log shows repeated recoveries that did not land on 5G, and a second SIM is available. " +
            "LTE failover moves data to that SIM while the primary keeps trying."
    }
    if (!pauseDuringCall && entries.any { it.detail.contains("call", ignoreCase = true) }) {
        steps += "Pause switching during calls" to
            "A call appeared in the log. Switching the radio or the data SIM mid-call drops the call on " +
            "some networks; the option waits a few seconds after the call ends instead."
    }
    if (!wifiFailover) {
        steps += "Raise Wi-Fi when recovery stalls" to
            "When the monitored radio stays on 4G or loses service, the Wi-Fi radio can be raised so the " +
            "phone joins a saved network. Android gives no way to pick which one."
    }
    if (steps.isEmpty()) {
        steps += "Nothing to change" to
            "The watcher is configured sensibly for what the log shows. Nothing here needs your attention."
    }

    HubGlassCard {
        Text(
            "WHAT TO DO NEXT",
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.4.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Based on this log, not on generic advice",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        steps.take(3).forEachIndexed { index, step ->
            if (index > 0) HubRowDivider(Modifier.padding(vertical = 9.dp))
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.first, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(step.second, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}

// ── page 3: data ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun DataPage(
    context: Context,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scroll = rememberScrollState()
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
    val heat = tick.let { readDeviceHeat(context) }
    val usageAccess = tick.let { PerAppDataUsage.hasUsageAccess(context) }
    var appUsage by remember { mutableStateOf<List<AppNetworkUsage>>(emptyList()) }
    var appUsageRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(appUsageRefresh, usageAccess) {
        appUsage = if (usageAccess) {
            withContext(Dispatchers.IO) { PerAppDataUsage.today(context) }
        } else {
            emptyList()
        }
    }
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
    val weeklyLte = daily.sumOf { it.lte }
    val weekly5G = daily.sumOf { it.fiveG }
    val todayTotal = totals.todayLte + totals.todayFiveG

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 14.dp, bottom = 22.dp),
    ) {
        HubReveal(0, Modifier.zIndex(1f)) {
            SwitcherHeader(
                kicker = "Usage",
                title = "Data usage",
                subtitle = "Mobile traffic, app activity, and battery temperature.",
                icon = Icons.Rounded.Analytics,
                scrollState = scroll,
                onOpenSettings = onOpenSettings,
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
                Spacer(Modifier.height(12.dp))
                HubQuietButton(
                    text = "Open history",
                    icon = Icons.Rounded.History,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenHistory,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HubReveal(2) { ResetTodayCard(context) { tick++ } }
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
                        progress = if (totals.todayPeakBps > 0L) {
                            (totals.todayPeakBps.toFloat() / NetworkUsageTracker.MAX_PLAUSIBLE_BPS.toFloat())
                                .coerceIn(0.05f, 1f)
                        } else {
                            0.05f
                        },
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
        HubReveal(4) {
            HubGlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("BATTERY TEMPERATURE", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            heat.batteryCelsius?.let { String.format(Locale.getDefault(), "Battery %.1f°C", it) } ?: "Battery temperature unavailable",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Thermal status · " + heat.thermalStatus,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "This is the battery sensor, not CPU temperature. Android does not reliably identify which app is heating the phone; high data activity below is a clue, not proof.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HubReveal(5) {
            HubGlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DATA BY APP", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Mobile data used today", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    HubQuietButton("Refresh") { appUsageRefresh++ }
                }
                Spacer(Modifier.height(8.dp))
                if (!usageAccess) {
                    Text(
                        "Allow Usage Access to see which apps use mobile data.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    HubQuietButton(
                        text = "Grant Usage Access",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { PerAppDataUsage.openUsageAccess(context) },
                    )
                } else if (appUsage.isEmpty()) {
                    Text(
                        "No per-app mobile traffic reported today.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                } else {
                    appUsage.forEachIndexed { index, item ->
                        if (index > 0) {
                            Spacer(Modifier.height(8.dp))
                            HubHairline()
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.appName, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(formatBytes(item.bytes), color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Android-reported mobile traffic only. Wi-Fi usage and some system traffic may be missing; figures can differ from carrier billing.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HubReveal(6) { LteWarningCard(warningEnabled, warningLimit, totals.todayLte) }
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
        HubReveal(6) { RecoveryAnalyticsCard(context) }
        Spacer(Modifier.height(12.dp))
        HubReveal(0) {
            HubGlassCard {
                Text(
                    "BILLING CYCLE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(8.dp))
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
        HubReveal(7) { ReconciliationCard(context) }
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
private fun UsageHistoryPage(context: MainActivity, onOpenSettings: () -> Unit) {
    val scroll = rememberScrollState()
    var tick by remember { mutableIntStateOf(0) }
    var rangeIndex by rememberSaveable { mutableIntStateOf(2) }
    var suspiciousOnly by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedBucket by rememberSaveable { mutableStateOf<Int?>(null) }
    var bulkMessage by remember { mutableStateOf<String?>(null) }
    val historyScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }

    val range = HISTORY_RANGES[rangeIndex.coerceIn(0, HISTORY_RANGES.lastIndex)]
    var all by remember { mutableStateOf<List<UsageInterval>>(emptyList()) }
    LaunchedEffect(rangeIndex, tick) {
        all = withContext(Dispatchers.IO) {
            NetworkUsageTracker.readIntervals(context, ((range.windowMs + DAY_MS - 1L) / DAY_MS).toInt().coerceIn(1, 14))
        }
    }

    val now = System.currentTimeMillis()
    val windowStart = now - range.windowMs
    // Bucketing is pure arithmetic over the loaded intervals, so it is safe to derive during
    // composition; the file read is the only part that needs the IO dispatcher.
    val buckets = remember(all, range, tick) { buildUsageBuckets(all, windowStart, range.bucketMs, now) }
    val flagged = all.count { it.suspicious }
    val bucketIntervals = selectedBucket?.let { buckets.getOrNull(it)?.exact }.orEmpty()
    val listed = (if (selectedBucket == null) all else bucketIntervals)
        .filter { !suspiciousOnly || it.suspicious }
        .asReversed()
    // Keep all non-composable derived values above the UI tree. This also avoids Compose
    // misreporting local collection calls after composable invocations.
    val rows = listed.take(180)
    val selected = selectedId?.let { id ->
        listed.firstOrNull { it.id == id } ?: all.firstOrNull { it.id == id }
    }
    // The range totals come from the intervals themselves rather than from the bars: a bar is aligned
    // to a bucket boundary, so the oldest bar can reach up to one bucket before the window opens and
    // summing the bars would quietly include that extra history in a figure labelled "in range".
    val inRange = all.filter { it.endAt >= windowStart && !it.fake }
    val rangeLte = inRange.filter { it.network != NetworkUsageTracker.FIVE_G_LABEL }.sumOf { it.bytes }
    val rangeFiveG = inRange.filter { it.network == NetworkUsageTracker.FIVE_G_LABEL }.sumOf { it.bytes }
    val busiest = buckets.maxByOrNull { it.total }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 14.dp, bottom = 22.dp),
    ) {
        HubReveal(0, Modifier.zIndex(1f)) {
            SwitcherHeader(
                kicker = "History",
                title = "Usage timeline",
                subtitle = "Minute-level mobile traffic with suspicious-spike review and manual cleanup.",
                icon = Icons.Rounded.History,
                scrollState = scroll,
                onOpenSettings = onOpenSettings,
            )
        }

        Spacer(Modifier.height(14.dp))
        HubReveal(1) {
            HubGlassCard {
                HubSegmented(
                    options = HISTORY_RANGES.map { it.label },
                    selectedIndex = rangeIndex.coerceIn(0, HISTORY_RANGES.lastIndex),
                    onSelect = {
                        rangeIndex = it
                        selectedId = null
                        selectedBucket = null
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Show suspicious spikes only",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Anything above " + formatSpeed(NetworkUsageTracker.SUSPECT_SPIKE_BPS) +
                                " is flagged for review; impossible bursts are excluded outright.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = suspiciousOnly,
                        onCheckedChange = {
                            suspiciousOnly = it
                            selectedId = null
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                HubQuietButton(
                    text = "Mark every spike as fake",
                    icon = Icons.Rounded.Warning,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        historyScope.launch {
                            val changed = withContext(Dispatchers.IO) {
                                NetworkUsageTracker.markAllSuspiciousFake(context)
                            }
                            bulkMessage = if (changed > 0) {
                                "Marked $changed spike${if (changed == 1) "" else "s"} fake and removed them from the totals."
                            } else {
                                "Nothing left to mark — every flagged spike is already fake."
                            }
                            tick++
                        }
                    },
                )
                bulkMessage?.let { note ->
                    Spacer(Modifier.height(8.dp))
                    Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HubReveal(2) {
            HubGlassCard {
                Text(
                    "TRAFFIC OVER TIME · " + range.label.uppercase(Locale.getDefault()),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(10.dp))
                UsageTimelineGraph(
                    buckets = buckets,
                    bucketMs = range.bucketMs,
                    now = now,
                    selectedIndex = selectedBucket,
                    onSelect = {
                        selectedBucket = it
                        selectedId = null
                    },
                    format = { bytes -> formatBytes(bytes) },
                )
                Spacer(Modifier.height(10.dp))
                // The 4G vs 5G comparison for the whole range: the one thing a stacked bar chart
                // cannot state outright.
                NetworkSplitBar(lte = rangeLte, fiveG = rangeFiveG)
                Spacer(Modifier.height(10.dp))
                AnalyticsRow("Total in range", formatBytes(rangeLte + rangeFiveG))
                AnalyticsRow(
                    "Busiest bucket",
                    busiest?.takeIf { it.total > 0L }
                        ?.let { formatBytes(it.total) + " · " + formatIntervalClock(it.startAt) }
                        ?: "—",
                )
                AnalyticsRow(
                    "Flagged intervals in range",
                    flagged.toString(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Each bar covers one " + formatBucketSpan(range.bucketMs) + " block of recorded intervals, " +
                        "stacked as 4G under 5G. Tap or drag a bar to open the exact intervals it was built " +
                        "from. Intervals marked fake are left out of the totals but stay in the list so they can " +
                        "be restored.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }

        selected?.let { item ->
            Spacer(Modifier.height(12.dp))
            HubReveal(3) {
                HubGlassCard(accent = if (item.fake) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (item.fake) "MARKED FAKE" else if (item.suspicious) "SUSPECTED SPIKE" else item.network,
                                    color = if (item.fake) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.4.sp,
                                )
                                Text(
                                    formatIntervalClock(item.startAt) + " → " + formatIntervalClock(item.endAt),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                )
                                Text(
                                    formatBytes(item.bytes) + " · peak " + formatSpeed(item.peakBps),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            }
                            Icon(
                                if (item.fake) Icons.Rounded.Refresh else if (item.suspicious) Icons.Rounded.Warning else Icons.Rounded.Timeline,
                                contentDescription = null,
                                tint = if (item.fake || item.suspicious) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        HubPrimaryButton(
                            text = if (item.fake) "Restore interval" else "Mark interval fake",
                            icon = if (item.fake) Icons.Rounded.Refresh else Icons.Rounded.Warning,
                        ) {
                            historyScope.launch {
                                withContext(Dispatchers.IO) {
                                    NetworkUsageTracker.toggleIntervalFake(context, item.id)
                                }
                                tick++
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HubSectionTitle(
            when {
                selectedBucket != null ->
                    "Exact intervals · " + formatIntervalClock(bucketIntervals.firstOrNull()?.startAt ?: windowStart)
                suspiciousOnly -> "Suspicious intervals"
                else -> "Exact intervals"
            },
        )
        if (selectedBucket != null) {
            Spacer(Modifier.height(6.dp))
            HubQuietButton(
                text = "Show the whole range again",
                icon = Icons.Rounded.Refresh,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    selectedBucket = null
                    selectedId = null
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        if (rows.isEmpty()) {
            HubGlassCard {
                HubEmptyState(
                    if (selectedBucket != null) "Nothing in this bucket" else "No intervals yet",
                    if (selectedBucket != null) {
                        "The selected bucket holds no recorded interval. Tap another bar."
                    } else {
                        "Keep the watcher running. Usage history is recorded in one-minute intervals."
                    },
                )
            }
        } else {
            rows.forEach { item ->
                val selectedRow = item.id == selectedId
                HubGlassCard(
                    accent = when {
                        item.fake -> MaterialTheme.colorScheme.error
                        item.suspicious -> MaterialTheme.colorScheme.error
                        item.network == NetworkUsageTracker.FIVE_G_LABEL -> MaterialTheme.hubAccents.success
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                    contentPadding = 12.dp,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selectedId = if (selectedRow) null else item.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    formatIntervalClock(item.startAt) + " – " + formatIntervalClock(item.endAt),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (item.suspicious) {
                                    Spacer(Modifier.width(7.dp))
                                    HubPill("SPIKE", active = false, accent = MaterialTheme.colorScheme.error)
                                }
                                if (item.fake) {
                                    Spacer(Modifier.width(7.dp))
                                    HubPill("FAKE", active = false, accent = MaterialTheme.colorScheme.error)
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                item.network + " · " + formatBytes(item.bytes) + " · peak " + formatSpeed(item.peakBps),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                            )
                        }
                        Icon(
                            if (selectedRow) Icons.Rounded.Warning else Icons.Rounded.Timeline,
                            contentDescription = null,
                            tint = if (item.suspicious) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (listed.size > rows.size) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Showing the most recent " + rows.size + " of " + listed.size + " intervals in this view.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "A suspicious spike is only a review flag. Marking an interval fake subtracts its bytes from the 4G/5G totals and recomputes today's speed peak. Android's mobile counters are aggregate observations, not carrier billing records.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
    }
}


/** One selectable window of the usage timeline: how far back it looks, and how wide each bar is. */
private data class UsageRange(val label: String, val windowMs: Long, val bucketMs: Long)

private const val DAY_MS = 24L * 60L * 60L * 1000L

/** The most buckets any timeline graph will draw, newest first. */
private const val MAX_USAGE_BUCKETS = 200

/** Below this much logged radio time the timeline factors cannot be scored honestly. */
private const val MIN_RADIO_WINDOW_MS = 10L * 60L * 1000L

private val HISTORY_RANGES = listOf(
    UsageRange("1 hour", 60L * 60L * 1000L, 5L * 60L * 1000L),
    UsageRange("6 hours", 6L * 60L * 60L * 1000L, 15L * 60L * 1000L),
    UsageRange("24 hours", 24L * 60L * 60L * 1000L, 60L * 60L * 1000L),
    UsageRange("7 days", 7L * DAY_MS, 6L * 60L * 60L * 1000L),
)

/**
 * Buckets the recorded intervals for the timeline graph.
 *
 * Every bucket keeps the intervals it was built from, which is what lets tapping a bar open the
 * exact minutes behind it rather than a summary. Fake intervals are left out of the byte totals —
 * that is what marking them fake is for — but they are still carried in [UsageBucket.exact], or the
 * row would disappear from the review list the moment it was marked and could never be restored.
 */
private fun buildUsageBuckets(
    intervals: List<UsageInterval>,
    windowStart: Long,
    bucketMs: Long,
    now: Long,
): List<UsageBucket> {
    // Anchored on the newest bucket rather than the oldest. The cap only bites if a future range ever
    // asks for more buckets than [MAX_USAGE_BUCKETS], and when it does the far end of the window is
    // what should go — truncating the recent end would hide the data the user is actually looking at.
    val requested = (((now - windowStart) / bucketMs).toInt() + 1).coerceAtLeast(1)
    val count = requested.coerceAtMost(MAX_USAGE_BUCKETS)
    val firstStart = (now / bucketMs) * bucketMs - (count - 1) * bucketMs
    val grouped = intervals
        .filter { it.endAt >= firstStart }
        .groupBy { (it.startAt / bucketMs) * bucketMs }
    return (0 until count).map { index ->
        val startAt = firstStart + index * bucketMs
        val rows = grouped[startAt].orEmpty()
        UsageBucket(
            startAt = startAt,
            lteBytes = rows.filter { !it.fake && it.network != NetworkUsageTracker.FIVE_G_LABEL }.sumOf { it.bytes },
            fiveGBytes = rows.filter { !it.fake && it.network == NetworkUsageTracker.FIVE_G_LABEL }.sumOf { it.bytes },
            suspicious = rows.any { it.suspicious },
            exact = rows.sortedBy { it.startAt },
        )
    }
}

/** "5-minute" / "6-hour", for the timeline's own explanation of what a bar covers. */
private fun formatBucketSpan(ms: Long): String {
    val minutes = ms / 60_000L
    return when {
        minutes >= 60L && minutes % 60L == 0L -> (minutes / 60L).toString() + "-hour"
        minutes >= 60L -> (minutes / 60L).toString() + "h " + (minutes % 60L) + "m"
        else -> minutes.toString() + "-minute"
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

/**
 * Resets today's numbers, one field at a time.
 *
 * A single "reset everything" button forced the user to throw away the good numbers along with the
 * bad one, so the scope is chosen first and only that bucket is cleared.
 */
@Composable
private fun ResetTodayCard(context: Context, onChanged: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val scopes = listOf(
        NetworkUsageTracker.ResetScope.ALL,
        NetworkUsageTracker.ResetScope.LTE,
        NetworkUsageTracker.ResetScope.FIVE_G,
        NetworkUsageTracker.ResetScope.PEAK,
    )
    val scope = scopes[index]
    val label = when (scope) {
        NetworkUsageTracker.ResetScope.ALL -> "everything"
        NetworkUsageTracker.ResetScope.LTE -> "the 4G figure"
        NetworkUsageTracker.ResetScope.FIVE_G -> "the 5G figure"
        NetworkUsageTracker.ResetScope.PEAK -> "the speed peak"
    }

    HubGlassCard(accent = MaterialTheme.colorScheme.tertiary) {
        Text("Reset today", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(
            "Clear only what is wrong. Resetting one field leaves the others alone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(12.dp))
        HubSegmented(
            options = listOf("All", "4G", "5G", "Peak"),
            selectedIndex = index,
            onSelect = { index = it },
        )
        Spacer(Modifier.height(12.dp))
        HubQuietButton(
            text = "Reset $label",
            icon = Icons.Rounded.Refresh,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                NetworkUsageTracker.resetToday(context, scope)
                onChanged()
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Clearing a radio's usage also drops that radio's recorded intervals for today.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
    }
}

/**
 * The device's own counter against the app's attribution, for this watcher session.
 *
 * This is the check that makes the 4G/5G split falsifiable rather than confident: if the attributed
 * bytes plus everything deliberately set aside ever exceed what the device moved, the split is
 * provably wrong, and the card says so instead of quietly presenting the numbers as fact.
 */
@Composable
private fun ReconciliationCard(context: Context) {
    // The baseline only changes when a new session starts, but the device total keeps moving, so the
    // residual is re-read on a slow tick rather than being frozen at first composition.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            tick++
        }
    }
    val balance = tick.let { NetworkUsageTracker.readReconciliation(context) }
    var correctMessage by remember { mutableStateOf<String?>(null) }
    val accent = when {
        balance == null -> MaterialTheme.colorScheme.tertiary
        balance.balanced -> MaterialTheme.hubAccents.success
        else -> MaterialTheme.colorScheme.error
    }
    HubGlassCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "COUNTER CHECK",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = accent,
                )
                Text(
                    when {
                        balance == null -> "Waiting for the first sample"
                        balance.balanced -> "The parts add up"
                        else -> "The parts exceed the device total"
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            HubStatusDot(color = accent, pulsing = balance == null)
        }
        if (balance != null) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Device total since " + formatTimestamp(balance.sinceAt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(formatBytes(balance.deviceTotal), color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Attributed 5G + 4G", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(formatBytes(balance.attributed), color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Set aside (rejected / unattributed)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(formatBytes(balance.rejected + balance.unattributed), color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            HubHairline()
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Not seen by the watcher", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(formatBytes(balance.unexplained), color = accent, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
            if (!balance.balanced) {
                Spacer(Modifier.height(10.dp))
                HubQuietButton(
                    text = "Auto-correct now",
                    icon = Icons.Rounded.Build,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { correctMessage = NetworkUsageTracker.autoCorrect(context) },
                )
            }
            correctMessage?.let { note ->
                Spacer(Modifier.height(8.dp))
                Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Traffic used while the watcher was off, or on Wi-Fi, is counted by the device but not by " +
                    "this app. A residual is therefore normal; a negative one would mean the split is wrong.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
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

// ── settings (pushed above the tabs, not a tab) ──────────────────────────────────────────────────

@Composable
private fun SettingsPage(
    context: MainActivity,
    themeMode: String,
    onThemeMode: (String) -> Unit,
    radio: RadioState?,
    shizuku: ShizukuHealth,
    onBack: () -> Unit,
) {
    val scroll = rememberScrollState()
    val store = MainActivity.store(context)
    var statusTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            statusTick++
        }
    }
    // Re-read on a slow tick so the warning clears when the user comes back from system settings.
    val notificationsEnabled = statusTick.let { context.notificationsEnabled() }
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
    var pause5gDuringFailover by rememberSaveable {
        mutableStateOf(store.getBoolean(AutoReconnectService.PREF_LTE_FAILOVER_PAUSE_RECOVERY, true))
    }
    var pauseDuringCall by rememberSaveable {
        mutableStateOf(store.getBoolean(AutoReconnectService.PREF_PAUSE_DURING_CALL, false))
    }
    var wifiFailover by rememberSaveable {
        mutableStateOf(store.getBoolean(AutoReconnectService.PREF_WIFI_FAILOVER_ENABLED, false))
    }

    fun refreshWatcher() {
        if (!store.getBoolean(AutoReconnectService.PREF_AUTO_ENABLED, false)) return
        // Never resume a watcher the user paused. ACTION_START clears the paused flag, so tweaking a
        // slider used to silently restart recovery behind the user's back; the setting now applies
        // on the next resume instead.
        if (store.getBoolean(AutoReconnectService.PREF_PAUSED, false)) return
        context.startWatcher()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 14.dp, bottom = 22.dp),
    ) {
        HubReveal(0, Modifier.zIndex(1f)) { SettingsHeader(scrollState = scroll, onBack = onBack) }

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
            HubGlassCard(accent = if (notificationsEnabled) null else MaterialTheme.hubAccents.warning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Status notifications", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(
                            if (notificationsEnabled) {
                                "The ongoing notification shows the live state, including an active recovery cooldown."
                            } else {
                                "Notifications are off, so the watcher's status and its cooldown alerts cannot appear. Enable them so a paused recovery is never silent."
                            },
                            color = if (notificationsEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.hubAccents.warning,
                            fontSize = 11.sp,
                        )
                    }
                    HubStatusDot(
                        color = if (notificationsEnabled) MaterialTheme.hubAccents.success else MaterialTheme.hubAccents.warning,
                        pulsing = !notificationsEnabled,
                    )
                }
                Spacer(Modifier.height(12.dp))
                HubQuietButton(
                    text = if (notificationsEnabled) "Notification settings" else "Enable notifications",
                    icon = Icons.Rounded.NotificationsActive,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { context.openNotificationSettings() },
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
                        when {
                            sims.size >= 2 ->
                                "Both SIMs detected. Failover can move mobile data between them, and the monitored SIM defaults to whichever one currently carries data."
                            sims.isEmpty() ->
                                "No active SIM detected, so failover stays disabled."
                            else ->
                                "Only one active SIM detected, so the backup-SIM failover cannot run. Insert or enable a second SIM to use it."
                        },
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
                        Spacer(Modifier.height(10.dp))
                        HubToggleRow(
                            title = "Pause 5G recovery while backup SIM is active",
                            subtitle = "Do not keep forcing 5G while data is on the backup SIM. This is useful when the backup SIM is 4G-only; the primary SIM is still watched for a natural 5G return.",
                            checked = pause5gDuringFailover,
                            onCheckedChange = { enabled ->
                                pause5gDuringFailover = enabled
                                store.edit().putBoolean(AutoReconnectService.PREF_LTE_FAILOVER_PAUSE_RECOVERY, enabled).apply()
                                refreshWatcher()
                            },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                HubHairline()
                Spacer(Modifier.height(10.dp))
                HubToggleRow(
                    title = "Pause switching during calls",
                    subtitle = "Do not change the radio or data SIM during a call. After the call ends, wait about 4 seconds and retry if LTE is still active.",
                    checked = pauseDuringCall,
                    onCheckedChange = { enabled ->
                        pauseDuringCall = enabled
                        store.edit().putBoolean(AutoReconnectService.PREF_PAUSE_DURING_CALL, enabled).apply()
                        refreshWatcher()
                    },
                )
                Spacer(Modifier.height(10.dp))
                HubHairline()
                Spacer(Modifier.height(10.dp))
                HubToggleRow(
                    title = "Raise Wi-Fi when cellular recovery stalls",
                    subtitle = "If the monitored radio stays on 4G or loses service, switch the Wi-Fi radio on so the phone can join a saved network. Android gives no way to choose the network, and Wi-Fi is turned back off once 5G returns.",
                    checked = wifiFailover,
                    onCheckedChange = { enabled ->
                        wifiFailover = enabled
                        store.edit().putBoolean(AutoReconnectService.PREF_WIFI_FAILOVER_ENABLED, enabled).apply()
                        refreshWatcher()
                    },
                )
                Spacer(Modifier.height(10.dp))
                HubQuietButton(
                    text = "Move data back to the monitored SIM now",
                    icon = Icons.Rounded.Refresh,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { context.revertFailovers() },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Failover only runs when two active SIMs are present. If the monitored SIM never gets 5G back, data is handed back automatically after a few minutes regardless, and this button does it immediately.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
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

private data class DeviceHeatSnapshot(
    val batteryCelsius: Float?,
    val thermalStatus: String,
)

private fun readDeviceHeat(context: Context): DeviceHeatSnapshot {
    val batteryIntent = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    val rawTemperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    val batteryCelsius = if (rawTemperature > 0) rawTemperature / 10f else null
    val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val power = context.getSystemService(PowerManager::class.java)
        when (runCatching { power.currentThermalStatus }.getOrNull()) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Unavailable"
        }
    } else {
        "Unavailable on this Android version"
    }
    return DeviceHeatSnapshot(batteryCelsius, thermalStatus)
}

internal fun readNetworkHistory(context: Context): List<HistoryEntry> {
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

internal fun buildNetworkReport(context: Context, entries: List<HistoryEntry>): String {
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

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824f)
    bytes >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576f)
    bytes >= 1_024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1_024f)
    else -> bytes.toString() + " B"
}

internal fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000L -> String.format(Locale.getDefault(), "%.1f MB/s", bytesPerSecond / 1_000_000f)
    bytesPerSecond >= 1_000L -> String.format(Locale.getDefault(), "%.0f KB/s", bytesPerSecond / 1_000f)
    else -> bytesPerSecond.toString() + " B/s"
}

internal fun formatTimestamp(timestamp: Long): String =
    if (timestamp <= 0L) "—" else SimpleDateFormat("dd MMM · HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

internal fun formatIntervalClock(timestamp: Long): String =
    SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(Date(timestamp))

/** Compact "2h 14m" / "7m 20s" span, shared by the pages and the analytics. */
internal fun formatDuration(ms: Long): String {
    val seconds = ms / 1000L
    return when {
        seconds >= 3600L -> (seconds / 3600).toString() + "h " + ((seconds % 3600) / 60).toString() + "m"
        seconds >= 60L -> (seconds / 60).toString() + "m " + (seconds % 60).toString() + "s"
        else -> seconds.toString() + "s"
    }
}

// ── live dashboard: telemetry graph, health score, change explanation ───────────────────────

/**
 * The live graph card: one metric, one window, and an explicit statement of who sampled what.
 *
 * Latency and signal are collected by this screen only, because a continuous network round trip and
 * a phone-state read are not costs the background watcher should carry. Speed and traffic come from
 * the watcher. All four land in the same ring buffer, so the graph never mixes two resolutions and
 * never implies a sample exists where none was taken.
 */
@Composable
private fun LiveGraphCard(context: MainActivity, accent: Color) {
    var metricIndex by rememberSaveable { mutableIntStateOf(0) }
    var rangeIndex by rememberSaveable { mutableIntStateOf(1) }
    var tick by remember { mutableIntStateOf(0) }
    val metrics = LiveTelemetry.Metric.entries
    val metric = metrics[metricIndex.coerceIn(0, metrics.lastIndex)]
    val ranges = listOf(1, 5, 15, 30)
    val rangeMinutes = ranges[rangeIndex.coerceIn(0, ranges.lastIndex)]

    LaunchedEffect(Unit) {
        while (true) {
            val at = System.currentTimeMillis()
            val latency = withContext(Dispatchers.IO) { LatencyProbe.measure() }
            // -1 means the handshake never completed, which is not a latency of zero and must not be
            // plotted as one.
            if (latency > 0L) {
                LiveTelemetry.record(LiveTelemetry.Metric.LATENCY, at, latency.toFloat(), context.liveLabel())
            }
            val level = context.signalLevel()
            if (level in 0..4) {
                LiveTelemetry.record(LiveTelemetry.Metric.SIGNAL, at, level.toFloat(), context.liveLabel())
            }
            tick++
            delay(4_000)
        }
    }

    // Reading the tick is what makes the graph re-read the ring buffer. Without it the canvas would
    // keep whatever list it was last handed.
    val sampleTick = tick
    val now = System.currentTimeMillis()
    val windowStart = now - rangeMinutes * 60_000L

    Column {
        HubSectionTitle("Live telemetry")
        Spacer(Modifier.height(8.dp))
        HubGlassCard(accent = accent) {
            HubSegmented(
                options = listOf("Speed", "Data", "Latency", "Signal"),
                selectedIndex = metricIndex.coerceIn(0, metrics.lastIndex),
                onSelect = { metricIndex = it },
            )
            Spacer(Modifier.height(8.dp))
            HubSegmented(
                options = ranges.map { it.toString() + " min" },
                selectedIndex = rangeIndex.coerceIn(0, ranges.lastIndex),
                onSelect = { rangeIndex = it },
            )
            Spacer(Modifier.height(14.dp))
            LiveMetricGraph(
                samples = sampleTick.let { LiveTelemetry.samples(metric, windowStart) },
                windowStart = windowStart,
                bucketMs = rangeMinutes * 60_000L / 30L,
                now = now,
                aggregate = when (metric) {
                    LiveTelemetry.Metric.DATA -> BucketAggregate.SUM
                    LiveTelemetry.Metric.SPEED -> BucketAggregate.MAX
                    else -> BucketAggregate.AVERAGE
                },
                format = { value ->
                    when (metric) {
                        LiveTelemetry.Metric.SPEED -> formatSpeed(value.toLong())
                        LiveTelemetry.Metric.DATA -> formatBytes(value.toLong())
                        LiveTelemetry.Metric.LATENCY -> value.roundToInt().toString() + " ms"
                        LiveTelemetry.Metric.SIGNAL -> signalDescription(value.roundToInt())
                    }
                },
                axisCeiling = when (metric) {
                    LiveTelemetry.Metric.SIGNAL -> 4f
                    LiveTelemetry.Metric.LATENCY -> 2_000f
                    else -> null
                },
                accent = accent,
                emptyHint = when (metric) {
                    LiveTelemetry.Metric.SPEED, LiveTelemetry.Metric.DATA ->
                        "Traffic samples come from the watcher — start it to fill this window"
                    else -> "Latency and signal are sampled only while this screen is open"
                },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                when (metric) {
                    LiveTelemetry.Metric.SPEED ->
                        "Each bucket keeps the fastest sample in its window, so a brief peak is not averaged away."
                    LiveTelemetry.Metric.DATA ->
                        "Each bucket sums the byte deltas the watcher observed. Traffic on Wi-Fi is not counted."
                    LiveTelemetry.Metric.LATENCY ->
                        "A TCP handshake to 1.1.1.1:443, measured while this screen is open. Android exposes no " +
                            "ICMP ping to an unprivileged app, so this is a round trip, not a ping."
                    LiveTelemetry.Metric.SIGNAL ->
                        "Android's 0–4 signal level, read while this screen is open. It is a coarse bucket, not a dBm reading."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * The explainable health score.
 *
 * The point of showing the factors is that the number is falsifiable: a user can see which factor
 * cost them the points and whether that factor even had evidence behind it.
 */
@Composable
private fun HealthCard(context: MainActivity) {
    val store = remember { MainActivity.store(context) }
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var intervals by remember { mutableStateOf<List<UsageInterval>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            // The interval history is a file, so it is read off the main thread. The score is only
            // recomputed when this load lands, not on every frame.
            val loaded = withContext(Dispatchers.IO) {
                readNetworkHistory(context) to NetworkUsageTracker.readIntervals(context, 1)
            }
            entries = loaded.first
            intervals = loaded.second
            tick++
            delay(15_000)
        }
    }

    val now = System.currentTimeMillis()
    val health = tick.let { networkHealth(entries, store, intervals, now - HEALTH_WINDOW_MS, now) }
    val gradeAccent = when {
        health == null -> MaterialTheme.colorScheme.tertiary
        health.score >= 85 -> MaterialTheme.hubAccents.success
        health.score >= 70 -> MaterialTheme.colorScheme.primary
        health.score >= 50 -> MaterialTheme.hubAccents.warning
        else -> MaterialTheme.colorScheme.error
    }

    Column {
        HubSectionTitle("Network health")
        Spacer(Modifier.height(8.dp))
        HubGlassCard(accent = gradeAccent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HubProgressRing(
                    progress = (health?.score ?: 0) / 100f,
                    modifier = Modifier.size(66.dp),
                    accent = gradeAccent,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val observed = health?.observedMs ?: 0L
                    Text(
                        // A score can exist without a radio timeline (the recovery counters alone can
                        // clear the evidence threshold), and "LAST 0s" would be nonsense there.
                        if (observed >= MIN_RADIO_WINDOW_MS) {
                            "SCORE · LAST " + formatDuration(observed)
                        } else {
                            "SCORE · RADIO TIMELINE STILL SHORT"
                        },
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.3.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        (health?.score?.toString() ?: "—") + " · " + (health?.grade ?: "Collecting"),
                        color = gradeAccent,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        health?.summary
                            ?: "The score needs at least ten minutes of observed radio time, so the watcher has to have been running.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
            health?.factors?.forEach { factor ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        factor.label,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        factor.points.toString(),
                        color = when (factor.level) {
                            HealthLevel.GOOD -> MaterialTheme.hubAccents.success
                            HealthLevel.FAIR -> MaterialTheme.hubAccents.warning
                            HealthLevel.POOR -> MaterialTheme.colorScheme.error
                            HealthLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
                Spacer(Modifier.height(5.dp))
                HubMeter(fraction = factor.points / 100f, accent = gradeAccent, height = 5.dp)
                Spacer(Modifier.height(5.dp))
                Text(factor.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
            if ((health?.observedMs ?: 0L) < MIN_RADIO_WINDOW_MS) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "5G uptime, LTE fallbacks and stability need at least " + formatDuration(MIN_RADIO_WINDOW_MS) +
                        " of logged radio time before they can be scored, so they are left out rather than " +
                        "guessed. The factors above come from the recovery counters and the interval history.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Weighted from 5G uptime, LTE fallbacks, stability, recovery success and speed, and flagged " +
                    "data. A factor with no evidence is dropped and the rest are re-weighted, so a fresh " +
                    "install is not scored as broken.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * Why the radio last changed, with the evidence and the confidence stated.
 *
 * The card hides itself entirely when there is no transition in the retained log, rather than
 * showing an empty shell with a promise.
 */
@Composable
private fun WhyChangedCard(context: MainActivity) {
    val store = remember { MainActivity.store(context) }
    var entries by remember { mutableStateOf(readNetworkHistory(context)) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            entries = readNetworkHistory(context)
            tick++
        }
    }

    val now = System.currentTimeMillis()
    val cause = tick.let {
        explainLastChange(entries, store, context.signalLevel(), now)
    } ?: return

    val recorded = cause.confidence == "Recorded"
    val accent = if (recorded) MaterialTheme.hubAccents.success else MaterialTheme.colorScheme.secondary

    Column {
        HubSectionTitle("Why did it change?")
        Spacer(Modifier.height(8.dp))
        HubGlassCard(accent = accent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "LAST CHANGE",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.3.sp,
                        color = accent,
                    )
                    Text(
                        cause.headline,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
                HubPill(
                    label = cause.confidence.uppercase(Locale.getDefault()),
                    active = recorded,
                    accent = accent,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                cause.explanation,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            cause.evidence.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        item.value,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Android does not report the modem's reason for a re-selection. Anything marked LIKELY is " +
                    "inferred from the evidence above; anything marked RECORDED is a fact from the watcher's " +
                    "own log.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * Recovery analytics: how often recovery works, how fast, and how bad the worst run was.
 *
 * The mean alone hides both ends of the distribution, which is why the fastest, the slowest and the
 * longest failure streak are all reported next to it.
 */
@Composable
private fun RecoveryAnalyticsCard(context: Context) {
    val store = remember { MainActivity.store(context) }
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            entries = withContext(Dispatchers.IO) { readNetworkHistory(context) }
            tick++
            delay(15_000)
        }
    }

    val now = System.currentTimeMillis()
    val stats = tick.let { recoveryStats(entries, store, now - HEALTH_WINDOW_MS, now) }
    val successAccent = when {
        stats.successRatePct < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        stats.successRatePct >= 80 -> MaterialTheme.hubAccents.success
        stats.successRatePct >= 50 -> MaterialTheme.hubAccents.warning
        else -> MaterialTheme.colorScheme.error
    }

    Column {
        HubSectionTitle("Recovery analytics")
        Spacer(Modifier.height(8.dp))
        HubGlassCard {
            Text(
                if (stats.observedMs >= MIN_RADIO_WINDOW_MS) {
                    "LAST " + formatDuration(stats.observedMs) + " OF RETAINED LOG"
                } else {
                    "RETAINED LOG · RADIO TIMELINE STILL SHORT"
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HubStatTile(
                    "Success rate",
                    if (stats.successRatePct < 0) "—" else stats.successRatePct.toString() + "%",
                    successAccent,
                    Modifier.weight(1f),
                )
                HubStatTile(
                    "Average",
                    if (stats.averageMs == 0L) "—" else formatDuration(stats.averageMs),
                    MaterialTheme.colorScheme.secondary,
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HubStatTile(
                    "Fastest",
                    if (stats.fastestMs == 0L) "—" else formatDuration(stats.fastestMs),
                    MaterialTheme.hubAccents.success,
                    Modifier.weight(1f),
                )
                HubStatTile(
                    "Slowest",
                    if (stats.slowestMs == 0L) "—" else formatDuration(stats.slowestMs),
                    MaterialTheme.hubAccents.warning,
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            HubHairline()
            AnalyticsRow("Attempts", stats.attempts.toString())
            AnalyticsRow("Landed on 5G", stats.successes.toString())
            AnalyticsRow("Failed attempts", stats.failures.toString())
            AnalyticsRow(
                "Current failure streak",
                if (stats.failureStreak == 0) "none" else stats.failureStreak.toString(),
            )
            AnalyticsRow(
                "Longest failure streak",
                if (stats.longestFailureStreak == 0) "none" else stats.longestFailureStreak.toString(),
            )
            AnalyticsRow("LTE fallbacks", stats.fallbacks.toString())
            AnalyticsRow("Time on LTE", formatDuration(stats.fallbackMs))
            AnalyticsRow(
                "Longest single fallback",
                if (stats.longestFallbackMs == 0L) "—" else formatDuration(stats.longestFallbackMs),
            )
            AnalyticsRow("Time on 5G", formatDuration(stats.fiveGMs))
            AnalyticsRow(
                "5G uptime",
                if (stats.fiveGUptimePct < 0) "—" else stats.fiveGUptimePct.toString() + "%",
            )
            AnalyticsRow(
                "Longest 5G session",
                if (stats.longestFiveGMs == 0L) "—" else formatDuration(stats.longestFiveGMs),
            )
            AnalyticsRow("Radio changes per hour", String.format(Locale.US, "%.1f", stats.churnPerHour))
            if (stats.trend.any { it.successes > 0 || it.failures > 0 || it.fallbacks > 0 }) {
                Spacer(Modifier.height(12.dp))
                HubHairline()
                Spacer(Modifier.height(10.dp))
                Text(
                    "LAST 7 DAYS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                stats.trend.forEach { day ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            day.label,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            day.fallbacks.toString() + " fallbacks · " + day.successes + " ok · " + day.failures + " failed",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Counts cover the retained event log, so they start from the oldest event this app still " +
                    "holds rather than from the beginning of time. Android keeps no per-attempt history of " +
                    "its own.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun AnalyticsRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

