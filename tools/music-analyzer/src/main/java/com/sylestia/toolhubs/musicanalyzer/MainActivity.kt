@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sylestia.toolhubs.musicanalyzer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ToolHubsTheme { MusicAnalyzerScreen() } }
    }
    fun requestMic() = requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 42)
}

data class AudioAnalysis(
    val name: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val rmsDb: Float,
    val peakDb: Float,
    val dominantHz: Float,
    val note: String,
    val waveform: List<Float>,
    val spectrum: List<Float>,
    val analyzedAt: Long = System.currentTimeMillis()
)

private fun db(v: Double): Float = (20.0 * log10(max(v, 1e-7))).toFloat().coerceAtLeast(-120f)

private fun noteFor(freq: Double): String {
    if (freq < 20.0 || !freq.isFinite()) return "—"
    val names = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    val midi = (69.0 + 12.0 * log2(freq / 440.0)).roundToInt()
    return "${names[(midi % 12 + 12) % 12]}${midi / 12 - 1}"
}

private fun fft(real: DoubleArray, imag: DoubleArray) {
    val n = real.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
        j = j xor bit
        if (i < j) {
            var t = real[i]; real[i] = real[j]; real[j] = t
            t = imag[i]; imag[i] = imag[j]; imag[j] = t
        }
    }
    var len = 2
    while (len <= n) {
        val a = -2.0 * PI / len
        val wr0 = cos(a); val wi0 = sin(a)
        for (start in 0 until n step len) {
            var wr = 1.0; var wi = 0.0
            for (k in 0 until len / 2) {
                val uR = real[start + k]; val uI = imag[start + k]
                val vR = real[start + k + len / 2] * wr - imag[start + k + len / 2] * wi
                val vI = real[start + k + len / 2] * wi + imag[start + k + len / 2] * wr
                real[start + k] = uR + vR; imag[start + k] = uI + vI
                real[start + k + len / 2] = uR - vR; imag[start + k + len / 2] = uI - vI
                val nr = wr * wr0 - wi * wi0
                wi = wr * wi0 + wi * wr0; wr = nr
            }
        }
        len = len shl 1
    }
}

private fun dominantFrequency(samples: FloatArray, sampleRate: Int): Double {
    val n = samples.size
    val minLag = max(2, sampleRate / 1000)
    val maxLag = min(n / 2, sampleRate / 55)
    if (maxLag <= minLag) return 0.0
    var energy = 0.0
    for (v in samples) energy += v * v
    if (energy / n < 0.000002) return 0.0
    var bestLag = 0
    var best = -1.0
    for (lag in minLag..maxLag) {
        var corr = 0.0
        var e1 = 0.0
        var e2 = 0.0
        var i = 0
        while (i + lag < n) {
            val a = samples[i].toDouble(); val b = samples[i + lag].toDouble()
            corr += a * b; e1 += a * a; e2 += b * b; i++
        }
        val normalized = corr / sqrt(max(1e-12, e1 * e2))
        if (normalized > best) { best = normalized; bestLag = lag }
    }
    if (bestLag == 0 || best < 0.45) return 0.0
    return sampleRate.toDouble() / bestLag
}

private fun analyzePcm(pcm: FloatArray, sampleRate: Int, channels: Int): Triple<List<Float>, List<Float>, Double> {
    if (pcm.isEmpty()) return Triple(emptyList(), emptyList(), 0.0)
    val mono = FloatArray(min(pcm.size, sampleRate * 12))
    if (channels <= 1) {
        System.arraycopy(pcm, 0, mono, 0, mono.size)
    } else {
        var o = 0
        var i = 0
        while (o < mono.size && i + channels <= pcm.size) {
            var s = 0f
            repeat(channels) { s += pcm[i++] }
            mono[o++] = s / channels
        }
    }
    val waveform = ArrayList<Float>(160)
    val block = max(1, mono.size / 160)
    for (start in mono.indices step block) {
        val end = min(mono.size, start + block)
        var peak = 0f
        for (i in start until end) peak = max(peak, abs(mono[i]))
        waveform.add(peak.coerceIn(0f, 1f))
        if (waveform.size >= 160) break
    }
    val n = 4096
    val real = DoubleArray(n)
    val imag = DoubleArray(n)
    val source = mono.copyOf(min(n, mono.size))
    for (i in source.indices) real[i] = source[i] * (0.5 - 0.5 * cos(2 * PI * i / max(1, source.size - 1)))
    fft(real, imag)
    val spectrum = ArrayList<Float>(96)
    for (band in 0 until 96) {
        val lo = max(1, 2.0.pow(band / 16.0)).toInt()
        val hi = min(n / 2, max(lo + 1, 2.0.pow((band + 1) / 16.0)).toInt())
        var m = 0.0
        for (bin in lo until hi) m = max(m, hypot(real[bin], imag[bin]) / n)
        spectrum.add((ln(1.0 + m * 60.0) / ln(61.0)).toFloat().coerceIn(0f, 1f))
    }
    return Triple(waveform, spectrum, dominantFrequency(source, sampleRate))
}

