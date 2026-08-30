package com.sylestia.toolhubs.calculator

import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.*
import kotlin.math.abs
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { ToolHubsTheme { CalculatorScreen() } } }
}

private enum class CalcOperator(val symbol: String) { ADD("+"), SUBTRACT("−"), MULTIPLY("×"), DIVIDE("÷") }
private data class Calculation(val expression: String, val result: String)

@Composable
private fun GlassSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(RoundedCornerShape(50.dp)).background(Brush.verticalGradient(listOf(Color.White.copy(.72f), Color.White.copy(.44f)))).background(CurioSurface.copy(.42f)).animateContentSize().padding(1.dp), content = content)
}

@Composable
private fun CalculatorScreen() {
    var display by rememberSaveable { mutableStateOf("0") }
    var expression by rememberSaveable { mutableStateOf("") }
    var accumulator by rememberSaveable { mutableStateOf<Double?>(null) }
    var pendingOperator by rememberSaveable { mutableStateOf<CalcOperator?>(null) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<Calculation>()) }
    var showHistory by remember { mutableStateOf(false) }
    val view = LocalView.current

    fun value() = display.toDoubleOrNull() ?: 0.0
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
    fun clear() { tap(); display = "0"; expression = ""; accumulator = null; pendingOperator = null; typing = false }
    fun input(s: String) {
        tap(); if (display == "Error") { display = "0"; expression = "" }
        display = if (!typing) { if (s == ".") "0." else s } else when { s == "." && display.contains(".") -> display; display == "0" && s != "." -> s; display.length >= 16 -> display; else -> display + s }
        typing = true
    }
    fun chooseOperator(next: CalcOperator) {
        tap(); if (display == "Error") return
        val current = value(); val result = if (accumulator != null && pendingOperator != null && typing) calculate(accumulator!!, current, pendingOperator!!) else current
        if (!result.isFinite()) { display = "Error"; accumulator = null; pendingOperator = null; return }
        display = format(result); accumulator = result; pendingOperator = next; typing = false; expression = "${format(result)} ${next.symbol}"
    }
    fun equals() {
        tap(); val a = accumulator ?: return; val op = pendingOperator ?: return; val b = value(); val result = format(calculate(a, b, op))
        history = (listOf(Calculation("${format(a)} ${op.symbol} ${format(b)}", result)) + history).take(30)
        display = result; accumulator = null; pendingOperator = null; expression = ""; typing = false
    }
    fun backspace() { tap(); if (typing) display = if (display.length <= 1 || display == "-0") "0" else display.dropLast(1) }
    fun percent() { tap(); display = format(value() / 100.0); typing = true }
    fun sign() { tap(); if (display != "0" && display != "Error") display = if (display.startsWith("-")) display.drop(1) else "-$display" }

    Column(Modifier.fillMaxSize().background(CurioCream).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(66.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("CALCULATOR", color = CurioRoseDark, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Text("Everyday arithmetic", color = CurioInk, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold) }
            GlassSurface(Modifier.clickable { tap(); showHistory = true }) { Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.History, null, tint = CurioInk, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("History", color = CurioInk, fontSize = 13.sp, fontWeight = FontWeight.Bold) } }
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 5.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Bottom) {
            Text(if (expression.isEmpty()) "Ready when you are" else expression, color = CurioRoseDark.copy(.75f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(7.dp)); Text(display, color = CurioInk, fontSize = 54.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.animateContentSize(tween(180, easing = FastOutSlowInEasing)))
        }
        Spacer(Modifier.height(18.dp))
        val keys = listOf(listOf("AC", "±", "%", "÷"), listOf("7", "8", "9", "×"), listOf("4", "5", "6", "−"), listOf("1", "2", "3", "+"), listOf("⌫", "0", ".", "="))
        keys.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { key ->
                    val operatorKey = key in listOf("÷", "×", "−", "+"); val equalsKey = key == "="; val utility = key in listOf("AC", "±", "%", "⌫")
                    val pressed = remember { mutableStateOf(false) }; val scale by animateFloatAsState(if (pressed.value) .94f else 1f, tween(90), label = "keyScale")
                    val bg = when { equalsKey -> CurioInk; operatorKey -> CurioRoseWood; utility -> CurioSurface.copy(.76f); else -> Color.White.copy(.66f) }; val fg = if (equalsKey || operatorKey) Color.White else CurioInk
                    Box(Modifier.weight(1f).aspectRatio(1.35f).clip(RoundedCornerShape(24.dp)).background(bg).clickable {
                        pressed.value = true
                        when (key) { "AC" -> clear(); "±" -> sign(); "%" -> percent(); "⌫" -> backspace(); "." -> input("."); "=" -> equals(); "÷" -> chooseOperator(CalcOperator.DIVIDE); "×" -> chooseOperator(CalcOperator.MULTIPLY); "−" -> chooseOperator(CalcOperator.SUBTRACT); "+" -> chooseOperator(CalcOperator.ADD); else -> input(key) }
                        pressed.value = false
                    }, contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale), contentAlignment = Alignment.Center) { if (key == "⌫") Icon(Icons.Outlined.Backspace, null, tint = fg, modifier = Modifier.size(22.dp)) else Text(key, color = fg, fontSize = if (utility) 19.sp else 24.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    if (showHistory) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(onDismissRequest = { showHistory = false }, containerColor = CurioSurface.copy(.96f), contentWindowInsets = { WindowInsets.navigationBars }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("History", color = CurioInk, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f)); if (history.isNotEmpty()) Text("Clear", color = CurioRoseWood, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50.dp)).clickable { tap(); history = emptyList() }.padding(9.dp)) }
                Spacer(Modifier.height(10.dp)); if (history.isEmpty()) Text("Your calculations will appear here.", color = CurioRoseDark, modifier = Modifier.padding(vertical = 30.dp))
                history.forEach { item -> Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) { Text(item.expression, color = CurioRoseDark, fontSize = 14.sp); Text(item.result, color = CurioInk, fontSize = 21.sp, fontWeight = FontWeight.Bold) } }
            }
        }
    }
}
