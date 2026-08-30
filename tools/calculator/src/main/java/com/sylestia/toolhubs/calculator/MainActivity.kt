package com.sylestia.toolhubs.calculator

import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalView
import com.sylestia.toolhubs.designsystem.*
import kotlin.math.abs
import kotlin.math.sqrt
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
    var lastOperand by rememberSaveable { mutableStateOf<Double?>(null) }
    var lastOperator by rememberSaveable { mutableStateOf<CalcOperator?>(null) }
    var history by remember { mutableStateOf(emptyList<Calculation>()) }
    var showHistory by remember { mutableStateOf(false) }
    var showFunctions by remember { mutableStateOf(false) }
    val view = LocalView.current
    fun haptic() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    fun value() = display.toDoubleOrNull() ?: 0.0
    fun format(n: Double): String { if (!n.isFinite()) return "Error"; if (abs(n) < 1e-10) return "0"; if (abs(n - n.toLong()) < 1e-10) return n.toLong().toString(); return String.format(Locale.US, "%.10f", n).trimEnd('0').trimEnd('.') }
    fun calc(a: Double, b: Double, op: CalcOperator) = when (op) { CalcOperator.ADD -> a + b; CalcOperator.SUBTRACT -> a - b; CalcOperator.MULTIPLY -> a * b; CalcOperator.DIVIDE -> if (b == 0.0) Double.NaN else a / b }
    fun clear() { haptic(); display="0"; expression=""; accumulator=null; pendingOperator=null; typing=false; lastOperand=null; lastOperator=null }
    fun input(s: String) { haptic(); if(display=="Error") clear(); display=if(!typing){if(s==".")"0." else s}else when{ s=="."&&display.contains(".")->display; display=="0"&&s!="."->s; display.length>=16->display; else->display+s }; typing=true }
    fun chooseOperator(next: CalcOperator) { haptic(); if(display=="Error")return; val current=value(); val result=if(accumulator!=null&&pendingOperator!=null&&typing)calc(accumulator!!,current,pendingOperator!!)else current; if(!result.isFinite()){display="Error";accumulator=null;pendingOperator=null;return}; display=format(result);accumulator=result;pendingOperator=next;typing=false;expression="${format(result)} ${next.symbol}" }
    fun equals() { haptic(); val a=accumulator; val op=pendingOperator; if(a!=null&&op!=null){val b=value();val raw=calc(a,b,op);val result=format(raw);if(raw.isFinite())history=(listOf(Calculation("${format(a)} ${op.symbol} ${format(b)}",result))+history).take(40);display=result;lastOperand=b;lastOperator=op;accumulator=null;pendingOperator=null;expression="";typing=false}else if(lastOperand!=null&&lastOperator!=null){val a=value();val raw=calc(a,lastOperand!!,lastOperator!!);val result=format(raw);if(raw.isFinite())history=(listOf(Calculation("${format(a)} ${lastOperator!!.symbol} ${format(lastOperand!!)}",result))+history).take(40);display=result;typing=false} }
    fun backspace(){haptic();if(typing)display=if(display.length<=1||display=="-0")"0"else display.dropLast(1)}
    fun percent(){haptic();display=format(value()/100.0);typing=true}
    fun sign(){haptic();if(display!="0"&&display!="Error")display=if(display.startsWith("-"))display.drop(1)else"-$display"}
    fun function(name:String){haptic();val n=value();val r=when(name){"√"->if(n>=0)sqrt(n)else Double.NaN;"x²"->n*n;"1/x"->if(n!=0.0)1.0/n else Double.NaN;else->n};display=format(r);typing=false}

    Column(Modifier.fillMaxSize().background(CurioCream).statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp)) {
        Row(Modifier.fillMaxWidth().height(66.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("CALCULATOR",color=CurioRoseDark,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);Text("Everyday arithmetic",color=CurioInk,fontSize=23.sp,fontWeight=FontWeight.ExtraBold)};Text("History",color=CurioInk,fontWeight=FontWeight.Bold,modifier=Modifier.clip(RoundedCornerShape(50.dp)).background(CurioSurface).clickable{haptic();showHistory=true}.padding(horizontal=15.dp,vertical=10.dp))}
        Column(Modifier.fillMaxWidth().weight(1f),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.Bottom){Text(if(expression.isEmpty())"Ready when you are"else expression,color=CurioRoseDark.copy(.7f),fontSize=15.sp,maxLines=1);Spacer(Modifier.height(5.dp));Text(display,color=CurioInk,fontSize=54.sp,fontWeight=FontWeight.ExtraBold,maxLines=1,textAlign=TextAlign.End)}
        if(showFunctions){Spacer(Modifier.height(10.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("√","x²","1/x").forEach{key->Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(50.dp)).background(CurioSurface.copy(.7f)).clickable{function(key)},contentAlignment=Alignment.Center){Text(key,color=CurioInk,fontWeight=FontWeight.Bold)}}}}
        Spacer(Modifier.height(10.dp));val keys=listOf(listOf("AC","±","%","÷"),listOf("7","8","9","×"),listOf("4","5","6","−"),listOf("1","2","3","+"),listOf("⌫","0",".","="));keys.forEach{row->Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)){row.forEach{key->val op=key in listOf("÷","×","−","+");val eq=key=="=";val util=key in listOf("AC","±","%","⌫");val bg=when{eq->CurioInk;op->CurioRoseWood;util->CurioSurface;else->Color.White.copy(.7f)};Box(Modifier.weight(1f).aspectRatio(1.35f).clip(RoundedCornerShape(24.dp)).background(bg).clickable{when(key){"AC"->clear();"±"->sign();"%"->percent();"⌫"->backspace();"."->input(".");"="->equals();"÷"->chooseOperator(CalcOperator.DIVIDE);"×"->chooseOperator(CalcOperator.MULTIPLY);"−"->chooseOperator(CalcOperator.SUBTRACT);"+"->chooseOperator(CalcOperator.ADD);else->input(key)}},contentAlignment=Alignment.Center){Text(key,color=if(eq||op)Color.White else CurioInk,fontSize=if(util)19.sp else 24.sp,fontWeight=FontWeight.Bold)}}}}
        Spacer(Modifier.height(6.dp));Text(if(showFunctions)"Hide functions"else"More functions",color=CurioRoseDark,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.CenterHorizontally).clickable{haptic();showFunctions=!showFunctions}.padding(8.dp));Spacer(Modifier.height(4.dp))
    }
    if(showHistory){@OptIn(ExperimentalMaterial3Api::class) ModalBottomSheet(onDismissRequest={showHistory=false}){Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)){Text("Calculations",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.ExtraBold);Spacer(Modifier.height(12.dp));if(history.isEmpty())Text("No calculations yet.");history.forEach{item->Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(item.expression,color=CurioRoseDark);Text(item.result,fontSize=22.sp,fontWeight=FontWeight.Bold)}}}}}}
}