private fun decodeAudio(context: Context, uri: Uri, name: String): AudioAnalysis = run {
    val extractor = MediaExtractor()
    extractor.setDataSource(context, uri, null)
    var track = -1
    var format: MediaFormat? = null
    for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
        if (mime.startsWith("audio/")) { track = i; format = f; break }
    }
    if (track < 0 || format == null) { extractor.release(); throw IllegalArgumentException("No supported audio track") }
    extractor.selectTrack(track)
    val mime = format.getString(MediaFormat.KEY_MIME)!!
    val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
    val duration = format.getLong(MediaFormat.KEY_DURATION, 0L) / 1000L
    val codec = MediaCodec.createDecoderByType(mime)
    codec.configure(format, null, null, 0)
    codec.start()
    val output = ArrayList<Float>(sampleRate * channels.coerceAtMost(2).coerceAtLeast(1) * 4)
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var outputDone = false
    try {
        while (!outputDone && output.size < sampleRate * channels * 120) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10000)
                if (index >= 0) {
                    val input = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, 10000)
            if (index >= 0) {
                if (info.size > 0) {
                    val buffer = codec.getOutputBuffer(index)!!
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    val remaining = buffer.remaining()
                    val shortCount = remaining / 2
                    val temp = ByteArray(remaining)
                    buffer.get(temp)
                    var p = 0
                    while (p + 1 < temp.size) {
                        val lo = temp[p].toInt() and 255
                        val hi = temp[p + 1].toInt()
                        output.add(((hi shl 8 or lo).toShort().toInt() / 32768f).coerceIn(-1f, 1f))
                        p += 2
                    }
                    if (shortCount == 0) Unit
                }
                codec.releaseOutputBuffer(index, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                if (output.size > sampleRate * channels * 8) outputDone = true
            }
        }
    } finally {
        runCatching { codec.stop() }; codec.release(); extractor.release()
    }
    val pcm = output.toFloatArray()
    var sum = 0.0; var peak = 0.0
    for (v in pcm) { sum += v * v; peak = max(peak, abs(v.toDouble())) }
    val rms = sqrt(sum / max(1, pcm.size))
    val (wave, spec, hz) = analyzePcm(pcm, sampleRate, channels)
    AudioAnalysis(name, duration, sampleRate, channels, db(rms), db(peak), hz.toFloat(), noteFor(hz), wave, spec)
}

private class AnalysisStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("music_analysis", Context.MODE_PRIVATE)
    fun load(): List<AudioAnalysis> {
        val array = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i -> runCatching { fromJson(array.getJSONObject(i)) }.getOrNull() }
    }
    fun save(items: List<AudioAnalysis>) {
        val array = JSONArray(); items.take(30).forEach { array.put(toJson(it)) }
        prefs.edit().putString("items", array.toString()).apply()
    }
    private fun toJson(a: AudioAnalysis) = JSONObject().apply {
        put("name", a.name); put("duration", a.durationMs); put("rate", a.sampleRate); put("channels", a.channels)
        put("rms", a.rmsDb); put("peak", a.peakDb); put("hz", a.dominantHz); put("note", a.note); put("at", a.analyzedAt)
        put("wave", JSONArray(a.waveform)); put("spec", JSONArray(a.spectrum))
    }
    private fun fromJson(o: JSONObject): AudioAnalysis = AudioAnalysis(
        o.getString("name"), o.getLong("duration"), o.getInt("rate"), o.getInt("channels"), o.getDouble("rms").toFloat(),
        o.getDouble("peak").toFloat(), o.getDouble("hz").toFloat(), o.getString("note"),
        List(o.getJSONArray("wave").length()) { o.getJSONArray("wave").getDouble(it).toFloat() },
        List(o.getJSONArray("spec").length()) { o.getJSONArray("spec").getDouble(it).toFloat() }, o.optLong("at")
    )
}

