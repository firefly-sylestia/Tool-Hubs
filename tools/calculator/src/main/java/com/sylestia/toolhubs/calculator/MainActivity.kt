package com.sylestia.toolhubs.calculator

import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material3.*
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
import com.sylestia.toolhubs.designsystem.*
import kotlin.math.abs
import kotlin.math.sqrt
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { ToolHubsTheme { CalculatorScreen() } } }
}

private data class Calculation(val expression: String, val result: String)
private enum class Op(val symbol: String) { ADD("+"), SUB("−"), MUL("×"), DIV("÷") }

private fun formatNumber(n: Double): String {
    if (!n.isFinite()) return "Error"
    if (abs(n) < 1e-10) return "0"
    if (abs(n - n.toLong()) < 1e-10) return n.toLong().toString()
    return String.format(Locale.US, "%.10f", n).trimEnd('0').trimEnd('.')
}

private class Parser(private val source: String) {
    private var index = 0
    private fun skip() { while (index < source.length && source[index].isWhitespace()) index++ }
    fun expression(): Double { var v=term(); while(true){skip();if(index>=source.length)break;val c=source[index];if(c!='+'&&c!='-')break;index++;val r=term();v=if(c=='+')v+r else v-r};return v }
    private fun term(): Double { var v=factor(); while(true){skip();if(index>=source.length)break;val c=source[index];if(c!='*'&&c!='/')break;index++;val r=factor();v=if(c=='*')v*r else if(r==0.0)Double.NaN else v/r};return v }
    private fun factor(): Double { skip();if(index<source.length&&(source[index]=='+'||source[index]=='-')){val neg=source[index++]=='-';val v=factor();return if(neg)-v else v};if(index<source.length&&source[index]=='('){index++;val v=expression();skip();if(index<source.length&&source[index]==')')index++;return v};val start=index;while(index<source.length&&(source[index].isDigit()||source[index]=='.'))index++;return source.substring(start,index).toDoubleOrNull()?:Double.NaN }
}
private fun evaluate(source:String):Double=Parser(source.replace('×','*').replace('÷','/').replace('−','-')).expression()

