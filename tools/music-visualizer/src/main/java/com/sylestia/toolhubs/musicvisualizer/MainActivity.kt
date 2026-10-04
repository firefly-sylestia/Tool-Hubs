package com.sylestia.toolhubs.musicvisualizer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sylestia.toolhubs.designsystem.ToolHubsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private data class AudioFrame(
    val spectrum: FloatArray = FloatArray(64),
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,
    val energy: Float = 0f,
    val beat: Float = 0f,
)

private class PulseAnalyzer {
    private val previous = FloatArray(64)
    private val history = FloatArray(72)
    private var historyCount = 0
    private var historyIndex = 0
    private var lastBeatMs = 0L

    fun analyze(samples: ShortArray, count: Int, sampleRate: Int): AudioFrame {
        val n = 1024
        val real = DoubleArray(n)
        val imag = DoubleArray(n)
        val used = min(count, n)
        var rms = 0.0

        for (i in 0 until used) {
            val v = samples[i] / 32768.0
            rms += v * v
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / max(1, used - 1))
            real[i] = v * window
        }

        fft(real, imag)

        val spectrum = FloatArray(64)
        var flux = 0.0
        var bass = 0.0
        var mid = 0.0
        var treble = 0.0

        for (band in 0 until 64) {
            val lo = max(1, 2.0.pow(band / 8.0).toInt())
            val hi = min(n / 2, max(lo + 1, 2.0.pow((band + 1) / 8.0).toInt()))
            var magnitude = 0.0
            for (bin in lo until hi) {
                magnitude = max(magnitude, hypot(real[bin], imag[bin]) / max(1.0, used.toDouble()))
            }
            val value = (ln1p(magnitude * 75.0) / ln1p(75.0)).toFloat().coerceIn(0f, 1f)
            spectrum[band] = value
            flux += max(0.0, value - previous[band])
            previous[band] = value
            when {
                band < 15 -> bass += value
                band < 38 -> mid += value
                else -> treble += value
            }
        }

        bass = (bass / 15.0).toFloat().coerceIn(0f, 1f)
        mid = (mid / 23.0).toFloat().coerceIn(0f, 1f)
        treble = (treble / 26.0).toFloat().coerceIn(0f, 1f)
        val energy = sqrt(rms / max(1, used)).toFloat().coerceIn(0f, 1f)

        history[historyIndex] = flux.toFloat()
        historyIndex = (historyIndex + 1) % history.size
        historyCount = min(history.size, historyCount + 1)
        var average = 0f
        for (i in 0 until historyCount) average += history[i]
        average /= max(1, historyCount)

        val now = android.os.SystemClock.elapsedRealtime()
        val threshold = average * 1.75f + 0.018f
        val beat = if (flux > threshold && now - lastBeatMs > 105L && bass > 0.08f) {
            lastBeatMs = now
            1f
        } else {
            0f
        }

        return AudioFrame(
            spectrum = spectrum,
            bass = bass,
            mid = mid,
            treble = treble,
            energy = energy,
            beat = beat,
        )
    }

    private fun ln1p(value: Double): Double = kotlin.math.ln(1.0 + value)

    private fun Double.pow(value: Double): Double = kotlin.math.exp(kotlin.math.ln(this) * value)

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        var j = 0
        for (i in 1 until real.size) {
            var bit = real.size shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = real[i]
                real[i] = real[j]
                real[j] = t
                t = imag[i]
                imag[i] = imag[j]
                imag[j] = t
            }
        }

        var length = 2
        while (length <= real.size) {
            val angle = -2.0 * PI / length
            val wr0 = cos(angle)
            val wi0 = sin(angle)
            for (start in 0 until real.size step length) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until length / 2) {
                    val ur = real[start + k]
                    val ui = imag[start + k]
                    val vr = real[start + k + length / 2] * wr - imag[start + k + length / 2] * wi
                    val vi = real[start + k + length / 2] * wi + imag[start + k + length / 2] * wr
                    real[start + k] = ur + vr
                    imag[start + k] = ui + vi
                    real[start + k + length / 2] = ur - vr
                    imag[start + k + length / 2] = ui - vi
                    val nextWr = wr * wr0 - wi * wi0
                    wi = wr * wi0 + wi * wr0
                    wr = nextWr
                }
            }
            length = length shl 1
        }
    }
}

