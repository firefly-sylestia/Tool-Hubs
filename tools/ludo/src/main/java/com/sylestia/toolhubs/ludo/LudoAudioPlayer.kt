package com.sylestia.toolhubs.ludo

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper

/** Bundled CC0 board-game sounds, loaded once and played locally through SoundPool. */
internal object LudoAudioPlayer {
    private var pool: SoundPool? = null
    private val sounds = mutableMapOf<Int, Int>()
    private val ready = mutableSetOf<Int>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize(context: Context) {
        if (pool != null) return
        val player = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .build()
        pool = player
        player.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) ready.add(sampleId)
        }
        sounds[1] = player.load(context.applicationContext, R.raw.ludo_dice_roll, 1)
        sounds[2] = player.load(context.applicationContext, R.raw.ludo_piece_move, 1)
        sounds[3] = player.load(context.applicationContext, R.raw.ludo_capture, 1)
        sounds[4] = player.load(context.applicationContext, R.raw.ludo_dice_throw, 1)
    }

    fun play(context: Context, kind: Int) {
        initialize(context)
        val soundId = sounds[kind] ?: return
        if (soundId in ready) pool?.play(soundId, 1f, 1f, 1, 0, 1f)
        if (kind == 1) {
            mainHandler.postDelayed({
                val throwId = sounds[4]
                if (throwId != null && throwId in ready) pool?.play(throwId, .9f, .9f, 1, 0, 1f)
            }, 220)
        }
    }
}
