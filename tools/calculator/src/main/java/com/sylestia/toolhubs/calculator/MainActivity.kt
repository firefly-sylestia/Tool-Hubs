package com.sylestia.toolhubs.calculator

import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.History
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import com.sylestia.toolhubs.designsystem.*
import kotlin.math.abs
import kotlin.math.sqrt
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { ToolHubsTheme { CalculatorScreen() } } }
}

private enum class Op(val symbol: String) { ADD("+"), SUB("−"), MUL("×"), DIV("÷") }
private data class Calculation(val expression: String, val result: String)

private fun formatNumber(n: Double): String {
    if (!n.isFinite()) return "Error"
    if (abs(n) < 1e-10) return "0"
    if (abs(n - n.toLong()) < 1e-10) return n.toLong().toString()
    return String.format(Locale.US, "%.10f", n).trimEnd('0').trimEnd('.')
}

private fun evaluateExpression(source: String): Double {
    val s = source.replace("×", "*").replace("÷", "/").replace("−", "-").replace(" ", "")
    var i = 0
    fun parseExpression(): Double { var v = parseTerm(); while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op=s[i++]; val r=parseTerm(); v=if(op=='+')v+r else v-r }; return v }
    fun parseTerm(): Double { var v=parseFactor(); while(i<s.length&&(s[i]=='*'||s[i]=='/')){val op=s[i++];val r=parseFactor();v=if(op=='*')v*r else if(r==0.0)Double.NaN else v/r};return v }
    fun parseFactor(): Double { if(i<s.length&&s[i]=='-'){i++;return -parseFactor()}; if(i<s.length&&s[i]=='+'){i++;return parseFactor()}; if(i<s.length&&s[i]=='('){i++;val v=parseExpression();if(i<s.length&&s[i]==')')i++;return v}; val start=i;while(i<s.length&&(s[i].isDigit()||s[i]=='.'))i++;return s.substring(start,i).toDoubleOrNull()?:Double.NaN }
    return parseExpression()
}

@Composable
private fun CalculatorScreen() {
    var display by rememberSaveable { mutableStateOf("0") }
    var expression by rememberSaveable { mutableStateOf("") }
    var typing by rememberSaveable { mutableStateOf(false) }
    var justCalculated by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<Calculation>()) }
    var showHistory by remember { mutableStateOf(false) }
    var functions by remember { mutableStateOf(false) }
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current
    fun haptic() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    fun append(text:String) { haptic(); if(justCalculated){expression="";display="0";justCalculated=false}; expression += text; if(text.firstOrNull()?.isDigit()==true||text==".") display=if(typing)display+text else text; typing=true }
    fun clear(){haptic();expression="";display="0";typing=false;justCalculated=false}
    fun equals(){haptic();if(expression.isBlank())return;val r=evaluateExpression(expression);val out=formatNumber(r);if(out!="Error")history=(listOf(Calculation(expression,out))+history).take(50);display=out;justCalculated=true;typing=false}
    fun unary(symbol:String, transform:(Double)->Double){haptic();val n=display.toDoubleOrNull()?:return;val r=transform(n);display=formatNumber(r);expression="$symbol(${formatNumber(n)})";justCalculated=true;typing=false}

    Column(Modifier.fillMaxSize().background(CurioCream).statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp)) {
        Row(Modifier.fillMaxWidth().height(66.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("CALCULATOR",color=CurioRoseDark,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);Text("Everyday arithmetic",color=CurioInk,fontSize=23.sp,fontWeight=FontWeight.ExtraBold)};Box(Modifier.size(44.dp).clip(RoundedCornerShape(50.dp)).background(CurioSurface).clickable{haptic();showHistory=true},contentAlignment=Alignment.Center){Icon(Icons.Outlined.History,null,tint=CurioInk)}}
        Column(Modifier.fillMaxWidth().weight(1f),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.Bottom){if(expression.isNotEmpty())Text(expression,color=CurioRoseDark.copy(.72f),fontSize=16.sp,maxLines=1);Spacer(Modifier.height(6.dp));Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.End){Text(display,color=CurioInk,fontSize=if(display.length>14)34.sp else 54.sp,fontWeight=FontWeight.ExtraBold,maxLines=1,textAlign=TextAlign.End,modifier=Modifier.weight(1f))}}
        Spacer(Modifier.height(10.dp))
        if(functions){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("√","x²","1/x").forEach{key->Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(50.dp)).background(CurioSurface.copy(.75f)).clickable{when(key){"√"->unary("√"){if(it<0)Double.NaN else sqrt(it)};"x²"->unary("x²"){it*it};"1/x"->unary("1/"){if(it==0.0)Double.NaN else 1.0/it}}},contentAlignment=Alignment.Center){Text(key,color=CurioInk,fontWeight=FontWeight.Bold)}}}}
        Spacer(Modifier.height(8.dp));val keys=listOf(listOf("AC","(",")","÷"),listOf("7","8","9","×"),listOf("4","5","6","−"),listOf("1","2","3","+"),listOf("⌫","0",".","="));keys.forEach{row->Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)){row.forEach{key->val op=key in listOf("÷","×","−","+");val eq=key=="=";val util=key in listOf("AC","(",")","⌫");val bg=when{eq->CurioInk;op->CurioRoseWood;util->CurioSurface;else->Color.White.copy(.72f)};Box(Modifier.weight(1f).aspectRatio(1.35f).clip(RoundedCornerShape(24.dp)).background(bg).clickable{when(key){"AC"->clear();"⌫"->{haptic();if(expression.isNotEmpty())expression=expression.dropLast(1)};"="->equals();"."->append(".");"("->append("(");")"->append(")");"÷"->append(" ÷ ");"×"->append(" × ");"−"->append(" − ");"+"->append(" + ");else->append(key)}},contentAlignment=Alignment.Center){if(key=="⌫")Icon(Icons.Outlined.Backspace,null,tint=if(eq||op)Color.White else CurioInk)else Text(key,color=if(eq||op)Color.White else CurioInk,fontSize=if(util)20.sp else 24.sp,fontWeight=FontWeight.Bold)}}}}
        Spacer(Modifier.height(6.dp));Row(verticalAlignment=Alignment.CenterVertically){Text(if(functions)"Hide functions"else"More functions",color=CurioRoseDark,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.clickable{haptic();functions=!functions}.padding(8.dp));if(display!="0"&&display!="Error"){Text("Copy",color=CurioRoseDark,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.clickable{haptic();clipboard.setText(AnnotatedString(display))}.padding(8.dp))}}
        Spacer(Modifier.height(4.dp))
    }
    if(showHistory){@OptIn(ExperimentalMaterial3Api::class) ModalBottomSheet(onDismissRequest={showHistory=false},containerColor=CurioSurface){Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)){Text("Calculations",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.ExtraBold);Spacer(Modifier.height(12.dp));if(history.isEmpty())Text("No calculations yet.");history.forEach{item->Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(.45f)).padding(14.dp).padding(vertical=4.dp)){Text(item.expression,color=CurioRoseDark,fontSize=14.sp);Text(item.result,color=CurioInk,fontSize=24.sp,fontWeight=FontWeight.ExtraBold)}}}}}
}