class MainActivity : ComponentActivity() {
    private var captureJob: Job? = null
    private var projection: MediaProjection? = null
    private var running by mutableStateOf(false)
    private var mode by mutableStateOf("Idle")
    private var frame by mutableStateOf(AudioFrame())
    private var message by mutableStateOf("Play music, then start the visualizer.")

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null && Build.VERSION.SDK_INT >= 29) {
                startPlaybackCapture(result.data!!)
            } else {
                message = "Device-audio capture was cancelled."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ToolHubsTheme(darkTheme = true) {
                PulseScreen(
                    frame = frame,
                    running = running,
                    mode = mode,
                    message = message,
                    onDeviceAudio = ::requestDeviceAudio,
                    onMic = ::startMic,
                    onStop = ::stopCapture,
                )
            }
        }
    }

    private fun requestDeviceAudio() {
        if (Build.VERSION.SDK_INT < 29) {
            message = "Device-audio capture needs Android 10 or newer."
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 70)
            message = "Allow microphone access, then tap Device audio again."
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startPlaybackCapture(data: Intent) {
        if (Build.VERSION.SDK_INT < 29) return
        stopCapture()
        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(Activity.RESULT_OK, data)
        val activeProjection = projection ?: run {
            message = "Android did not create an audio capture session."
            return
        }
        activeProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                runOnUiThread { stopCapture("System audio capture ended.") }
            }
        }, mainExecutor)
        startRecorder(playback = activeProjection)
    }

    private fun startMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 70)
            message = "Allow microphone access, then tap Mic again."
            return
        }
        stopCapture()
        startRecorder(playback = null)
    }

    private fun startRecorder(playback: MediaProjection?) {
        captureJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val sampleRate = 44100
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val bufferSize = max(
                8192,
                AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ) * 2
            )

            val recorder = try {
                if (playback != null && Build.VERSION.SDK_INT >= 29) {
                    val captureConfig = AudioPlaybackCaptureConfiguration.Builder(playback)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .build()
                    AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferSize)
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .build()
                } else {
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    running = false
                    mode = "Idle"
                    message = "Audio capture failed: ${t.javaClass.simpleName}"
                }
                return@launch
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                withContext(Dispatchers.Main) {
                    running = false
                    mode = "Idle"
                    message = "Audio input could not be initialized."
                }
                return@launch
            }

            val analyzer = PulseAnalyzer()
            val samples = ShortArray(1024)
            recorder.startRecording()

            withContext(Dispatchers.Main) {
                running = true
                mode = if (playback != null) "DEVICE AUDIO" else "MIC"
                message = if (playback != null) {
                    "Listening to capturable media playback in real time."
                } else {
                    "Mic visualizer active."
                }
            }

            try {
                while (isActive) {
                    val read = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        val next = analyzer.analyze(samples, read, sampleRate)
                        withContext(Dispatchers.Main.immediate) {
                            frame = next
                        }
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }
        }
    }

    private fun stopCapture(text: String = "Visualizer stopped.") {
        captureJob?.cancel()
        captureJob = null
        projection?.let { runCatching { it.stop() } }
        projection = null
        running = false
        mode = "Idle"
        message = text
        frame = AudioFrame()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}

@Composable
private fun PulseScreen(
    frame: AudioFrame,
    running: Boolean,
    mode: String,
    message: String,
    onDeviceAudio: () -> Unit,
    onMic: () -> Unit,
    onStop: () -> Unit,
) {
    var time by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            withInfiniteAnimationFrameNanos { nanos ->
                time = nanos / 1_000_000_000f
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PulseCanvas(
            modifier = Modifier.fillMaxSize(),
            frame = frame,
            time = time,
        )

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Text(
                "PULSE",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 11.sp,
                letterSpacing = 4.sp,
            )
            Text(
                "Stretch the sound.",
                color = Color.White,
                fontSize = 28.sp,
                lineHeight = 31.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 11.sp,
                maxLines = 2,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                VisualizerButton(
                    text = "Device audio",
                    icon = Icons.Rounded.Waves,
                    active = mode == "DEVICE AUDIO",
                    modifier = Modifier.weight(1f),
                    onClick = onDeviceAudio,
                )
                VisualizerButton(
                    text = "Mic",
                    icon = Icons.Rounded.Mic,
                    active = mode == "MIC",
                    modifier = Modifier.weight(0.52f),
                    onClick = onMic,
                )
                if (running) {
                    VisualizerButton(
                        text = "Stop",
                        icon = Icons.Rounded.Stop,
                        active = false,
                        modifier = Modifier.weight(0.48f),
                        onClick = onStop,
                    )
                }
            }
        }
    }
}

