@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sylestia.toolhubs.musicvisualizer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sylestia.toolhubs.designsystem.ToolHubsTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.roundToInt

private data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: Uri,
)

private data class PulseFrame(
    val bands: FloatArray = FloatArray(64),
    val bass: Float = 0f,
    val energy: Float = 0f,
    val beat: Float = 0f,
)

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private var visualizer: Visualizer? = null
    private var songs by mutableStateOf<List<Song>>(emptyList())
    private var currentSong by mutableStateOf<Song?>(null)
    private var playing by mutableStateOf(false)
    private var position by mutableLongStateOf(0L)
    private var duration by mutableLongStateOf(0L)
    private var frame by mutableStateOf(PulseFrame())
    private var screen by mutableStateOf("library")
    private var scanning by mutableStateOf(false)
    private var message by mutableStateOf("Scan your device for local music.")
    private var minMusicDurationMs by mutableLongStateOf(30_000L)
    private var includeShortTracks by mutableStateOf(false)
    private var activePlaylist: List<Song> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { scanMusic() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectPlayer()
        setContent {
            ToolHubsTheme(darkTheme = true) {
                val visibleSongs = songs.filter { includeShortTracks || it.duration >= minMusicDurationMs }
                PulseApp(
                    songs = visibleSongs,
                    totalSongs = songs.size,
                    currentSong = currentSong,
                    playing = playing,
                    position = position,
                    duration = duration,
                    frame = frame,
                    screen = screen,
                    scanning = scanning,
                    message = message,
                    minMusicDurationMs = minMusicDurationMs,
                    includeShortTracks = includeShortTracks,
                    onMinMusicDurationChange = { minMusicDurationMs = it },
                    onIncludeShortTracksChange = { includeShortTracks = it },
                    onScan = ::scanMusic,
                    onSong = { playSong(it, visibleSongs) },
                    onBack = { screen = "library" },
                    onPlayPause = {
                        controller?.let { if (it.isPlaying) it.pause() else it.play() }
                    },
                    onNext = { controller?.seekToNextMediaItem() },
                    onPrevious = { controller?.seekToPreviousMediaItem() },
                    onSeek = { value -> controller?.seekTo(value) },
                    onTick = { controller?.let { position = it.currentPosition.coerceAtLeast(0L); duration = it.duration.coerceAtLeast(0L) } },
                )
            }
        }
    }

    private fun connectPlayer() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            runCatching {
                controller = future.get()
                controller?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playing = isPlaying
                        if (isPlaying) attachVisualizer()
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val index = controller?.currentMediaItemIndex ?: -1
                        currentSong = activePlaylist.getOrNull(index)
                        duration = controller?.duration?.coerceAtLeast(0L) ?: 0L
                        attachVisualizer()
                    }
                })
            }.onFailure { message = "Player unavailable: ${it.message}" }
        }, mainExecutor)
    }

    private fun attachVisualizer() {
        visualizer?.release()
        val sessionId = controller?.audioSessionId ?: 0
        if (sessionId <= 0) return
        runCatching {
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    private var lastEnergy = 0f
                    private var lastBeat = 0L

                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (fft == null) return
                        val bands = FloatArray(64)
                        for (i in bands.indices) {
                            val from = max(2, (2.0.pow(i / 8.0)).toInt())
                            val to = min(fft.size / 2, max(from + 1, (2.0.pow((i + 1) / 8.0)).toInt()))
                            var magnitude = 0f
                            for (bin in from until to) {
                                val index = bin * 2
                                if (index + 1 >= fft.size) break
                                val re = fft[index].toInt()
                                val im = fft[index + 1].toInt()
                                magnitude = max(magnitude, kotlin.math.sqrt((re * re + im * im).toFloat()))
                            }
                            bands[i] = (kotlin.math.ln(1f + magnitude * 0.035f) / kotlin.math.ln(2f)).coerceIn(0f, 1f)
                        }
                        var bass = 0f
                        for (i in 0 until 12) bass += bands[i]
                        bass = (bass / 12f).coerceIn(0f, 1f)
                        var energy = 0f
                        bands.forEach { energy += it }
                        energy = (energy / bands.size).coerceIn(0f, 1f)

                        val now = android.os.SystemClock.elapsedRealtime()
                        val rising = (energy - lastEnergy).coerceAtLeast(0f)
                        val beat = if (rising > 0.055f && bass > 0.12f && now - lastBeat > 115L) {
                            lastBeat = now
                            1f
                        } else 0f
                        lastEnergy = energy
                        runOnUiThread { frame = PulseFrame(bands, bass, energy, beat) }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        }
    }

    private fun scanMusic() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.RECORD_AUDIO))
                return
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO))
            return
        }

        scanning = true
        message = "Scanning Music, Downloads and audio folders…"
        Thread {
            val result = mutableListOf<Song>()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.IS_MUSIC,
            )
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC",
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val songId = cursor.getLong(id)
                    result += Song(
                        songId,
                        cursor.getString(title) ?: "Unknown title",
                        cursor.getString(artist)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist",
                        cursor.getString(album)?.takeIf { it.isNotBlank() } ?: "Unknown album",
                        cursor.getLong(duration),
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId),
                    )
                }
            }
            runOnUiThread {
                songs = result
                scanning = false
                message = if (result.isEmpty()) "No local music found." else "${result.size} tracks found."
            }
        }.start()
    }

    private fun playSong(song: Song, playlist: List<Song>) {
        val player = controller ?: return
        val index = playlist.indexOfFirst { it.id == song.id }
        if (index < 0 || playlist.isEmpty()) return
        activePlaylist = playlist
        player.setMediaItems(playlist.map { MediaItem.fromUri(it.uri) }, index, 0L)
        player.prepare()
        player.play()
        currentSong = song
        duration = song.duration
        screen = "player"
        attachVisualizer()
    }

    override fun onDestroy() {
        visualizer?.release()
        controller?.release()
        super.onDestroy()
    }
}