@Composable
private fun CalculatorScreen() {
    var expression by rememberSaveable { mutableStateOf("") }
    var display by rememberSaveable { mutableStateOf("0") }
    var justCalculated by rememberSaveable { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<Calculation>()) }
    var showHistory by remember { mutableStateOf(false) }
    var showFunctions by remember { mutableStateOf(false) }
    val view=LocalView.current; val clipboard=LocalClipboardManager.current
    fun haptic()=view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    fun append(s:String){haptic();if(justCalculated){expression="";display="0";justCalculated=false};if(s=="."&&typing&&display.contains('.'))return;expression+=s;display=when{!typing&&s=="."->"0.";!typing->s;display=="0"&&s.firstOrNull()?.isDigit()==true->s;else->display+s};typing=true}
    fun back(){haptic();if(expression.isNotEmpty()){expression=expression.dropLast(1);display=expression.takeLastWhile{it.isDigit()||it=='.'}.ifEmpty{"0"}}}
    fun clear(){haptic();expression="";display="0";typing=false;justCalculated=false}
    fun equal(){haptic();if(expression.isBlank())return;val raw=evaluate(expression);val result=formatNumber(raw);if(result!="Error")history=(listOf(Calculation(expression,result))+history).take(50);display=result;justCalculated=true;typing=false}
    fun unary(name:String, f:(Double)->Double){haptic();val n=display.toDoubleOrNull()?:return;val r=f(n);val out=formatNumber(r);display=out;expression="$name($n)";justCalculated=true;typing=false}

    Column(Modifier.fillMaxSize().background(CurioCream).statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp)) {
        Row(Modifier.fillMaxWidth().height(66.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("CALCULATOR",color=CurioRoseDark,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);Text("Everyday arithmetic",color=CurioInk,fontSize=23.sp,fontWeight=FontWeight.ExtraBold)};Box(Modifier.size(46.dp).clip(RoundedCornerShape(50.dp)).background(CurioSurface).clickable{haptic();showHistory=true},contentAlignment=Alignment.Center){Icon(Icons.Outlined.History,null,tint=CurioInk)}}
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().weight(1f),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.Bottom){Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.End){if(expression.isNotEmpty())Text(expression,color=CurioRoseDark.copy(.72f),fontSize=16.sp,maxLines=1)};Spacer(Modifier.height(7.dp));Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.End){AnimatedContent(display,label="result"){v->Text(v,color=CurioInk,fontSize=when{v.length>20->28.sp;v.length>14->36.sp;v.length>10->44.sp;else->56.sp},fontWeight=FontWeight.ExtraBold,maxLines=1,textAlign=TextAlign.End,modifier=Modifier.animateContentSize())}}}
        Spacer(Modifier.height(10.dp))
        if(showFunctions){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("√","x²","1/x").forEach{key->Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(50.dp)).background(CurioSurface.copy(.72f)).clickable{when(key){"√"->unary("√"){if(it<0)Double.NaN else sqrt(it)};"x²"->unary("x²"){it*it};"1/x"->unary("1/"){if(it==0.0)Double.NaN else 1/it}}},contentAlignment=Alignment.Center){Text(key,color=CurioInk,fontWeight=FontWeight.Bold)}}}}
        Spacer(Modifier.height(8.dp))
        val rows=listOf(listOf("AC","(",")","÷"),listOf("7","8","9","×"),listOf("4","5","6","−"),listOf("1","2","3","+"),listOf("⌫","0",".","="))
        rows.forEach{row->Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)){row.forEach{key->val op=key in listOf("÷","×","−","+");val eq=key=="=";val util=key in listOf("AC","(",")","⌫");val bg=when{eq->CurioInk;op->CurioRoseWood;util->CurioSurface;else->Color.White.copy(.72f)};Box(Modifier.weight(1f).aspectRatio(1.35f).clip(RoundedCornerShape(24.dp)).background(bg).clickable{when(key){"AC"->clear();"⌫"->back();"="->equal();"("->append("(");")"->append(")");"."->append(".");else->append(key)}},contentAlignment=Alignment.Center){if(key=="⌫")Icon(Icons.Outlined.Backspace,null,tint=CurioInk)else Text(key,color=if(eq||op)Color.White else CurioInk,fontSize=if(util)19.sp else 24.sp,fontWeight=FontWeight.Bold)}}}}
        Spacer(Modifier.height(6.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){Text(if(showFunctions)"Hide functions"else"More functions",color=CurioRoseDark,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.clickable{haptic();showFunctions=!showFunctions}.padding(7.dp));if(display!="0"&&display!="Error")Text("Copy",color=CurioRoseDark,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.clickable{haptic();clipboard.setText(AnnotatedString(display))}.padding(7.dp))};Spacer(Modifier.height(3.dp))
    }
    if(showHistory){@OptIn(ExperimentalMaterial3Api::class) ModalBottomSheet(onDismissRequest={showHistory=false},containerColor=CurioSurface){Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Calculations",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.ExtraBold,modifier=Modifier.weight(1f));Text("Clear",color=CurioRoseWood,fontWeight=FontWeight.Bold,modifier=Modifier.clickable{history=emptyList()}.padding(8.dp))};Spacer(Modifier.height(12.dp));if(history.isEmpty())Text("No calculations yet.",color=CurioRoseDark);history.forEach{item->Column(Modifier.fillMaxWidth().padding(vertical=5.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(.45f)).padding(14.dp)){Text(item.expression,color=CurioRoseDark,fontSize=14.sp);Text(item.result,color=CurioInk,fontSize=24.sp,fontWeight=FontWeight.ExtraBold)}}}}}
}
