package com.sylestia.toolhubs.chime.bell

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.delay

/**
 * The old-clock bell, synthesized rather than shipped as an audio file.
 *
 * A recording would either be a large asset or a heavily compressed one, and a compressed bell loses
 * exactly the long decay that makes it a bell rather than a beep. Striking a bell is also not a fixed
 * sound: a clock makes twelve strikes at midnight and one at one o'clock, so the waveform has to be
 * built per strike anyway.
 *
 * The tone is additive: a small set of inharmonic partials above a fundamental, each with its own
 * amplitude and decay. Real bells are inharmonic — the partials are not whole multiples of the
 * fundamental — and the ratios below are the classic ones (2.0, 2.76 and 4.07) that make the ear
 * read "bell" instead of "organ".
 */
object BellVoice {

    private const val SAMPLE_RATE = 44_100
    /** Fundamental of the bell, roughly the G above middle C. */
    private const val FUNDAMENTAL = 784.0
    /** Gap between one strike and the next, in milliseconds. */
    private const val STRIKE_SPACING_MS = 480
    private const val DECAY_SECONDS = 1.15
    /** Longest tail kept after the final strike. */
    private const val TAIL_MS = 900

    private val partials = listOf(
        Triple(1.0, 1.00, 1.00),
        Triple(2.00, 0.46, 0.72),
        Triple(2.76, 0.30, 0.55),
        Triple(4.07, 0.14, 0.34),
        Triple(5.43, 0.07, 0.24),
    )

    /**
     * Strikes [strikes] times, in order, and returns once the sound has finished.
     *
     * Blocking on purpose: the caller is a short-lived service whose only job is this sound, and a
     * coroutine that returned before the last strike decayed would be asking the system to kill the
     * process mid-chime.
     */
    suspend fun strike(context: Context, strikes: Int) {
        // Zero means the bell is switched off and only the spoken time is wanted; it must stay
        // silent rather than fall back to a single strike.
        val count = strikes.coerceIn(0, 12)
        if (count == 0) return
        val samples = render(count)
        if (samples.isEmpty()) return
        val track = buildTrack(samples.size * 2) ?: return
        try {
            track.write(samples, 0, samples.size)
            track.play()
            // Wait for the buffer to play out and for the tail to breathe before releasing.
            delay((samples.size * 1000L / SAMPLE_RATE) + 120L)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    /** Builds the whole sequence as one buffer: one track, one play call, no timing races. */
    private fun render(strikes: Int): ShortArray {
        val spacing = SAMPLE_RATE * STRIKE_SPACING_MS / 1000
        val tail = SAMPLE_RATE * TAIL_MS / 1000
        val total = spacing * (strikes - 1) + tail
        val buffer = ShortArray(total)
        val strikeLength = (SAMPLE_RATE * DECAY_SECONDS).toInt()
        for (index in 0 until strikes) {
            val offset = index * spacing
            val length = minOf(strikeLength, total - offset)
            for (n in 0 until length) {
                val time = n.toDouble() / SAMPLE_RATE
                var value = 0.0
                for ((ratio, amplitude, decay) in partials) {
                    val envelope = Math.exp(-time / (decay * DECAY_SECONDS))
                    value += amplitude * envelope * Math.sin(2.0 * Math.PI * FUNDAMENTAL * ratio * time)
                }
                // 0.32 leaves headroom: the partials add up well past 1.0 at t=0.
                val scaled = (value * 0.32 * Short.MAX_VALUE).toInt()
                buffer[offset + n] = scaled.coerceIn(-32768, 32767).toShort()
            }
        }
        return buffer
    }

    private fun buildTrack(sizeBytes: Int): AudioTrack? = runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // The alarm stream, not the notification stream: a clock chime must be audible
                    // with the phone on silent, which is the whole point of a clock.
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setLegacyStreamType(AudioManager.STREAM_ALARM)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            // Static mode still wants the size up front, and the size is the rendered waveform.
            .setBufferSizeInBytes(sizeBytes.coerceAtLeast(2))
            .build()
    }.getOrNull()
}