@Composable
private fun PulseApp(
    songs: List<Song>,
    totalSongs: Int,
    currentSong: Song?,
    playing: Boolean,
    position: Long,
    duration: Long,
    frame: PulseFrame,
    screen: String,
    scanning: Boolean,
    message: String,
    minMusicDurationMs: Long,
    includeShortTracks: Boolean,
    onMinMusicDurationChange: (Long) -> Unit,
    onIncludeShortTracksChange: (Boolean) -> Unit,
    onScan: () -> Unit,
    onSong: (Song) -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onTick: () -> Unit,
) {
    LaunchedEffect(screen, playing) {
        while (screen == "player") {
            onTick()
            delay(150)
        }
    }
    if (screen == "player" && currentSong != null) {
        PlayerScreen(currentSong, playing, position, duration, frame, onBack, onPlayPause, onNext, onPrevious, onSeek)
    } else {
        LibraryScreen(
            songs = songs,
            totalSongs = totalSongs,
            scanning = scanning,
            message = message,
            minMusicDurationMs = minMusicDurationMs,
            includeShortTracks = includeShortTracks,
            onMinMusicDurationChange = onMinMusicDurationChange,
            onIncludeShortTracksChange = onIncludeShortTracksChange,
            onScan = onScan,
            onSong = onSong,
        )
    }
}

@Composable
private fun LibraryScreen(
    songs: List<Song>,
    totalSongs: Int,
    scanning: Boolean,
    message: String,
    minMusicDurationMs: Long,
    includeShortTracks: Boolean,
    onMinMusicDurationChange: (Long) -> Unit,
    onIncludeShortTracksChange: (Boolean) -> Unit,
    onScan: () -> Unit,
    onSong: (Song) -> Unit,
) {
    var showFilter by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF080909)).statusBarsPadding().navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("PULSE", color = Color.White.copy(.55f), fontSize = 10.sp, letterSpacing = 4.sp)
                Text("Your music", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text(
                    if (includeShortTracks) "${totalSongs} tracks" else "${songs.size} tracks • ≥ ${minMusicDurationMs / 1000}s",
                    color = Color.White.copy(.42f),
                    fontSize = 11.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(.08f)).clickable { showFilter = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Tune, null, tint = Color.White)
                }
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(.09f)).clickable(onClick = onScan),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Refresh, null, tint = Color.White)
                }
            }
        }

        if (scanning) {
            Text("Scanning local audio…", color = Color(0xFFE7C0C5), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }

        if (songs.isEmpty() && !scanning) {
            Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.FolderOpen, null, tint = Color.White.copy(.5f), modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(14.dp))
                Text("Nothing here yet", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Scan your phone to find local music.", color = Color.White.copy(.45f), fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                Box(Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).clickable(onClick = onScan).padding(horizontal = 20.dp, vertical = 13.dp)) {
                    Text("Scan music", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                items(songs, key = { it.id }) { song ->
                    SongRow(song, onClick = { onSong(song) })
                }
            }
        }
    }

    if (showFilter) {
        MusicFilterDialog(
            minMusicDurationMs = minMusicDurationMs,
            includeShortTracks = includeShortTracks,
            onMinMusicDurationChange = onMinMusicDurationChange,
            onIncludeShortTracksChange = onIncludeShortTracksChange,
            onDismiss = { showFilter = false },
        )
    }
}