private fun displayName(context: Context, uri: Uri): String {
    var result = "Audio file"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) result = c.getString(0) ?: result
    }
    return result
}

@Composable
private fun GraphCard(title: String, subtitle: String, values: List<Float>, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(28.dp)).background(Color.White.copy(.46f)).padding(16.dp)) {
        Text(title, color = CurioInk, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, color = CurioRoseDark.copy(.58f), fontSize = 10.sp)
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            if (values.isEmpty()) return@Canvas
            val grid = Color.White.copy(.5f)
            repeat(4) { y -> drawLine(grid, Offset(0f, size.height * y / 4f), Offset(size.width, size.height * y / 4f), 1f) }
            val path = Path()
            values.forEachIndexed { i, value ->
                val x = if (values.size == 1) 0f else i * size.width / (values.size - 1)
                val y = size.height - value.coerceIn(0f, 1f) * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, brush = Brush.horizontalGradient(listOf(CurioRoseWood, CurioRoseDark)), style = androidx.compose.ui.graphics.drawscope.Stroke(3f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun StatPill(label: String, value: String) {
    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(.48f)).padding(horizontal = 13.dp, vertical = 11.dp)) {
        Text(label, color = CurioRoseDark, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(value, color = CurioInk, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun AnalysisSheet(a: AudioAnalysis, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CurioCream) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            Text("FILE ANALYSIS", color = CurioRoseDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(a.name, color = CurioInk, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${a.durationMs / 60000}:${(a.durationMs / 1000 % 60).toString().padStart(2, '0')}  ·  ${a.sampleRate} Hz  ·  ${a.channels} ch", color = CurioRoseDark.copy(.62f), fontSize = 11.sp)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("RMS", "${a.rmsDb.roundToInt()} dB")
                StatPill("PEAK", "${a.peakDb.roundToInt()} dB")
                StatPill("PITCH", "${a.dominantHz.roundToInt()} Hz")
                StatPill("NOTE", a.note)
            }
            Spacer(Modifier.height(12.dp))
            GraphCard("Waveform", "Amplitude envelope from decoded audio", a.waveform)
            Spacer(Modifier.height(10.dp))
            GraphCard("Frequency spectrum", "FFT energy distribution · low → high", a.spectrum)
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun MusicAnalyzerScreen() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val store = remember { AnalysisStore(context) }
    var history by remember { mutableStateOf(store.load()) }
    var selected by remember { mutableStateOf<AudioAnalysis?>(history.firstOrNull()) }
    var active by remember { mutableStateOf(false) }
    var analyzingFile by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(-120f) }
    var frequency by remember { mutableFloatStateOf(0f) }
    var note by remember { mutableStateOf("—") }
    var liveBars by remember { mutableStateOf(List(48) { 0f }) }
    var historyOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            analyzingFile = true
            kotlinx.coroutines.MainScope().launch {
                val result = withContext(Dispatchers.IO) { runCatching { decodeAudio(context, uri, displayName(context, uri)) }.getOrNull() }
                analyzingFile = false
                if (result != null) {
                    history = (listOf(result) + history.filter { it.name != result.name }).take(30)
                    store.save(history)
                    selected = result
                }
            }
        }
    }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestMic(); active = false; return@LaunchedEffect
        }
        withContext(Dispatchers.Default) {
            val rate = 44100; val n = 2048
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return@withContext
            val record = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, n * 2))
            val samples = ShortArray(n)
            val real = DoubleArray(n); val imag = DoubleArray(n)
            var smoothLevel = 0f; var smoothPeak = -120f
            try {
                record.startRecording()
                while (isActive && active) {
                    val read = record.read(samples, 0, n)
                    if (read <= 0) continue
                    val mono = FloatArray(read)
                    var sum = 0.0; var maxSample = 0.0
                    for (i in 0 until read) {
                        val v = samples[i] / 32768f; mono[i] = v
                        sum += v * v; maxSample = max(maxSample, abs(v.toDouble()))
                        real[i] = v * (0.5 - 0.5 * cos(2 * PI * i / max(1, read - 1))); imag[i] = 0.0
                    }
                    for (i in read until n) { real[i] = 0.0; imag[i] = 0.0 }
                    fft(real, imag)
                    val mags = FloatArray(48)
                    for (bin in 2 until n / 2) {
                        val mag = hypot(real[bin], imag[bin]) / n
                        val band = min(47, (ln(bin.toDouble()) / ln((n / 2).toDouble()) * 48).toInt())
                        mags[band] = max(mags[band], (ln(1 + mag * 80) / ln(81.0)).toFloat())
                    }
                    val rmsDb = db(sqrt(sum / read))
                    val pDb = db(maxSample)
                    smoothLevel = smoothLevel * .78f + ((rmsDb + 60f) / 60f).coerceIn(0f, 1f) * .22f
                    smoothPeak = max(smoothPeak - .7f, pDb)
                    val hz = dominantFrequency(mono, rate).toFloat()
                    withContext(Dispatchers.Main) {
                        level = smoothLevel; peak = smoothPeak; frequency = hz; note = noteFor(hz.toDouble())
                        liveBars = mags.toList()
                    }
                }
            } finally { runCatching { record.stop() }; record.release() }
        }
    }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CurioCream, Color(0xFFF1E3E6)))).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(68.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AUDIO LAB", color = CurioRoseDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Music Analyzer", color = CurioInk, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
            }
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(50.dp)).background(Color.White.copy(.48f)).clickable { historyOpen = true }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.History, null, tint = CurioInk) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(45.dp).clip(RoundedCornerShape(23.dp)).background(CurioInk).clickable { picker.launch(arrayOf("audio/*")) }, contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.FolderOpen, null, tint = Color.White, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Analyze file", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
            Box(Modifier.width(48.dp).height(45.dp).clip(RoundedCornerShape(23.dp)).background(Color.White.copy(.55f)).clickable { historyOpen = true }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AudioFile, null, tint = CurioRoseWood) }
        }
        if (analyzingFile) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White.copy(.48f)).padding(15.dp)) { Text("Decoding audio and building the graphs…", color = CurioInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
        }
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
            Box(Modifier.fillMaxWidth().height(245.dp).clip(RoundedCornerShape(34.dp)).background(Brush.linearGradient(listOf(Color.White.copy(.62f), CurioSurface.copy(.68f)))).padding(18.dp)) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                    liveBars.forEach { value ->
                        val animated by animateFloatAsState(if (active) value.coerceIn(.025f, 1f) else .025f, label = "spectrum")
                        Box(Modifier.weight(1f).fillMaxHeight(animated).clip(RoundedCornerShape(6.dp)).background(Brush.verticalGradient(listOf(CurioRoseWood, CurioRoseDark))))
                    }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(15.dp)) {
                    Text(if (active) "LIVE ANALYSIS" else "READY", color = CurioRoseDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                    Text(if (frequency > 20) "${frequency.roundToInt()} Hz  ·  $note" else "Choose a file or listen to live audio", color = CurioInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("RMS", "${(level * 60f - 60f).roundToInt()} dB")
                StatPill("PEAK", "${peak.roundToInt()} dB")
                StatPill("PITCH", if (frequency > 20) "${frequency.roundToInt()} Hz" else "—")
                StatPill("NOTE", note)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp)).background(if (active) CurioRoseWood else CurioInk).clickable { active = !active }, contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (active) Icons.Outlined.Stop else Icons.Outlined.Mic, null, tint = Color.White); Spacer(Modifier.width(9.dp)); Text(if (active) "Stop live analysis" else "Analyze live audio", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(7.dp))
            Text("Decoded waveform • FFT spectrum • pitch detection • saved sessions", color = CurioRoseDark.copy(.55f), fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            if (selected != null) {
                Spacer(Modifier.height(8.dp))
                Text("Last file: ${selected!!.name}", color = CurioRoseDark.copy(.65f), fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    if (selected != null) AnalysisSheet(selected!!, onDismiss = { selected = null })
    if (historyOpen) {
        ModalBottomSheet(onDismissRequest = { historyOpen = false }, containerColor = CurioSurface) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Analysis history", color = CurioInk, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    Text("Clear", color = CurioRoseWood, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { history = emptyList(); store.save(history) }.padding(8.dp))
                }
                Spacer(Modifier.height(12.dp))
                if (history.isEmpty()) Text("Analyzed tracks will stay here.", color = CurioRoseDark)
                history.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(.42f)).clickable { selected = item; historyOpen = false }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.GraphicEq, null, tint = CurioRoseWood)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) { Text(item.name, color = CurioInk, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${item.peakDb.roundToInt()} dB peak · ${item.dominantHz.roundToInt()} Hz · ${item.note}", color = CurioRoseDark.copy(.62f), fontSize = 10.sp) }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
