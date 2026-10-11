package com.sylestia.toolhubs.chime.ring

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.sylestia.toolhubs.designsystem.ToolHubsTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.chime.ChimeFormat
import com.sylestia.toolhubs.chime.DismissMode
import com.sylestia.toolhubs.chime.DueItem
import com.sylestia.toolhubs.chime.schedule.ChimeActionReceiver
import com.sylestia.toolhubs.chime.schedule.ChimeScheduler
import kotlinx.coroutines.delay

/**
 * The screen that owns the alarm until the user answers it.
 *
 * It is deliberately not dismissible by the back gesture or the home button: an alarm you can swipe
 * away in your sleep is a notification, not an alarm. The only ways out are the ones configured for
 * that alarm — a scan, a shake streak, or the buttons — and every one of them goes through
 * [ChimeActionReceiver] so the notification actions and the screen take exactly the same path.
 */
class RingingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val kind = runCatching {
            DueItem.Kind.valueOf(intent.getStringExtra(RingService.EXTRA_KIND) ?: DueItem.Kind.ALARM.name)
        }.getOrDefault(DueItem.Kind.ALARM)
        val id = intent.getLongExtra(RingService.EXTRA_ID, -1L)
        val due = ChimeScheduler(this).dueItem(kind, id)
        if (due == null) {
            // The alarm was deleted while it was ringing: leave quietly rather than show a blank.
            finish()
            return
        }

        // Back must not dismiss. The empty callback swallows the gesture entirely.
        onBackPressedDispatcher.addCallback(this) { }

        setContent {
            ToolHubsTheme {
                RingingScreen(
                    due = due,
                    onSnooze = { answer(ChimeActionReceiver.ACTION_SNOOZE) },
                    onDismiss = { answer(ChimeActionReceiver.ACTION_DISMISS) },
                )
            }
        }
    }

    private fun answer(action: String) {
        sendBroadcast(
            Intent(this, ChimeActionReceiver::class.java).apply {
                this.action = action
                putExtra(ChimeActionReceiver.EXTRA_KIND, intent.getStringExtra(RingService.EXTRA_KIND))
                putExtra(ChimeActionReceiver.EXTRA_ID, intent.getLongExtra(RingService.EXTRA_ID, -1L))
            }
        )
        finish()
    }

    companion object {
        fun intent(context: Context, due: DueItem): Intent =
            Intent(context, RingingActivity::class.java).apply {
                putExtra(RingService.EXTRA_KIND, due.kind.name)
                putExtra(RingService.EXTRA_ID, due.id)
            }
    }
}

private val RingBackground = Color(0xFF0A0D14)
private val RingAccent = Color(0xFF6FE3C4)
private val RingMuted = Color(0xFF8FA3BF)

@Composable
private fun RingingScreen(
    due: DueItem,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    var shakes by remember { mutableIntStateOf(0) }
    val needsShake = due.dismissMode == DismissMode.SHAKE || due.dismissMode == DismissMode.SHAKE_OR_SCAN
    val needsScan = due.dismissMode == DismissMode.SCAN || due.dismissMode == DismissMode.SHAKE_OR_SCAN

    if (needsShake) {
        val watcher = remember { ShakeWatcher(context) { onDismiss() } }
        DisposableEffect(Unit) {
            watcher.start()
            onDispose { watcher.stop() }
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(250)
                shakes = watcher.progress()
            }
        }
    }

    val scan = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) onDismiss()
    }

    Box(Modifier.fillMaxSize().background(RingBackground)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (due.kind == DueItem.Kind.ALARM) "Alarm" else "Reminder",
                    color = RingAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Text(
                    due.title,
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "${due.subtitle} · ringing since ${ChimeFormat.clock(due.triggerAt)}",
                    color = RingMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            PendulumCanvas(seconds = (now / 1000 % 60).toInt())

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                when (due.dismissMode) {
                    DismissMode.SCAN -> Hint("Scan the saved code to stop this alarm")
                    DismissMode.SHAKE -> Hint("Shake the phone to stop this alarm — $shakes of ${ShakeWatcher.DEFAULT_REQUIRED_SHAKES}")
                    DismissMode.SHAKE_OR_SCAN -> Hint("Shake, or scan the code — $shakes of ${ShakeWatcher.DEFAULT_REQUIRED_SHAKES}")
                    DismissMode.BUTTON -> Hint("Tap dismiss to stop this alarm")
                }

                if (needsScan) {
                    RingButton(
                        label = "Scan to dismiss",
                        accent = RingAccent,
                        onClick = {
                            scan.launch(ScanDismissActivity.intent(context, due.scanCode, due.scanCode == null))
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // Snooze stays available in every mode: it is the pressure valve that stops a strict
                // alarm from being uninstalled at 6am, and each snooze shortens the next one anyway.
                RingButton(
                    label = "Snooze ${due.snoozeMinutes} min",
                    accent = RingMuted,
                    onClick = onSnooze,
                    icon = true,
                )
                Spacer(Modifier.height(10.dp))
                RingButton(
                    label = "Dismiss",
                    accent = Color(0xFFFFB4AB),
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = RingMuted,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(bottom = 18.dp),
    )
}

@Composable
private fun RingButton(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    icon: Boolean = false,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon) {
                Icon(Icons.Rounded.Snooze, contentDescription = null, tint = accent)
                Spacer(Modifier.size(8.dp))
            }
            Text(label, color = accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

/**
 * A pendulum that swings with the seconds and a bell that answers it.
 *
 * The arc is drawn from the phase of the current second rather than from an animation clock, so the
 * swing matches the second hand instead of free-running beside it — which is the whole reason a
 * pendulum clock reads as a clock.
 */
@Composable
private fun PendulumCanvas(seconds: Int) {
    val sweep = rememberInfiniteTransition(label = "pendulum")
    val pulse by sweep.animateFloat(
        initialValue = 0.86f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val centre = Offset(size.width / 2f, 36.dp.toPx())
        val length = size.height - 80.dp.toPx()
        val maxAngle = 24.0
        val angle = Math.toRadians(maxAngle * Math.sin(2 * Math.PI * seconds / 60.0))
        val bob = Offset(
            centre.x + (length * Math.sin(angle)).toFloat(),
            centre.y + (length * Math.cos(angle)).toFloat(),
        )
        drawLine(
            color = RingMuted.copy(alpha = 0.7f),
            start = centre,
            end = bob,
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(color = RingAccent, radius = 22.dp.toPx() * pulse, center = bob)
        drawCircle(
            color = RingAccent.copy(alpha = 0.22f),
            radius = 34.dp.toPx() * pulse,
            center = bob,
        )
    }
}
