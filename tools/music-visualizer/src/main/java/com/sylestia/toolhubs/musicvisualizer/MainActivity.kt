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
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { scanMusic() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectPlayer()
        setContent {
            ToolHubsTheme(darkTheme = true) {
                PulseApp(
                    songs = songs,
                    currentSong = currentSong,
                    playing = playing,
                    position = position,
                    duration = duration,
                    frame = frame,
                    screen = screen,
                    scanning = scanning,
                    message = message,
                    onScan = ::scanMusic,
                    onSong = ::playSong,
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
                        currentSong = songs.getOrNull(index)
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

    private fun playSong(song: Song) {
        val player = controller ?: return
        val index = songs.indexOfFirst { it.id == song.id }
        if (index < 0) return
        player.setMediaItems(songs.map { MediaItem.fromUri(it.uri) }, index, 0L)
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
    currentSong: Song?,
    playing: Boolean,
    position: Long,
    duration: Long,
    frame: PulseFrame,
    screen: String,
    scanning: Boolean,
    message: String,
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
        LibraryScreen(songs, scanning, message, onScan, onSong)
    }
}

@Composable
private fun LibraryScreen(
    songs: List<Song>,
    scanning: Boolean,
    message: String,
    onScan: () -> Unit,
    onSong: (Song) -> Unit,
) {
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
                Text(message, color = Color.White.copy(.42f), fontSize = 11.sp)
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(.09f)).clickable(onClick = onScan),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Refresh, null, tint = Color.White)
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
        val radius = min(size.width, size.height) * .055f
        val reach = min(size.width, size.height) * .50f
        val arms = 64
        val points = 34

        drawRect(Color.Black)
        for (arm in 0 until arms) {
            val seed = arm * 1.713f
            val baseAngle = arm.toFloat() / arms * 2f * PI.toFloat()
            val band = frame.bands[(arm * 3) % 64]
            val path = Path()

            for (p in 0 until points) {
                val t = p.toFloat() / (points - 1)
                val local = frame.bands[((arm * 5 + p * 2) % 64)]
                val drift = sin(time * (.19f + arm * .001f) + seed) * .07f
                val ripple = sin(time * .61f + t * 12f + seed) * .035f
                val beatStretch = frame.beat * (.20f + t * .85f)
                val stretch = 1f +
                    frame.bass * .72f * t +
                    local * .35f * t +
                    beatStretch

                val r = radius + (reach - radius) * t
                val angle = baseAngle + drift + ripple + sin(t * 4.5f + seed) * .035f
                val x = cx + cos(angle) * r * stretch
                val y = cy + sin(angle) * r * stretch

                if (p == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            val pink = Color(0xFFE7C0C5)
            val green = Color(0xFF1E5D3B)
            val mix = ((sin(seed * .9f + time * .11f) + 1f) / 2f)
            val color = lerp(pink, green, mix * .8f).copy(alpha = .30f + frame.energy * .38f)

            drawPath(path, color.copy(alpha = .05f + frame.beat * .12f), style = Stroke(9f + frame.beat * 10f, cap = StrokeCap.Round))
            drawPath(path, color, style = Stroke(1.0f + band * 2.5f, cap = StrokeCap.Round))
        }

        val bloom = radius * (1f + frame.bass * 2f + frame.beat * 4f)
        drawCircle(Color(0xFFE7C0C5).copy(alpha = .06f + frame.beat * .14f), bloom * 2f)
        drawCircle(Color(0xFF09100C).copy(alpha = .94f), bloom * .72f)
        drawCircle(Color.White.copy(alpha = .18f + frame.beat * .5f), bloom * .07f)
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
