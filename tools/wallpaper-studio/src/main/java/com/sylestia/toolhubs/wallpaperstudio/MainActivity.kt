@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sylestia.toolhubs.wallpaperstudio

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast
import android.provider.OpenableColumns
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.ToolHubsTheme
import com.sylestia.toolhubs.ui.HubBottomNav
import com.sylestia.toolhubs.ui.HubEmptyState
import com.sylestia.toolhubs.ui.HubGlassCard
import com.sylestia.toolhubs.ui.HubHeroHeader
import com.sylestia.toolhubs.ui.HubNavItem
import com.sylestia.toolhubs.ui.HubPill
import com.sylestia.toolhubs.ui.HubQuietButton
import com.sylestia.toolhubs.ui.HubSectionTitle
import com.sylestia.toolhubs.ui.HubSegmented
import java.text.DateFormat
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WallpaperStudioTheme {
                WallpaperStudioApp()
            }
        }
        applyShortcutIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyShortcutIntent(intent)
    }

    private fun applyShortcutIntent(intent: Intent?) {
        if (intent?.action != ACTION_APPLY_SCENE) return
        val sceneId = intent.getStringExtra(WallpaperScheduler.EXTRA_SCENARIO_ID) ?: return
        thread(name = "wallpaper-shortcut") {
            val applied = WallpaperEngine.applyScenario(applicationContext, sceneId)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (applied) "Wallpaper applied" else "Could not apply this scene",
                    Toast.LENGTH_SHORT,
                ).show()
                setIntent(Intent(this, MainActivity::class.java))
                recreate()
            }
        }
    }

    companion object {
        const val ACTION_APPLY_SCENE = "com.sylestia.toolhubs.wallpaperstudio.APPLY_SCENE"
    }
}

@Composable
private fun WallpaperStudioTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    ToolHubsTheme(darkTheme = dark) {
        val base = MaterialTheme.colorScheme
        val oneUi = if (dark) {
            base.copy(
                primary = Color(0xFF8AB4FF),
                onPrimary = Color(0xFF082B5C),
                secondary = Color(0xFFB5C7E5),
                tertiary = Color(0xFFB6B8C0),
                background = Color(0xFF101114),
                onBackground = Color(0xFFF1F2F4),
                surface = Color(0xFF181A1F),
                onSurface = Color(0xFFF1F2F4),
                surfaceVariant = Color(0xFF262930),
                onSurfaceVariant = Color(0xFFB6BAC3),
                surfaceContainer = Color(0xFF202329),
                surfaceContainerHigh = Color(0xFF24272D),
                surfaceContainerHighest = Color(0xFF2B2F36),
                outline = Color(0xFF737984),
                outlineVariant = Color(0xFF343941),
            )
        } else {
            base.copy(
                primary = Color(0xFF1769E0),
                onPrimary = Color.White,
                secondary = Color(0xFF5C6675),
                tertiary = Color(0xFF89919C),
                background = Color(0xFFF5F6F8),
                onBackground = Color(0xFF17191D),
                surface = Color.White,
                onSurface = Color(0xFF17191D),
                surfaceVariant = Color(0xFFEDF0F4),
                onSurfaceVariant = Color(0xFF626A75),
                surfaceContainer = Color(0xFFF0F2F5),
                surfaceContainerHigh = Color.White,
                surfaceContainerHighest = Color(0xFFE9EDF2),
                outline = Color(0xFF89919C),
                outlineVariant = Color(0xFFE1E5EA),
            )
        }
        MaterialTheme(colorScheme = oneUi, content = content)
    }
}

private val WallpaperTabs = listOf(
    HubNavItem(Icons.Rounded.Wallpaper, "Home"),
    HubNavItem(Icons.Rounded.Tune, "Scenes"),
    HubNavItem(Icons.Rounded.Image, "Library"),
    HubNavItem(Icons.Rounded.Settings, "Settings"),
)

