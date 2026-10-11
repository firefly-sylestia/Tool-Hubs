package com.sylestia.toolhubs.chime.ring

import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes as MediaAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.sylestia.toolhubs.chime.ChimeNotifications
import com.sylestia.toolhubs.chime.ChimeStore
import com.sylestia.toolhubs.chime.DueItem
import com.sylestia.toolhubs.chime.schedule.ChimeScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The ringing itself: a foreground service that owns the sound, the vibration and the alarm
 * notification.
 *
 * It is a service rather than a few lines in the receiver because an alarm lasts minutes, and a
 * `BroadcastReceiver` is killed the moment `onReceive` returns. Being foreground is also what keeps
 * the process alive while the screen is off, which is the only state that matters for an alarm.
 *
 * The sound is an `ExoPlayer` rather than a `Ringtone` for one reason: the volume ramp. A `Ringtone`
 * can only be on or off, and an alarm that starts at full volume on a bedside table is a design
 * fault. The player's own gain climbs from near-silence to the alarm stream's current level, and the
 * stream volume the user chose is never touched.
 */
class RingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var rampJob: Job? = null
    private var due: DueItem? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ChimeNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRinging()
            return START_NOT_STICKY
        }
        val kind = runCatching {
            DueItem.Kind.valueOf(intent?.getStringExtra(EXTRA_KIND) ?: DueItem.Kind.ALARM.name)
        }.getOrDefault(DueItem.Kind.ALARM)
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        if (id < 0L) {
            stopSelf()
            return START_NOT_STICKY
        }
        val item = ChimeScheduler(this).dueItem(kind, id)
        if (item == null) {
            // Deleted between firing and starting: nothing to ring, and never a crash.
            stopSelf()
            return START_NOT_STICKY
        }
        startRinging(item)
        return START_NOT_STICKY
    }

    private fun startRinging(item: DueItem) {
        due = item
        startForeground(ChimeNotifications.RINGING_ID, ChimeNotifications.ringing(this, item))
        acquireWakeLock()
        startSound(item)
        if (item.vibrate) startVibration()
        showRingingScreen()

        val timeoutMinutes = ChimeStore(this).settings().ringTimeoutMinutes
        scope.launch {
            delay(timeoutMinutes * 60_000L)
            // Never ring forever: an alarm nobody answered becomes a missed alarm, and a repeating
            // one has already been re-armed for its next turn by the receiver.
            if (due != null) stopRinging()
        }
    }

    private fun startSound(item: DueItem) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        val exo = ExoPlayer.Builder(this).build()
        exo.setAudioAttributes(
            MediaAttributes.Builder()
                .setUsage(C.USAGE_ALARM)
                .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
                .build(),
            true,
        )
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.volume = 0f
        exo.prepare()
        exo.play()
        player = exo

        val rampMillis = ChimeStore(this).settings().rampSeconds * 1000L
        rampJob?.cancel()
        rampJob = scope.launch {
            if (rampMillis <= 0L) {
                exo.volume = 1f
                return@launch
            }
            val steps = 40
            repeat(steps + 1) { step ->
                exo.volume = 0.08f + 0.92f * (step.toFloat() / steps)
                delay(rampMillis / steps)
            }
        }
    }

    private fun startVibration() {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        // Two pulses then a pause, repeating: recognisable as this app's alarm, and gentler on a
        // bedside table than one long buzz.
        val pattern = longArrayOf(0, 420, 240, 420, 900)
        device.vibrate(VibrationEffect.createWaveform(pattern, 0))
        vibrator = device
    }

    /**
     * Puts the ringing screen up directly when the phone is already awake and unlocked.
     *
     * A full-screen intent is only honoured while the screen is off or the device is locked; on an
     * unlocked phone it arrives as a heads-up banner, which is how an alarm gets missed. A service
     * may start an activity in this case because the user is already using the device.
     */
    private fun showRingingScreen() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        val interactive = power?.isInteractive == true
        val locked = keyguard?.isKeyguardLocked == true
        val item = due ?: return
        if (!interactive || locked) return
        runCatching {
            startActivity(RingingActivity.intent(this, item).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun stopRinging() {
        rampJob?.cancel()
        rampJob = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        rampJob?.cancel()
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java) ?: return
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
        lock.setReferenceCounted(false)
        // A ceiling rather than a duration: the service releases it as soon as it stops, and the
        // timeout only matters if the process is killed without running onDestroy.
        lock.acquire(30 * 60_000L)
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    companion object {
        private const val WAKE_TAG = "chime:ring"
        const val ACTION_STOP = "com.sylestia.toolhubs.chime.ACTION_STOP_RINGING"
        const val EXTRA_KIND = "kind"
        const val EXTRA_ID = "id"

        fun start(context: Context, kind: DueItem.Kind, id: Long) {
            val intent = Intent(context, RingService::class.java).apply {
                putExtra(EXTRA_KIND, kind.name)
                putExtra(EXTRA_ID, id)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RingService::class.java).apply { action = ACTION_STOP })
        }
    }
}
