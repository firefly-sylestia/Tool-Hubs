package com.sylestia.toolhubs.ludo

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.ToolHubsTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ToolHubsTheme { LudoApp() } }
    }
}

private val Ink = Color(0xFF202438)
private val Muted = Color(0xFF85899B)
private val CanvasBg = Color(0xFFF6F4F0)
private val SurfaceWhite = Color(0xFFFFFFFF)

@Composable
private fun LudoApp() {
    var screen by remember { mutableStateOf(false) }
    var playerCount by remember { mutableIntStateOf(4) }
    var humanCount by remember { mutableIntStateOf(2) }
    var sound by remember { mutableStateOf(true) }
    var vibration by remember { mutableStateOf(true) }
    var game by remember { mutableStateOf(LudoEngine.newGame(playerCount, humanCount)) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { LudoAudioPlayer.initialize(context) }

    LaunchedEffect(game.currentPlayer, game.dice, game.message, game.gameOver, screen) {
        if (screen && !game.gameOver && game.players.getOrNull(game.currentPlayer)?.isBot == true) {
            delay(850)
            game = LudoEngine.botMove(game)
            feedback(context, sound, vibration, if (game.gameOver) 4 else if (game.message.contains("captured")) 3 else 2)
        }
    }

    Surface(color = CanvasBg) {
        if (!screen) {
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("TOOL HUBS  /  PLAY", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("Ludo Club", color = Ink, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFFFE8B7)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Casino, null, tint = Color(0xFFB77B20), modifier = Modifier.size(26.dp))
                    }
                }
                Card(shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF292E49)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("ROLL INTO\nGOOD TIMES.", color = Color.White, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black)
                        Text("The classic board game. Your rules. Your people. No internet needed.", color = Color(0xFFD7D9E4), fontSize = 14.sp, lineHeight = 20.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(Color(0xFFE85D75), Color(0xFF36B9A0), Color(0xFFFFC34D), Color(0xFF6489F5), Color(0xFFAB78E8), Color(0xFFEF8A45)).forEach { c ->
                                Box(Modifier.size(25.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(Brush.verticalGradient(listOf(c.copy(alpha = .8f), c))))
                            }
                        }
                    }
                }
                Text("GAME SETUP", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                SettingCard(title = "Players", subtitle = "Choose how many seats join the board") {
                    ChoiceRow((2..6).toList(), playerCount) { playerCount = it; humanCount = humanCount.coerceAtMost(it) }
                }
                SettingCard(title = "People playing", subtitle = "Remaining seats are clever local bots") {
                    ChoiceRow((1..playerCount).toList(), humanCount) { humanCount = it }
                }
                SettingCard(title = "Game feel", subtitle = "Sound and haptics can be changed any time") {
                    SettingToggle("Sound effects", sound, { sound = it }, Icons.Rounded.VolumeUp)
                    SettingToggle("Vibration", vibration, { vibration = it }, Icons.Rounded.Vibration)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    game = LudoEngine.newGame(playerCount, humanCount)
                    screen = true
                    feedback(context, sound, vibration, 1)
                }, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(19.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292E49))) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start a new game", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Text("100% OFFLINE  •  NO ACCOUNTS  •  NO ADS", color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        } else {
            GameScreen(
                state = game,
                sound = sound,
                vibration = vibration,
                onBack = { screen = false },
                onRoll = { game = LudoEngine.roll(game); feedback(context, sound, vibration, 1) },
                onMove = { game = LudoEngine.move(game, it); feedback(context, sound, vibration, if (game.gameOver) 4 else if (game.message.contains("captured")) 3 else 2) },
                onRestart = { game = LudoEngine.newGame(playerCount, humanCount) }
            )
        }
    }
}

@Composable
private fun SettingCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = SurfaceWhite), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
            content()
        }
    }
}