@Composable
private fun WallpaperStudioApp() {
    val context = LocalContext.current
    val store = remember { WallpaperStore(context) }
    val scheduler = remember { WallpaperScheduler(context) }

    var tab by remember { mutableIntStateOf(0) }
    var images by remember { mutableStateOf(store.images()) }
    var scenarios by remember { mutableStateOf(store.scenarios()) }
    var editing by remember { mutableStateOf<WallpaperScenario?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deletingImage by remember { mutableStateOf<WallpaperImage?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun requestEventNotificationPermission(scene: WallpaperScenario) {
        if (
            scene.enabled && scene.trigger != WallpaperTrigger.TIME &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        scheduler.refresh()
        WallpaperMonitoringService.sync(context, scenarios)
        WallpaperShortcuts.refresh(context, scenarios)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(24),
    ) { selected ->
        if (selected.isNotEmpty()) {
            val imported = selected.map { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val displayName = context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                }
                val label = (displayName ?: uri.lastPathSegment)
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: "Wallpaper ${store.images().size + 1}"
                WallpaperImage(UUID.randomUUID().toString(), uri.toString(), label)
            }
            store.addImages(imported)
            images = store.images()
            statusMessage = "${imported.size} image(s) added to your library."
        }
    }

    fun refresh() {
        images = store.images()
        scenarios = store.scenarios()
        scheduler.refresh()
        WallpaperMonitoringService.sync(context, scenarios)
        WallpaperShortcuts.refresh(context, scenarios)
    }

    fun applyNow(scenario: WallpaperScenario) {
        val applied = WallpaperEngine.applyScenario(context, scenario.id)
        statusMessage = if (applied) {
            "Applied ${scenario.name}."
        } else {
            store.lastError() ?: "Android could not apply this wallpaper."
        }
        refresh()
    }

    fun saveScene(scene: WallpaperScenario) {
        requestEventNotificationPermission(scene)
        store.saveScenario(scene)
        refresh()
        statusMessage = "${scene.name} is ready."
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 18.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "TOOL HUBS  /  PERSONALISATION",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.7.sp,
                    )
                    Text(
                        "Wallpaper Studio",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.7).sp,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        Icons.Rounded.Wallpaper,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> OverviewScreen(
                        images = images,
                        scenarios = scenarios,
                        store = store,
                        statusMessage = statusMessage,
                        onDismissStatus = { statusMessage = null },
                        onOpenScenes = { tab = 1 },
                        onOpenLibrary = { tab = 2 },
                        onAddPhotos = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onApply = ::applyNow,
                    )
                    1 -> ScenesScreen(
                        images = images,
                        scenarios = scenarios,
                        onAdd = {
                            editing = null
                            showEditor = true
                        },
                        onEdit = {
                            editing = it
                            showEditor = true
                        },
                        onToggle = { scene, enabled ->
                            requestEventNotificationPermission(scene.copy(enabled = enabled))
                            store.setScenarioEnabled(scene.id, enabled)
                            refresh()
                        },
                        onApply = ::applyNow,
                        onDelete = {
                            scheduler.cancel(it.id)
                            store.removeScenario(it.id)
                            refresh()
                            statusMessage = "Scene removed."
                        },
                    )
                    2 -> LibraryScreen(
                        images = images,
                        scenarios = scenarios,
                        onAdd = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onDelete = { deletingImage = it },
                    )
                    else -> SettingsScreen(
                        scenarioCount = scenarios.count { it.enabled },
                        imageCount = images.size,
                        lastError = store.lastError(),
                        onRefresh = {
                            refresh()
                            statusMessage = "Schedules refreshed."
                        },
                    )
                }
            }

            HubBottomNav(
                items = WallpaperTabs,
                selectedIndex = tab,
                onSelect = { tab = it },
            )
        }
    }

    if (showEditor) {
        ScenarioEditorDialog(
            initial = editing,
            images = images,
            onDismiss = { showEditor = false },
            onAddPhotos = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onSave = {
                saveScene(it)
                showEditor = false
            },
        )
    }

    deletingImage?.let { image ->
        val inUse = scenarios.any { it.imageId == image.id }
        AlertDialog(
            onDismissRequest = { deletingImage = null },
            title = { Text("Remove image?") },
            text = {
                Text(
                    if (inUse) "This image is used by a scene. Remove those scenes first."
                    else "Remove ${image.name} from your library? Your original photo will not be deleted.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !inUse,
                    onClick = {
                        store.saveImages(images.filterNot { it.id == image.id })
                        runCatching {
                            context.contentResolver.releasePersistableUriPermission(
                                Uri.parse(image.uri),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                        deletingImage = null
                        refresh()
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deletingImage = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StudioPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(17.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(9.dp))
            }
            Text(text, color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun OverviewScreen(
    images: List<WallpaperImage>,
    scenarios: List<WallpaperScenario>,
    store: WallpaperStore,
    statusMessage: String?,
    onDismissStatus: () -> Unit,
    onOpenScenes: () -> Unit,
    onOpenLibrary: () -> Unit,
    onAddPhotos: () -> Unit,
    onApply: (WallpaperScenario) -> Unit,
) {
    val lastScene = scenarios.firstOrNull { it.id == store.lastAppliedScenarioId() }
    val lastImage = images.firstOrNull { it.id == lastScene?.imageId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = 12.dp,
            bottom = 18.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            HubHeroHeader(
                kicker = "YOUR PHONE, YOUR ATMOSPHERE",
                title = "A different scene for every moment.",
                subtitle = "Let your wallpaper follow the way you use your phone.",
            )
        }
        item {
            HubGlassCard(contentPadding = 0.dp) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(218.dp)
                        .clip(RoundedCornerShape(24.dp)),
                ) {
                    if (lastImage != null) {
                        WallpaperImageView(lastImage, Modifier.fillMaxSize())
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.76f),
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.74f),
                                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f),
                                        ),
                                    ),
                                ),
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomStart)
                            .background(Color.Black.copy(alpha = 0.50f))
                            .padding(18.dp),
                    ) {
                        Column {
                            Text(
                                if (lastScene == null) "YOUR NEXT FAVOURITE VIEW" else "CURRENT SCENE",
                                color = Color.White.copy(alpha = 0.76f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.5.sp,
                            )
                            Text(
                                lastScene?.name ?: "Make your first scene",
                                color = Color.White,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (lastScene == null) "Choose a photo, then decide when it appears."
                                else "Applied ${DateFormat.getTimeInstance(DateFormat.SHORT).format(store.lastAppliedAt())}",
                                color = Color.White.copy(alpha = 0.82f),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
        if (statusMessage != null) {
            item {
                HubGlassCard(contentPadding = 13.dp, accent = MaterialTheme.colorScheme.primary) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Text(statusMessage, Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = onDismissStatus) { Text("OK") }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OverviewMetric("SCENES", scenarios.size.toString(), Modifier.weight(1f))
                OverviewMetric("ACTIVE", scenarios.count { it.enabled }.toString(), Modifier.weight(1f))
                OverviewMetric("PHOTOS", images.size.toString(), Modifier.weight(1f))
            }
        }
        item {
            HubSectionTitle("QUICK ACTIONS")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HubQuietButton(
                    text = "Add photos",
                    icon = Icons.Rounded.Add,
                    modifier = Modifier.weight(1f),
                    onClick = onAddPhotos,
                )
                HubQuietButton(
                    text = "Manage scenes",
                            modifier = Modifier.weight(1f),
                    onClick = onOpenScenes,
                )
            }
        }
        item {
            HubSectionTitle("AUTOMATION")
            HubGlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Fingerprint, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Unlock-aware switching", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Change scenes after the lock screen is dismissed.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    HubPill("READY", true)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Android reports that the phone is unlocked, not which biometric unlocked it. A separate Samsung fingerprint routine may be possible through Routines+.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        item {
            HubSectionTitle("UP NEXT")
            if (scenarios.isEmpty()) {
                HubGlassCard {
                    HubEmptyState(
                        title = "Your scenes live here",
                        message = "Create a scene for unlock, charging, Wi-Fi or a time of day.",
                        icon = Icons.Rounded.Schedule,
                    )
                    StudioPrimaryButton(
                        text = "Create your first scene",
                        icon = Icons.Rounded.Add,
                        onClick = onOpenScenes,
                    )
                }
            } else {
                val next = scenarios.filter { it.enabled }.take(3)
                if (next.isEmpty()) {
                    HubGlassCard {
                        Text("All scenes are paused.", color = MaterialTheme.colorScheme.onSurface)
                        Text("Turn one back on from Scenes when you're ready.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                } else {
                    next.forEach { scene ->
                        CompactSceneRow(scene, images.firstOrNull { it.id == scene.imageId }) {
                            onApply(scene)
                        }
                    }
                }
            }
        }
        item {
            HubQuietButton(
                text = "Open wallpaper library",
                    modifier = Modifier.fillMaxWidth(),
                onClick = onOpenLibrary,
            )
        }
    }
}

@Composable
private fun OverviewMetric(label: String, value: String, modifier: Modifier = Modifier) {
    HubGlassCard(modifier = modifier, contentPadding = 13.dp) {
        Text(value, color = MaterialTheme.colorScheme.primary, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

@Composable
private fun ScenesScreen(
    images: List<WallpaperImage>,
    scenarios: List<WallpaperScenario>,
    onAdd: () -> Unit,
    onEdit: (WallpaperScenario) -> Unit,
    onToggle: (WallpaperScenario, Boolean) -> Unit,
    onApply: (WallpaperScenario) -> Unit,
    onDelete: (WallpaperScenario) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        HubHeroHeader(
            kicker = "AUTOMATIONS",
            title = "Scenes",
            subtitle = "Build the moments that change your wallpaper.",
            icon = Icons.Rounded.Tune,
        )
        HubPrimaryButton(
            text = "New scene",
            icon = Icons.Rounded.Add,
            onClick = onAdd,
        )
        HubSectionTitle("YOUR SCENES")
        if (scenarios.isEmpty()) {
            HubGlassCard {
                HubEmptyState(
                    title = "Nothing automated yet",
                    message = "Start with an unlock scene, a charging scene or a nightly wallpaper.",
                    icon = Icons.Rounded.Schedule,
                )
                HubQuietButton("Create a scene", icon = Icons.Rounded.Add, onClick = onAdd)
            }
        } else {
            scenarios.sortedWith(compareByDescending<WallpaperScenario> { it.enabled }.thenByDescending { it.priority })
                .forEach { scene ->
                    SceneCard(
                        scene = scene,
                        image = images.firstOrNull { it.id == scene.imageId },
                        onToggle = { onToggle(scene, it) },
                        onApply = { onApply(scene) },
                        onEdit = { onEdit(scene) },
                        onDelete = { onDelete(scene) },
                    )
                }
        }
        HubGlassCard {
            Text("SCENE PRIORITY", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                "When more than one scene matches the same event, the highest priority wins. If its photo cannot be opened, Studio tries the next matching scene.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun SceneCard(
    scene: WallpaperScenario,
    image: WallpaperImage?,
    onToggle: (Boolean) -> Unit,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    HubGlassCard(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WallpaperThumbnail(image, Modifier.size(width = 74.dp, height = 92.dp))
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(scene.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(scene.trigger.label, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(scene.target.label + if (scene.trigger == WallpaperTrigger.TIME) "  ·  ${scene.time}" else "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                Text("Priority ${scene.priority}/5", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
            Switch(checked = scene.enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HubQuietButton("Apply", icon = Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f), enabled = scene.enabled, onClick = onApply)
            HubQuietButton("Edit", icon = Icons.Rounded.Tune, modifier = Modifier.weight(1f), onClick = onEdit)
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete scene", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CompactSceneRow(scene: WallpaperScenario, image: WallpaperImage?, onApply: () -> Unit) {
    HubGlassCard(contentPadding = 12.dp, onClick = onApply) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WallpaperThumbnail(image, Modifier.size(width = 48.dp, height = 58.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(scene.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(scene.trigger.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
            Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun LibraryScreen(
    images: List<WallpaperImage>,
    scenarios: List<WallpaperScenario>,
    onAdd: () -> Unit,
    onDelete: (WallpaperImage) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        HubHeroHeader(
            kicker = "YOUR COLLECTION",
            title = "Wallpaper library",
            subtitle = "Keep the images you want to rotate through.",
            icon = Icons.Rounded.Image,
        )
        HubPrimaryButton("Import photos", icon = Icons.Rounded.Add, onClick = onAdd)
        Row(verticalAlignment = Alignment.CenterVertically) {
            HubSectionTitle("${images.size} IMAGES", Modifier.weight(1f))
            Text("Stored on this device", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
        if (images.isEmpty()) {
            HubGlassCard {
                HubEmptyState(
                    title = "A blank canvas",
                    message = "Import photos from your gallery. Only the images you select are accessible to Studio.",
                    icon = Icons.Rounded.Image,
                )
            }
        } else {
            images.chunked(2).forEach { rowImages ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowImages.forEach { image ->
                        val assigned = scenarios.count { it.imageId == image.id }
                        HubGlassCard(
                            modifier = Modifier.weight(1f),
                            contentPadding = 9.dp,
                        ) {
                            WallpaperImageView(image, Modifier.fillMaxWidth().aspectRatio(0.82f))
                            Spacer(Modifier.height(8.dp))
                            Text(image.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (assigned == 0) "Not assigned" else "Used by $assigned scene(s)",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                            )
                            IconButton(
                                onClick = { onDelete(image) },
                                enabled = assigned == 0,
                                modifier = Modifier.align(Alignment.End).size(34.dp),
                            ) {
                                Icon(Icons.Rounded.Delete, null, tint = if (assigned == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    if (rowImages.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    scenarioCount: Int,
    imageCount: Int,
    lastError: String?,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        HubHeroHeader(
            kicker = "PREFERENCES",
            title = "Settings",
            subtitle = "A few details that keep your scenes dependable.",
        )
        HubGlassCard {
            Text("AUTOMATION STATUS", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(12.dp))
            SettingValue("Active scenes", scenarioCount.toString())
            SettingValue("Library images", imageCount.toString())
            SettingValue("Event monitor", "Only for event scenes")
            Text(
                "Unlock, power and Wi-Fi scenes use a small ongoing notification while any event scene is enabled. Time-only scenes use alarms and do not keep the monitor running.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        HubGlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Fingerprint, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("SAMSUNG ROUTINES+", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "On supported Galaxy devices, Routines+ may offer an “Unlock with fingerprint” condition. Pair that condition with a Wallpaper Studio scene shortcut when it appears in the available actions. The exact options depend on your One UI and Good Lock version.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                "Face unlock is not exposed as a separate trigger to ordinary Android apps. Use the built-in “When you unlock” scene for any unlock method.",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        HubGlassCard {
            Text("SCHEDULES", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
            Text(
                "Daily time scenes use Android's battery-aware alarm delivery. They may run a little later while the phone is in deep Doze; exact alarms are deliberately not requested.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            HubQuietButton("Refresh schedules", icon = Icons.Rounded.Schedule, onClick = onRefresh)
        }
        if (!lastError.isNullOrBlank()) {
            HubGlassCard {
                Text("LAST ISSUE", color = MaterialTheme.colorScheme.error, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                Text(lastError, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Text(
            "Wallpaper Studio  ·  ${BuildConfig.VERSION_NAME}",
            Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun SettingValue(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ScenarioEditorDialog(
    initial: WallpaperScenario?,
    images: List<WallpaperImage>,
    onDismiss: () -> Unit,
    onAddPhotos: () -> Unit,
    onSave: (WallpaperScenario) -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var imageId by remember(initial?.id) {
        mutableStateOf(initial?.imageId ?: images.firstOrNull()?.id.orEmpty())
    }
    var trigger by remember(initial?.id) { mutableStateOf(initial?.trigger ?: WallpaperTrigger.UNLOCKED) }
    var target by remember(initial?.id) { mutableStateOf(initial?.target ?: WallpaperTarget.BOTH) }
    var priority by remember(initial?.id) { mutableIntStateOf(initial?.priority ?: 3) }
    var time by remember(initial?.id) { mutableStateOf(initial?.time ?: "21:00") }
    var triggerMenu by remember { mutableStateOf(false) }
    LaunchedEffect(images.size) {
        if (imageId.isBlank() && images.isNotEmpty()) imageId = images.first().id
    }
    val timeValid = time.matches(Regex("^([01]\\d|2[0-3]):[0-5]\\d$"))
    val canSave = name.isNotBlank() && imageId.isNotBlank() && images.any { it.id == imageId } &&
        (trigger != WallpaperTrigger.TIME || timeValid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) "Create a scene" else "Edit scene",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Scene name") },
                    singleLine = true,
                )

                Text("CHOOSE A WALLPAPER", color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp)
                if (images.isEmpty()) {
                    HubEmptyState("No photos yet", "Import an image to create this scene.", icon = Icons.Rounded.Image)
                    HubQuietButton("Import photos", icon = Icons.Rounded.Add, onClick = onAddPhotos)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        items(images, key = { it.id }) { image ->
                            val selected = image.id == imageId
                            Column(
                                Modifier
                                    .width(90.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(
                                        if (selected) 2.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(14.dp),
                                    )
                                    .clickable { imageId = image.id }
                                    .padding(5.dp),
                            ) {
                                Box {
                                    WallpaperImageView(image, Modifier.fillMaxWidth().height(104.dp))
                                    if (selected) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            null,
                                            tint = Color.White,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(5.dp)
                                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                                .padding(4.dp)
                                                .size(13.dp),
                                        )
                                    }
                                }
                                Text(image.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    TextButton(onClick = onAddPhotos) { Text("Add more photos") }
                }

                Text("WHEN SHOULD IT CHANGE?", color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp)
                Box {
                    HubQuietButton(
                        text = trigger.label,
                        icon = triggerIcon(trigger),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { triggerMenu = true },
                    )
                    DropdownMenu(expanded = triggerMenu, onDismissRequest = { triggerMenu = false }) {
                        WallpaperTrigger.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(option.label, fontWeight = FontWeight.SemiBold)
                                        Text(option.detail, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    trigger = option
                                    triggerMenu = false
                                },
                            )
                        }
                    }
                }
                if (trigger == WallpaperTrigger.TIME) {
                    OutlinedTextField(
                        value = time,
                        onValueChange = { time = it.take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Daily time (24-hour)") },
                        supportingText = { Text(if (timeValid) "Repeats every day" else "Use HH:mm, for example 21:30") },
                        isError = !timeValid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                Text("APPLY TO", color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp)
                HubSegmented(
                    options = WallpaperTarget.entries.map { it.label },
                    selectedIndex = WallpaperTarget.entries.indexOf(target),
                    onSelect = { target = WallpaperTarget.entries[it] },
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Scene priority", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text("Higher wins when triggers overlap.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                    Text("$priority / 5", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = priority.toFloat(),
                    onValueChange = { priority = it.toInt().coerceIn(1, 5) },
                    valueRange = 1f..5f,
                    steps = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        WallpaperScenario(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            imageId = imageId,
                            trigger = trigger,
                            target = target,
                            enabled = initial?.enabled ?: true,
                            priority = priority,
                            time = time,
                        ),
                    )
                },
            ) { Text("Save scene") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WallpaperImageView(image: WallpaperImage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = image.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(image.uri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val sample = maxOf(
                    1,
                    minOf(bounds.outWidth.coerceAtLeast(1) / 900, bounds.outHeight.coerceAtLeast(1) / 1400),
                )
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(
                        it,
                        null,
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.RGB_565
                        },
                    )
                }
            }.getOrNull()
        }
    }

    if (bitmap != null) {
        Image(
            bitmap!!.asImageBitmap(),
            contentDescription = image.name,
            modifier = modifier.clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.34f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f),
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WallpaperThumbnail(image: WallpaperImage?, modifier: Modifier = Modifier) {
    if (image == null) {
        Box(
            modifier
                .clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        WallpaperImageView(image, modifier)
    }
}

private fun triggerIcon(trigger: WallpaperTrigger) = when (trigger) {
    WallpaperTrigger.UNLOCKED -> Icons.Rounded.Lock
    WallpaperTrigger.CHARGING, WallpaperTrigger.UNPLUGGED -> Icons.Rounded.Power
    WallpaperTrigger.WIFI_CONNECTED, WallpaperTrigger.WIFI_DISCONNECTED -> Icons.Rounded.Wifi
    WallpaperTrigger.LOW_BATTERY -> Icons.Rounded.BatteryAlert
    WallpaperTrigger.TIME -> Icons.Rounded.Alarm
}
