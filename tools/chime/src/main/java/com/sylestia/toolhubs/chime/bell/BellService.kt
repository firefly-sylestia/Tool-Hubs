package com.sylestia.toolhubs.chime.bell

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Plays one chime, then stops itself.
 *
 * A broadcast receiver has around ten seconds to finish, and a twelve-strike midnight chime with a
 * spoken time after it can outlive that — which is how a clock ends up silent at exactly the hour it
 * was bought for. A started service has no such ceiling, and it needs no foreground type because it
 * lives for a few seconds.
 *
 * The start can still be refused on an OEM build that blocks background service starts, so [start]
 * reports whether it worked and the receiver falls back to doing the watch inside its own window.
 */
class BellService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val strikes = intent?.getIntExtra(EXTRA_STRIKES, 1) ?: 1
        val speak = intent?.getBooleanExtra(EXTRA_SPEAK, false) ?: false
        val hour24 = intent?.getBooleanExtra(EXTRA_HOUR24, false) ?: false
        scope.launch {
            runCatching { BellVoice.strike(this@BellService, strikes) }
            if (speak) runCatching { TimeVoice.announce(this@BellService, hour24) }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STRIKES = "strikes"
        const val EXTRA_SPEAK = "speak"
        const val EXTRA_HOUR24 = "hour24"

        /** Returns false when the system refused the start, so the caller can ring in-line instead. */
        fun start(context: Context, strikes: Int, speak: Boolean, hour24: Boolean): Boolean = runCatching {
            context.startService(
                Intent(context, BellService::class.java).apply {
                    putExtra(EXTRA_STRIKES, strikes)
                    putExtra(EXTRA_SPEAK, speak)
                    putExtra(EXTRA_HOUR24, hour24)
                }
            )
            true
        }.getOrDefault(false)
    }
}