@Composable
private fun MusicFilterDialog(
    minMusicDurationMs: Long,
    includeShortTracks: Boolean,
    onMinMusicDurationChange: (Long) -> Unit,
    onIncludeShortTracksChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var localSeconds by remember { mutableFloatStateOf((minMusicDurationMs / 1000L).toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111313),
        title = {
            Text("Music filter", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Ignore clips shorter than ${localSeconds.toInt()} seconds.",
                    color = Color.White.copy(.72f),
                    fontSize = 13.sp,
                )
                Slider(
                    value = localSeconds,
                    onValueChange = { localSeconds = it },
                    valueRange = 5f..120f,
                    steps = 22,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Include short tracks", color = Color.White, fontSize = 14.sp)
                        Text(
                            "Show everything, including alerts and clips.",
                            color = Color.White.copy(.42f),
                            fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = includeShortTracks,
                        onCheckedChange = onIncludeShortTracksChange,
                    )
                }
                Text(
                    "Default is 30 seconds. Raise it for samples and voice clips; lower it if your library contains short songs.",
                    color = Color.White.copy(.38f),
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onMinMusicDurationChange((localSeconds.roundToInt() * 1000L).coerceIn(5_000L, 120_000L))
                    onDismiss()
                },
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White.copy(.65f))
            }
        },
    )
}

@Composable
private fun SongRow(song: Song, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Color(0xFF1C3327), Color(0xFF7C555F)))), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(.85f))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${song.artist} • ${song.album}", color = Color.White.copy(.42f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatTime(song.duration), color = Color.White.copy(.32f), fontSize = 10.sp)
    }
}

@Composable
private fun PlayerScreen(
    song: Song,
    playing: Boolean,
    position: Long,
    duration: Long,
    frame: PulseFrame,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { phase = it / 1_000_000_000f }
        }
    }
    LaunchedEffect(playing, position) {
        delay(250)
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(.08f)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, null, tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.Album, null, tint = Color.White.copy(.35f), modifier = Modifier.size(21.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            PulseCanvas(frame, phase)
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp)) {
            Text(song.title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = Color.White.copy(.48f), fontSize = 13.sp)
            Spacer(Modifier.height(17.dp))
            val progress = if (duration > 0) position.toFloat() / duration else 0f
            androidx.compose.material3.Slider(
                value = progress.coerceIn(0f, 1f),
                onValueChange = { onSeek((it * duration).toLong()) },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color(0xFFE7C0C5),
                    activeTrackColor = Color(0xFFE7C0C5),
                    inactiveTrackColor = Color.White.copy(.10f),
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), color = Color.White.copy(.35f), fontSize = 10.sp)
                Text(formatTime(duration), color = Color.White.copy(.35f), fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(32.dp).clickable(onClick = onPrevious))
                Box(Modifier.size(62.dp).clip(CircleShape).background(Color.White).clickable(onClick = onPlayPause), contentAlignment = Alignment.Center) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(31.dp))
                }
                Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(32.dp).clickable(onClick = onNext))
            }
        }
    }
}