@Composable
private fun ChoiceRow(values: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        values.forEach { value ->
            val active = value == selected
            Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(13.dp))
                .background(if (active) Ink else Color(0xFFF1F1F4)).clickable { onSelect(value) },
                contentAlignment = Alignment.Center) {
                Text("$value", color = if (active) Color.White else Ink, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(19.dp))
        Text(label, color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun GameScreen(state: LudoState, sound: Boolean, vibration: Boolean, onBack: () -> Unit, onRoll: () -> Unit, onMove: (Int) -> Unit, onRestart: () -> Unit) {
    val current = state.players[state.currentPlayer]
    val options = state.dice?.let { LudoEngine.movableTokens(current, it) }.orEmpty()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, null, tint = Ink)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("LUDO CLUB", color = Muted, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
                Text("The board is yours", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text("LOCAL GAME", color = Color(0xFF268B73), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
            state.players.forEach { player ->
                val active = player.id == current.id
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (active) Color(player.color) else Color.White).padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (active) Color.White else Color(player.color)))
                    Text(if (player.isBot) "BOT" else "P${player.id + 1}", color = if (active) Color.White else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFECE9E2)), modifier = Modifier.fillMaxWidth()) {
            Board(state, Modifier.fillMaxWidth().aspectRatio(1f).padding(10.dp))
        }
        Card(shape = RoundedCornerShape(23.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (current.isBot) "${current.name}'s turn" else "${current.name}'s turn", color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(state.message, color = Muted, fontSize = 12.sp)
                }
                if (state.dice == null) {
                    Button(onClick = onRoll, enabled = !current.isBot && !state.gameOver, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292E49))) {
                        Icon(Icons.Rounded.Casino, null)
                        Spacer(Modifier.width(6.dp))
                        Text("ROLL")
                    }
                } else {
                    DiceFace(state.dice!!, Modifier.size(58.dp))
                }
            }
        }
        if (state.dice != null && !current.isBot && options.isNotEmpty()) {
            Text("CHOOSE A PIECE TO MOVE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                options.forEach { token ->
                    OutlinedButton(onClick = { onMove(token) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) {
                        Text("Piece ${token + 1}", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (state.gameOver) {
            Button(onClick = onRestart, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292E49))) {
                Icon(Icons.Rounded.Refresh, null); Text("Play again")
            }
        } else {
            Spacer(Modifier.weight(1f))
            Text("SAFE CELLS ✦  •  SIX TO LAUNCH  •  EXACT FINISH REQUIRED", color = Muted, fontSize = 9.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DiceFace(value: Int, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(targetValue = value * 60f, animationSpec = tween(480), label = "dice-rotation")
    val image = when (value) {
        1 -> R.drawable.die_white_1
        2 -> R.drawable.die_white_2
        3 -> R.drawable.die_white_3
        4 -> R.drawable.die_white_4
        5 -> R.drawable.die_white_5
        else -> R.drawable.die_white_6
    }
    Image(
        painter = painterResource(image),
        contentDescription = "Dice showing $value",
        modifier = modifier.graphicsLayer { rotationZ = rotation }
    )
}

@Composable
private fun Board(state: LudoState, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2, h / 2)
        val radius = min(w, h) * .335f
        drawCircle(Color(0xFFFFFEFC), radius = min(w, h) * .47f, center = center)
        drawCircle(Color(0xFFE4E0D8), radius = radius + 4.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
        val seats = (0..5).toList()
        seats.forEach { seat ->
            val angle = -PI / 2 + seat * PI / 3
            val color = Color(LudoEngine.palette[seat])
            val base = Offset(center.x + cos(angle).toFloat() * radius * 1.28f, center.y + sin(angle).toFloat() * radius * 1.28f)
            drawCircle(color.copy(alpha = .15f), radius * .24f, base)
            drawCircle(color.copy(alpha = .6f), radius * .24f, base, style = Stroke(2.dp.toPx()))
        }
        repeat(60) { index ->
            val angle = -PI / 2 + index * (2 * PI / 60)
            val p = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
            val seat = index / 10
            val color = if (index % 10 == 0) Color(LudoEngine.palette[seat]) else Color(0xFFF1F0ED)
            drawCircle(color, radius = min(w,h) * .021f, center = p)
            drawCircle(Color(0xFFD8D5CF), radius = min(w,h) * .021f, center = p, style = Stroke(1.dp.toPx()))
        }
        state.players.forEach { player ->
            val angle = -PI / 2 + player.seat * PI / 3
            val color = Color(player.color)
            player.tokens.forEachIndexed { tokenIndex, progress ->
                val point = if (progress == -1) {
                    val baseRadius = radius * 1.28f
                    val a = angle + (if (tokenIndex % 2 == 0) -.22 else .22)
                    val rr = baseRadius + (if (tokenIndex >= 2) radius * .13f else -radius * .13f)
                    Offset(center.x + cos(a).toFloat() * rr, center.y + sin(a).toFloat() * rr)
                } else if (progress < 50) {
                    val index = (player.seat * 10 + progress) % 60
                    val a = -PI / 2 + index * (2 * PI / 60)
                    Offset(center.x + cos(a).toFloat() * radius, center.y + sin(a).toFloat() * radius)
                } else {
                    val rr = radius * (1f - (progress - 49) * .16f)
                    Offset(center.x + cos(angle).toFloat() * rr, center.y + sin(angle).toFloat() * rr)
                }
                val tokenRadius = min(w,h) * .026f
                drawCircle(Color.Black.copy(alpha = .12f), tokenRadius * 1.16f, point + Offset(0f, 2.dp.toPx()))
                drawCircle(Brush.verticalGradient(listOf(color.copy(alpha = .72f), color, color.copy(alpha = .82f))), tokenRadius, point)
                drawCircle(Color.White.copy(alpha = .9f), tokenRadius, point, style = Stroke(1.5.dp.toPx()))
                drawCircle(Color.White.copy(alpha = .55f), tokenRadius * .24f, point + Offset(-tokenRadius*.3f, -tokenRadius*.32f))
            }
        }
        drawCircle(Color(0xFF292E49), radius = min(w,h) * .055f, center = center)
        drawCircle(Color.White, radius = min(w,h) * .043f, center = center, style = Stroke(2.dp.toPx()))
        drawCircle(Color(0xFFFFC34D), radius = min(w,h) * .012f, center = center)
    }
}

@Suppress("DEPRECATION")
private fun feedback(context: Context, sound: Boolean, vibration: Boolean, type: Int) {
    if (sound) LudoAudioPlayer.play(context, type)
    if (vibration) runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(22, VibrationEffect.DEFAULT_AMPLITUDE))
        else vibrator.vibrate(22)
    }
}