@Composable
private fun VisualizerButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(if (active) Color.White else Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) Color.Black else Color.White,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text,
            color = if (active) Color.Black else Color.White,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun PulseCanvas(
    modifier: Modifier,
    frame: AudioFrame,
    time: Float,
) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val minSize = min(size.width, size.height)
        val baseRadius = minSize * 0.065f
        val outer = minSize * 0.52f

        // Beat impulse is deliberately short. The geometry never loops through a
        // fixed animation clip: every frame is a function of live spectrum + time.
        val beat = frame.beat
        val bass = frame.bass
        val mid = frame.mid
        val treble = frame.treble
        val energy = frame.energy

        drawRect(Color.Black)

        val arms = 56
        val points = 26

        for (arm in 0 until arms) {
            val armT = arm.toFloat() / arms
            val seed = arm * 0.713f + 1.37f
            val angleBase = armT * 2f * PI.toFloat()

            val path = Path()
            for (p in 0 until points) {
                val t = p.toFloat() / (points - 1)
                val bandIndex = ((arm * 1.7f + p * 1.9f).toInt()).floorMod(64)
                val spectral = frame.spectrum[bandIndex]

                // Multiple incommensurate oscillators prevent a repeating "GIF" feel.
                val drift = sin(time * (0.31f + seed * 0.017f) + seed * 2.4f)
                val ripple = sin(time * 0.73f + t * 13.0f + seed)
                val fine = cos(time * 1.17f + t * 29.0f - seed * 0.7f)
                val twist = drift * 0.10f + ripple * 0.045f + fine * 0.018f

                val stretch = 1f +
                    bass * 0.58f * (0.25f + t * 1.25f) +
                    mid * 0.24f * sin(t * PI.toFloat()) +
                    spectral * 0.22f +
                    beat * 0.32f * (t * t)

                val radius = baseRadius + (outer - baseRadius) * t
                val r = radius * stretch
                val angle = angleBase +
                    twist +
                    sin(t * 4.2f + seed) * 0.035f * (1f + beat * 2.2f)

                val x = cx + cos(angle) * r
                val y = cy + sin(angle) * r

                if (p == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            val pink = Color(0xFFE7C0C5)
            val green = Color(0xFF1D5C3B)
            val mix = ((sin(seed * 1.7f + time * 0.13f) + 1f) / 2f)
            val color = lerp(pink, green, mix * 0.82f).copy(alpha = 0.32f + energy * 0.35f)

            drawPath(
                path,
                color = color.copy(alpha = 0.08f + beat * 0.10f),
                style = Stroke(width = 10f + beat * 8f, cap = StrokeCap.Round),
            )
            drawPath(
                path,
                color = color,
                style = Stroke(width = 1.3f + spectralWidth(frame.spectrum[bandIndexSafe(arm)], 2.8f), cap = StrokeCap.Round),
            )

            // A second, slightly detuned ribbon creates the stretched "threads" seen
            // in the reference without repeating the same contour on every arm.
            if (arm % 2 == 0) {
                val echo = Path()
                for (p in 0 until points) {
                    val t = p.toFloat() / (points - 1)
                    val r = baseRadius + (outer - baseRadius) * t
                    val echoWave = sin(time * 0.41f + seed * 2.1f + t * 17f) * 0.05f
                    val angle = angleBase - twist * 0.7f + echoWave
                    val rr = r * (1f + bass * 0.38f * t + treble * 0.18f * (1f - t))
                    val x = cx + cos(angle) * rr
                    val y = cy + sin(angle) * rr
                    if (p == 0) echo.moveTo(x, y) else echo.lineTo(x, y)
                }
                drawPath(
                    echo,
                    color = pink.copy(alpha = 0.12f + treble * 0.25f),
                    style = Stroke(width = 1.1f, cap = StrokeCap.Round),
                )
            }
        }

        // Beat-driven center bloom.
        val bloom = baseRadius * (1f + bass * 1.8f + beat * 3.5f)
        drawCircle(Color(0xFFE7C0C5).copy(alpha = 0.06f + beat * 0.14f), bloom * 1.8f)
        drawCircle(Color(0xFF10251B).copy(alpha = 0.92f), bloom * 0.76f)
        drawCircle(Color.White.copy(alpha = 0.22f + beat * 0.4f), bloom * 0.08f)
    }
}

private fun spectralWidth(value: Float, max: Float): Float = value.coerceIn(0f, 1f) * max

private fun bandIndexSafe(arm: Int): Int = (arm * 2).floorMod(64)

private fun Int.floorMod(modulus: Int): Int {
    val r = this % modulus
    return if (r < 0) r + modulus else r
}

private fun lerp(a: Color, b: Color, amount: Float): Color =
    Color(
        red = a.red + (b.red - a.red) * amount,
        green = a.green + (b.green - a.green) * amount,
        blue = a.blue + (b.blue - a.blue) * amount,
        alpha = a.alpha + (b.alpha - a.alpha) * amount,
    )
