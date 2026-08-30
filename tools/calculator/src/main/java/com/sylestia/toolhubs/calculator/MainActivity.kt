package com.sylestia.toolhubs.calculator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
private fun CalculatorScreen() {
    var display by rememberSaveable { mutableStateOf("0") }
    var expression by rememberSaveable { mutableStateOf("") }
    var accumulator by rememberSaveable { mutableStateOf<Double?>(null) }
    var pendingOperator by rememberSaveable { mutableStateOf<CalcOperator?>(null) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<Calculation>()) }
    var showHistory by remember { mutableStateOf(false) }

    fun value() = display.toDoubleOrNull() ?: 0.0
    fun format(n: Double): String {
        if (!n.isFinite()) return "Error"
        if (abs(n) < 1e-10) return "0"
        if (abs(n - n.toLong()) < 1e-10) return n.toLong().toString()
        return String.format(Locale.US, "%.10f", n).trimEnd('0').trimEnd('.')
    }
    fun calculate(a: Double, b: Double, op: CalcOperator) = when (op) {
        CalcOperator.ADD -> a + b
        CalcOperator.SUBTRACT -> a - b
        CalcOperator.MULTIPLY -> a * b
        CalcOperator.DIVIDE -> if (b == 0.0) Double.NaN else a / b
    }
    fun clear() { display = "0"; expression = ""; accumulator = null; pendingOperator = null; typing = false }
    fun input(s: String) {
        if (display == "Error") clear()
        display = if (!typing) { if (s == ".") "0." else s } else when {
            s == "." && display.contains(".") -> display
            display == "0" && s != "." -> s
            display.length >= 16 -> display
            else -> display + s
        }
        typing = true
    }
    fun chooseOperator(next: CalcOperator) {
        if (display == "Error") return
        val current = value()
        val result = if (accumulator != null && pendingOperator != null && typing) calculate(accumulator!!, current, pendingOperator!!) else current
        if (!result.isFinite()) { display = "Error"; accumulator = null; pendingOperator = null; return }
        display = format(result); accumulator = result; pendingOperator = next; typing = false; expression = "${format(result)} ${next.symbol}"
    }
    fun equals() {
        val a = accumulator ?: return
        val op = pendingOperator ?: return
        val b = value()
        val result = format(calculate(a, b, op))
        history = (listOf(Calculation("${format(a)} ${op.symbol} ${format(b)}", result)) + history).take(30)
        display = result; accumulator = null; pendingOperator = null; expression = ""; typing = false
    }
    fun backspace() { if (typing) display = if (display.length <= 1 || display == "-0") "0" else display.dropLast(1) }
    fun percent() { display = format(value() / 100.0); typing = true }
    fun sign() { if (display != "0" && display != "Error") display = if (display.startsWith("-")) display.drop(1) else "-$display" }

    Column(Modifier.fillMaxSize().background(CurioCream).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CALCULATOR", color = CurioRoseDark, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                Text("Simple arithmetic", color = CurioInk, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(CurioSurface).clickable { showHistory = true }.padding(horizontal = 14.dp, vertical = 10.dp)) { Text("History", color = CurioInk, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Bottom) {
            Text(if (expression.isEmpty()) "Ready when you are" else expression, color = CurioRoseDark, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(5.dp)); Text(display, color = CurioInk, fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, textAlign = TextAlign.End)
        }
        Spacer(Modifier.height(18.dp))
        val keys = listOf(listOf("AC", "±", "%", "÷"), listOf("7", "8", "9", "×"), listOf("4", "5", "6", "−"), listOf("1", "2", "3", "+"), listOf("⌫", "0", ".", "="))
        keys.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val operatorKey = key in listOf("÷", "×", "−", "+"); val equalsKey = key == "="; val utility = key in listOf("AC", "±", "%", "⌫")
                    val bg = when { equalsKey -> CurioInk; operatorKey -> CurioRoseWood; utility -> CurioSurface; else -> Color.White.copy(alpha = .78f) }
                    val fg = if (equalsKey || operatorKey) Color.White else CurioInk
                    Box(Modifier.weight(1f).aspectRatio(1.48f).clip(RoundedCornerShape(18.dp)).background(bg).clickable {
                        when (key) {
                            "AC" -> clear(); "±" -> sign(); "%" -> percent(); "⌫" -> backspace(); "." -> input(".")
                            "=" -> equals(); "÷" -> chooseOperator(CalcOperator.DIVIDE); "×" -> chooseOperator(CalcOperator.MULTIPLY); "−" -> chooseOperator(CalcOperator.SUBTRACT); "+" -> chooseOperator(CalcOperator.ADD)
                            else -> input(key)
                        }
                    }, contentAlignment = Alignment.Center) { Text(key, color = fg, fontSize = if (key == "⌫") 21.sp else 23.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
    if (showHistory) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(onDismissRequest = { showHistory = false }, containerColor = CurioSurface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("History", color = CurioInk, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    Text("Clear", color = CurioRoseWood, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { history = emptyList() }.padding(8.dp))
                }
                Spacer(Modifier.height(10.dp))
                if (history.isEmpty()) Text("Your calculations will appear here.", color = CurioRoseDark, modifier = Modifier.padding(vertical = 30.dp))
                history.forEach { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text(item.expression, color = CurioRoseDark, fontSize = 14.sp); Text(item.result, color = CurioInk, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
