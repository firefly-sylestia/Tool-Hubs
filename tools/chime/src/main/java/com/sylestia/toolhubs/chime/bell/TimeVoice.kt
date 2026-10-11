package com.sylestia.toolhubs.chime.bell

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale

/**
 * "It's twenty past seven."
 *
 * The wording is spelled out in words rather than handed to the engine as digits, because a
 * text-to-speech engine reading "07:20" says "oh seven colon two zero" on most devices. The grammar
 * is the small part of this that people actually notice.
 *
 * An engine is created per announcement and shut down again. Holding one open for the life of the
 * process would be cheaper, but only if the process were always alive — and it is the announcement
 * that is rare, not the process, so paying a few hundred milliseconds at announcement time is the
 * better trade. It is also the only shape that works from a broadcast receiver.
 */
object TimeVoice {

    /** Speaks the current time and returns once the engine has been started and handed the text. */
    suspend fun announce(context: Context) {
        val phrase = phrase(ZonedDateTime.now())
        val engine = createEngine(context) ?: return
        engine.setLanguage(Locale.getDefault())
        runCatching {
            engine.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "chime-time-${System.currentTimeMillis()}")
        }
        // Speak is asynchronous; give it time to finish before the caller shuts the engine down.
        delay(2600)
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
    }

    /** Reads the time once for a preview button, so the user can hear it before relying on it. */
    suspend fun preview(context: Context, millis: Long) {
        val phrase = phrase(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))
        val engine = createEngine(context) ?: return
        engine.setLanguage(Locale.getDefault())
        runCatching { engine.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "chime-preview") }
        delay(2600)
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
    }

    /**
     * The spoken form of [time].
     *
     * Minutes are read as past/to the hour, which is how a person says a time out loud, and the hour
     * is read as a clock reads it — twelve, not zero, so midnight is "twelve o'clock" and not
     * "zero hundred".
     */
    fun phrase(time: ZonedDateTime): String = phraseFor(time.toLocalTime())

    fun phraseFor(time: LocalTime): String {
        val hour24 = time.hour
        val clockHour = if (hour24 % 12 == 0) 12 else hour24 % 12
        val nextHour = if (clockHour == 12) 1 else clockHour + 1
        val minute = time.minute
        val partOfDay = when {
            hour24 < 12 -> "in the morning"
            hour24 < 18 -> "in the afternoon"
            else -> "in the evening"
        }
        return when (minute) {
            0 -> "It's $clockHour o'clock $partOfDay"
            15 -> "It's quarter past $clockHour $partOfDay"
            30 -> "It's half past $clockHour $partOfDay"
            45 -> "It's quarter to $nextHour $partOfDay"
            in 1..29 -> "It's $minute past $clockHour $partOfDay"
            else -> "It's ${60 - minute} to $nextHour $partOfDay"
        }
    }

    private suspend fun createEngine(context: Context): TextToSpeech? {
        // The engine only calls back on its own thread, so the initialisation is bridged with a
        // polling wait rather than a callback the caller would have to be structured around.
        var ready = false
        var failed = false
        val engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) ready = true else failed = true
        }
        var waited = 0
        while (!ready && !failed && waited < 2500) {
            delay(50)
            waited += 50
        }
        if (!ready) {
            runCatching { engine.shutdown() }
            return null
        }
        return engine
    }
}
