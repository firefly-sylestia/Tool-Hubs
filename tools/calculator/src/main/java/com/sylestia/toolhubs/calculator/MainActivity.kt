package com.sylestia.toolhubs.calculator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.*
import kotlin.math.abs
import kotlin.math.sqrt
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ToolHubsTheme { CalculatorScreen() } }
    }
}

private enum class CalcOperator(val symbol: String) { ADD("+"), SUBTRACT("−"), MULTIPLY("×"), DIVIDE("÷") }
private data class Calculation(val expression: String, val result: String)

@Composable
private fun GlassPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.clip(RoundedCornerShape(50.dp)).background(Brush.verticalGradient(listOf(Color.White.copy(.78f), Color.White.copy(.48f)))).background(CurioSurface.copy(.42f)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun CalculatorScreen() {
    var display by rememberSaveable { mutableStateOf("0") }
    var expression by rememberSaveable { mutableStateOf("") }
    var accumulator by rememberSaveable { mutableStateOf<Double?>(null) }
    var pendingOperator by rememberSaveable { mutableStateOf<CalcOperator?>(null) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var lastOperand by rememberSaveable { mutableStateOf<Double?>(null) }
    var lastOperator by rememberSaveable { mutableStateOf<CalcOperator?>(null) }
    var history by remember { mutableStateOf(emptyList<Calculation>()) }
    var showHistory by remember { mutableStateOf(false) }
    var showFunctions by remember { mutableStateOf(false) }
    var selectedHistory by remember { mutableStateOf<Calculation?>(null) }
    val view = LocalView.current
    val context = LocalContext.current

    fun value(): Double = display.toDoubleOrNull() ?: 0.0
    fun format(n: Double): String {
        if (!n.isFinite()) return "Error"
        if (abs(n) < 1e-10) return "0"
        if (abs(n - n.toLong()) < 1e-10) return n.toLong().toString()
        return String.format(Locale.US, "%.10f", n).trimEnd('0').trimEnd('.')
    }
    fun calculate(a: Double, b: Double, op: CalcOperator) = when (op) {
        CalcOperator.ADD -> a + b; CalcOperator.SUBTRACT -> a - b; CalcOperator.MULTIPLY -> a * b; CalcOperator.DIVIDE -> if (b == 0.0) Double.NaN else a / b
    }
    fun tap() { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    fun clear() { tap(); display = "0"; expression = ""; accumulator = null; pendingOperator = null; typing = false; lastOperand = null; lastOperator = null }
    fun input(s: String) {
        tap(); if (display == "Error") { display = "0"; expression = "" }
        display = if (!typing) { if (s == ".") "0." else s } else when { s == "." && display.contains(".") -> display; display == "0" && s != "." -> s; display.length >= 16 -> display; else -> display + s }
        typing = true
    }
    fun chooseOperator(next: CalcOperator) {
        tap(); if (display == "Error") return
        val current = value()
        val result = if (accumulator != null && pendingOperator != null && typing) calculate(accumulator!!, current, pendingOperator!!) else current
        if (!result.isFinite()) { display = "Error"; accumulator = null; pendingOperator = null; return }
        display = format(result); accumulator = result; pendingOperator = next; typing = false; expression = "${format(result)} ${next.symbol}"
    }
    fun equals() {
        tap()
        val a = accumulator
        val op = pendingOperator
        val b = value()
        val actualOp = op ?: lastOperator
        val actualA = a ?: if (lastOperand != null && actualOp != null) calculate(value(), lastOperand!!, actualOp) else null
        if (actualA == null || actualOp == null) return
        val operand = if (a == null) lastOperand ?: b else b
        val raw = calculate(actualA, operand, actualOp)
        val result = format(raw)
        if (raw.isFinite()) history = (listOf(Calculation("${format(actualA)} ${actualOp.symbol} ${format(operand)}", result)) + history).take(40)
        display = result; expression = ""; accumulator = null; pendingOperator = null; typing = false; lastOperand = operand; lastOperator = actualOp
    }
    fun backspace() { tap(); if (typing) display = if (display.length <= 1 || display == "-0") "0" else display.dropLast(1) }
    fun percent() { tap(); display = format(value() / 100.0); typing = true }
    fun sign() { tap(); if (display != "0" && display != "Error") display = if (display.startsWith("-")) display.drop(1) else "-$display" }
    fun function(name: String) {
        tap(); val n = value(); val result = when (name) { "x²" -> n * n; "√" -> if (n >= 0) sqrt(n) else Double.NaN; "1/x" -> if (n != 0.0) 1.0 / n else Double.NaN; else -> n }
        display = format(result); typing = false
    }
    fun copy(text: String) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Calculator result", text)); tap() }

    Column(Modifier.fillMaxSize().background(CurioCream).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(66.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("CALCULATOR", color = CurioRoseDark, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Text("Everyday arithmetic", color = CurioInk, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold) }
            GlassPill(Modifier.clickable { tap(); showHistory = true }) { Icon(Icons.Outlined.History, null, tint = CurioInk, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("History", color = CurioInk, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }

        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Bottom) {
            AnimatedContent(targetState = expression, label = "expression") { value -> Text(if (value.isEmpty()) "Ready when you are" else value, color = CurioRoseDark.copy(.72f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (display != "0" && display != "Error") Icon(Icons.Outlined.ContentCopy, "Copy result", tint = CurioRoseDark.copy(.55f), modifier = Modifier.size(17.dp).clickable { copy(display) })
                Spacer(Modifier.width(9.dp))
                Text(display, color = CurioInk, fontSize = 54.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.animateContentSize(tween(180, easing = FastOutSlowInEasing)))
            }
        }

        if (showFunctions) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("√", "x²", "1/x").forEach { key ->
                    Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(50.dp)).background(CurioSurface.copy(.65f)).clickable { function(key) }, contentAlignment = Alignment.Center) { Text(key, color = CurioInk, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val keys = listOf(listOf("AC", "±", "%", "÷"), listOf("7", "8", "9", "×"), listOf("4", "5", "6", "−"), listOf("1", "2", "3", "+"), listOf("⌫", "0", ".", "="))
        keys.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { key ->
                    val operatorKey = key in listOf("÷", "×", "−", "+"); val equalsKey = key == "="; val utility = key in listOf("AC", "±", "%", "⌫")
                    val pressed = remember { mutableStateOf(false) }; val scale by animateFloatAsState(if (pressed.value) .93f else 1f, tween(90), label = "keyScale")
                    val bg = when { equalsKey -> CurioInk; operatorKey -> CurioRoseWood; utility -> CurioSurface.copy(.78f); else -> Color.White.copy(.68f) }; val fg = if (equalsKey || operatorKey) Color.White else CurioInk
                    Box(Modifier.weight(1f).aspectRatio(1.35f).graphicsLayer(scaleX = scale, scaleY = scale).clip(RoundedCornerShape(24.dp)).background(bg).clickable {
                        pressed.value = true
                        when (key) { "AC" -> clear(); "±" -> sign(); "%" -> percent(); "⌫" -> backspace(); "." -> input("."); "=" -> equals(); "÷" -> chooseOperator(CalcOperator.DIVIDE); "×" -> chooseOperator(CalcOperator.MULTIPLY); "−" -> chooseOperator(CalcOperator.SUBTRACT); "+" -> chooseOperator(CalcOperator.ADD); else -> input(key) }
                        pressed.value = false
                    }, contentAlignment = Alignment.Center) {
                        if (key == "⌫") Icon(Icons.Outlined.Backspace, "Backspace", tint = fg, modifier = Modifier.size(22.dp)) else Text(key, color = fg, fontSize = if (utility) 19.sp else 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        GlassPill(Modifier.align(Alignment.CenterHorizontally).clickable { tap(); showFunctions = !showFunctions }) { Text(if (showFunctions) "Hide functions" else "More functions", color = CurioInk, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(6.dp))
    }

    if (showHistory) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(onDismissRequest = { showHistory = false }, containerColor = CurioSurface.copy(.97f), contentWindowInsets = { WindowInsets.navigationBars }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Calculations", color = CurioInk, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold); Text("Your recent results", color = CurioRoseDark.copy(.7f), fontSize = 13.sp) }
                    if (history.isNotEmpty()) Text("Clear", color = CurioRoseWood, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50.dp)).clickable { tap(); history = emptyList() }.padding(10.dp))
                }
                Spacer(Modifier.height(14.dp))
                if (history.isEmpty()) Text("No calculations yet. Your results will collect here.", color = CurioRoseDark, modifier = Modifier.padding(vertical = 36.dp))
                history.forEach { item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White.copy(.45f)).clickable { tap(); selectedHistory = item }.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(item.expression, color = CurioRoseDark, fontSize = 14.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(3.dp)); Text(item.result, color = CurioInk, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold) }
                        Icon(Icons.Outlined.Replay, "Reuse calculation", tint = CurioRoseDark.copy(.55f), modifier = Modifier.size(20.dp).clickable { display = item.result; typing = true; showHistory = false; tap() })
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    selectedHistory?.let { item ->
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(onDismissRequest = { selectedHistory = null }, containerColor = CurioSurface.copy(.98f), contentWindowInsets = { WindowInsets.navigationBars }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
                Text("Result", color = CurioRoseDark, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Text(item.expression, color = CurioInk, fontSize = 19.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 5.dp))
                Text(item.result, color = CurioInk, fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    GlassPill(Modifier.clickable { copy(item.result) }) { Icon(Icons.Outlined.ContentCopy, null, tint = CurioInk, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Copy", color = CurioInk, fontWeight = FontWeight.Bold) }
                    GlassPill(Modifier.clickable { display = item.result; typing = true; selectedHistory = null; showHistory = false; tap() }) { Icon(Icons.Outlined.Replay, null, tint = CurioInk, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Use result", color = CurioInk, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