@Composable
private fun PulseCanvas(frame: PulseFrame, time: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val shortest = min(size.width, size.height)
        val inner = shortest * .035f
        val outer = shortest * .66f
        val sectors = 28
        val rings = 9
        val dots = 22

        drawRect(Color.Black)

        val rotation = time * 4.8f + frame.energy * 8f
        val beatRetract = 1f - frame.beat * .16f
        val breathing = 1f + sin(time * .72f) * .045f
        val bassPush = frame.bass * .22f
        val energyLift = frame.energy * .10f

        drawCircle(
            Color(0xFF020202),
            radius = inner * (2.7f + frame.beat * 2.0f),
            center = Offset(cx, cy),
        )

        for (sector in 0 until sectors) {
            val sectorAngle = sector.toFloat() / sectors * 2f * PI.toFloat() + rotation
            val sectorBand = frame.bands[(sector * 2) % frame.bands.size]

            for (ring in 0 until rings) {
                val ringT = ring.toFloat() / (rings - 1)
                val baseR = inner + (outer - inner) * ringT
                val band = frame.bands[(sector * 5 + ring * 7) % frame.bands.size]
                val radialWave = sin(time * 1.25f + ring * .9f + sector * .31f)
                val pulseWave = sin(time * 2.0f - ring * .55f + sector * .19f)
                val radialScale = beatRetract *
                    breathing *
                    (1f + bassPush * ringT + energyLift * ringT) *
                    (1f + radialWave * (.055f + band * .06f) + pulseWave * .025f)

                val radius = baseR * radialScale
                val points = dots - ring / 2

                for (dot in 0 until points) {
                    val dotT = dot.toFloat() / max(1, points - 1)
                    val wave = sin(dotT * PI.toFloat() * 2.0f + time * 1.55f + ring * .7f + sector * .27f)
                    val wave2 = cos(dotT * PI.toFloat() * 3.0f - time * 1.05f + sector * .41f)
                    val angularWarp = wave * (.11f + band * .16f) + wave2 * .035f
                    val angle = sectorAngle +
                        (dotT - .5f) * (.18f + ringT * .42f) +
                        angularWarp +
                        sin(time * .45f + ring * .6f) * .025f

                    val r = radius + wave2 * (6f + band * 13f) + dotT * 4f
                    val x = cx + cos(angle) * r
                    val y = cy + sin(angle) * r

                    val dotSize = (0.65f + band * 1.7f) *
                        (1f + frame.energy * .55f) *
                        (1f + frame.beat * .9f) *
                        (0.75f + dotT * .55f)

                    val warm = Color(
                        red = .79f + band * .18f,
                        green = .68f + band * .15f,
                        blue = .67f + band * .15f,
                        alpha = .30f + frame.energy * .34f,
                    )

                    drawCircle(
                        color = warm,
                        radius = dotSize,
                        center = Offset(x, y),
                    )

                    if (ring % 2 == 0 && dot % 2 == 0) {
                        val echoR = r + 5f + band * 7f
                        val echoA = angle + .018f
                        drawCircle(
                            color = warm.copy(alpha = .15f + frame.energy * .14f),
                            radius = dotSize * .62f,
                            center = Offset(
                                cx + cos(echoA) * echoR,
                                cy + sin(echoA) * echoR,
                            ),
                        )
                    }
                }
            }

            val petal = Path()
            val petalSamples = 18
            for (i in 0 until petalSamples) {
                val t = i.toFloat() / (petalSamples - 1)
                val r = inner * 2.0f + (outer * .92f) * t * beatRetract
                val petalWave = sin(t * PI.toFloat() * 3.0f + time * 1.35f + sector * .22f)
                val a = sectorAngle +
                    petalWave * (.10f + sectorBand * .10f) +
                    sin(t * PI.toFloat() * 2f + time * .55f) * .025f
                val x = cx + cos(a) * r
                val y = cy + sin(a) * r
                if (i == 0) petal.moveTo(x, y) else petal.lineTo(x, y)
            }
            drawPath(
                petal,
                Color(0xFFD7BFC0).copy(alpha = .07f + sectorBand * .12f),
                style = Stroke(width = 1.1f + sectorBand * 1.3f, cap = StrokeCap.Round),
            )
        }

        for (ring in 0 until 4) {
            val r = inner * (3.0f + ring * 2.2f) * (1f - frame.beat * .07f)
            drawCircle(
                color = Color(0xFFE5CCCD).copy(alpha = .045f + frame.energy * .045f),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 1.2f),
            )
        }

        drawCircle(
            Color(0xFFE5C9CA).copy(alpha = .055f + frame.beat * .08f),
            radius = inner * (2.0f + frame.bass * 2.2f),
            center = Offset(cx, cy),
        )
        drawCircle(
            Color.Black.copy(alpha = .92f),
            radius = inner * (1.25f + frame.beat * .55f),
            center = Offset(cx, cy),
        )
    }
}


private fun formatTime(ms: Long): String {
    val total = max(0L, ms) / 1000L
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

private fun lerp(a: Color, b: Color, amount: Float): Color =
    Color(
        a.red + (b.red - a.red) * amount,
        a.green + (b.green - a.green) * amount,
        a.blue + (b.blue - a.blue) * amount,
        a.alpha + (b.alpha - a.alpha) * amount,
    )

private fun Double.pow(value: Double): Double = kotlin.math.exp(kotlin.math.ln(this) * value)
