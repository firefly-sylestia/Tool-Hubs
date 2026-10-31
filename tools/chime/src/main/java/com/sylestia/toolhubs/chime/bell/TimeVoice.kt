package com.sylestia.toolhubs.chime.bell

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale

/**
 * "It's twenty past seven."
 *
 * The wording is written out rather than handed to the engine as digits, because an engine reading
 * "07:20" says "oh seven colon two zero" on most devices. The grammar is the small part of this people
 * actually notice.
 *
 * An engine is created per announcement and shut down again. It used to be shut down on a fixed
 * 2.6-second timer, which cut the sentence off mid-word whenever initialisation was slow — the reason
 * the spoken time did not always match what it said it would. It now waits for the engine to report
 * that it finished, with a ceiling so a broken engine cannot hold the service open.
 */
object TimeVoice {

    /**
     * Speaks the current time and returns once the sentence has been spoken.
     *
     * [hour24] must be the same choice the clock face is using. It used to be dropped on the floor
     * here, so a phone set to 24-hour still heard "half past seven in the evening" — the spoken time
     * disagreeing with the number above it.
     */
    suspend fun announce(context: Context, hour24: Boolean) {
        speak(context, phrase(ZonedDateTime.now(), hour24))
    }

    /** Reads a specific time, for the preview button in settings. */
    suspend fun preview(context: Context, millis: Long, hour24: Boolean) {
        val time = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
        speak(context, phrase(time, hour24))
    }

    /**
     * Reads the clock face aloud.
     *
     * [hour24] only changes the hour phrase: in 12-hour style the hour is always read as a clock
     * hour, and in 24-hour style "seventeen hundred" is how the number reads, so the phrase follows
     * the same setting the clock face does.
     */
    fun phrase(time: ZonedDateTime, hour24: Boolean = false): String = phraseFor(time.toLocalTime(), hour24)

    fun phraseFor(time: LocalTime, hour24: Boolean = false): String {
        val minute = time.minute
        if (hour24) {
            return when (minute) {
                0 -> "It's ${time.hour} hundred hours"
                else -> "It's ${time.hour} ${minute.toString().padStart(2, '0')}"
            }
        }
        val hour24Value = time.hour
        val clockHour = if (hour24Value % 12 == 0) 12 else hour24Value % 12
        val nextHour = if (clockHour == 12) 1 else clockHour + 1
        val partOfDay = when {
            hour24Value < 12 -> "in the morning"
            hour24Value < 18 -> "in the afternoon"
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

    private suspend fun speak(context: Context, phrase: String) {
        val engine = createEngine(context) ?: return
        engine.setLanguage(Locale.getDefault())
        val finished = CompletableDeferred<Unit>()
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    finished.complete(Unit)
                }

                @Deprecated("Required by the platform listener; the error code overload is used instead.")
                override fun onError(utteranceId: String?) {
                    finished.complete(Unit)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    finished.complete(Unit)
                }
            }
        )
        runCatching {
            engine.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "chime-time")
        }
        // A ceiling, not a timer: normally this returns the instant the last word lands.
        withTimeoutOrNull(20_000) { finished.await() }
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
    }

    private suspend fun createEngine(context: Context): TextToSpeech? {
        // The engine only calls back on its own thread, so initialisation is bridged with a polling
        // wait rather than a callback the caller would have to be structured around.
        var ready = false
        var failed = false
        val engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) ready = true else failed = true
        }
        var waited = 0
        while (!ready && !failed && waited < 3000) {
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
