package com.sylestia.toolhubs.calculator

import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.*
import kotlin.math.*
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { ToolHubsTheme { CalculatorScreen() } } }
}

private data class Calculation(val expression: String, val result: String)
private enum class AngleMode { DEG, RAD }

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
private fun evaluate(source:String):Double=Parser(source.replace('×','*').replace('÷','/').replace('−','-').replace("π",Math.PI.toString()).replace("e",Math.E.toString())).expression()

@Composable
private fun CalculatorScreen() {
    var expression by rememberSaveable { mutableStateOf("") }
    var display by rememberSaveable { mutableStateOf("0") }
    var justCalculated by rememberSaveable { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<Calculation>()) }
    var showHistory by remember { mutableStateOf(false) }
    var showFunctions by rememberSaveable { mutableStateOf(false) }
    var angleMode by rememberSaveable { mutableStateOf(AngleMode.DEG) }
    var memory by rememberSaveable { mutableStateOf(0.0) }
    var hasMemory by rememberSaveable { mutableStateOf(false) }
    val view=LocalView.current; val clipboard=LocalClipboardManager.current
    fun haptic(){view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)}
    fun append(s:String){haptic();if(justCalculated){expression="";display="0";justCalculated=false};if(s=="."&&typing&&display.contains('.'))return;expression+=s;display=when{!typing&&s=="."->"0.";!typing->s;display=="0"&&s.firstOrNull()?.isDigit()==true->s;else->display+s};typing=true}
    fun back(){haptic();if(expression.isNotEmpty()){expression=expression.dropLast(1);val tail=expression.takeLastWhile{it.isDigit()||it=='.'};display=tail.ifEmpty{"0"}}}
    fun clear(){haptic();expression="";display="0";typing=false;justCalculated=false}
    fun equal(){haptic();if(expression.isBlank())return;val raw=evaluate(expression);val result=formatNumber(raw);if(result!="Error")history=(listOf(Calculation(expression,result))+history).take(80);display=result;justCalculated=true;typing=false}
    fun unary(name:String, f:(Double)->Double){haptic();val n=display.toDoubleOrNull()?:return;val out=formatNumber(f(n));display=out;expression="$name($n)";justCalculated=true;typing=false}
    fun memoryAction(action:String){haptic();val n=display.toDoubleOrNull()?:0.0;when(action){"MC"->{memory=0.0;hasMemory=false};"MR"->{display=formatNumber(memory);typing=false};"M+"->{memory+=n;hasMemory=true};"M−"->{memory-=n;hasMemory=true}}}
    fun preview():String?=if(expression.isNotBlank()&&!justCalculated){formatNumber(evaluate(expression)).takeIf{it!="Error"} }else null
    val preview=preview()

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CurioCream, Color(0xFFF3E9E8)))).statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Row(verticalAlignment=Alignment.CenterVertically){Text("CALCULATOR",color=CurioRoseDark,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);if(hasMemory){Spacer(Modifier.width(8.dp));Text("M",color=CurioRoseWood,fontSize=10.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.clip(RoundedCornerShape(50.dp)).background(CurioRoseWood.copy(.12f)).padding(horizontal=7.dp,vertical=3.dp))}};Text("Beautifully calculated",color=CurioInk,fontSize=23.sp,fontWeight=FontWeight.ExtraBold)};Box(Modifier.size(46.dp).clip(RoundedCornerShape(50.dp)).background(Color.White.copy(.48f)).clickable{haptic();showHistory=true},contentAlignment=Alignment.Center){Icon(Icons.Outlined.History,null,tint=CurioInk)}}
        Spacer(Modifier.height(7.dp))
        Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(listOf(Color.White.copy(.55f),CurioSurface.copy(.58f)))).padding(horizontal=20.dp,vertical=18.dp),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.Bottom){Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.End){if(expression.isNotEmpty())Text(expression,color=CurioRoseDark.copy(.7f),fontSize=16.sp,maxLines=1)};if(preview!=null){Spacer(Modifier.height(6.dp));Text("= $preview",color=CurioRoseWood.copy(.72f),fontSize=13.sp,fontWeight=FontWeight.Medium)};Spacer(Modifier.height(6.dp));Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.End){AnimatedContent(display,label="result"){v->Text(v,color=CurioInk,fontSize=when{v.length>24->26.sp;v.length>18->31.sp;v.length>13->38.sp;v.length>9->46.sp;else->56.sp},fontWeight=FontWeight.ExtraBold,maxLines=1,textAlign=TextAlign.End,modifier=Modifier.animateContentSize())}};Spacer(Modifier.height(8.dp));Row(verticalAlignment=Alignment.CenterVertically){Text(if(angleMode==AngleMode.DEG)"DEG"else"RAD",color=CurioRoseWood,fontSize=10.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.clip(RoundedCornerShape(50.dp)).background(CurioRoseWood.copy(.10f)).clickable{haptic();angleMode=if(angleMode==AngleMode.DEG)AngleMode.RAD else AngleMode.DEG}.padding(horizontal=9.dp,vertical=5.dp));Spacer(Modifier.width(8.dp));Text("tap to switch angle mode",color=CurioRoseDark.copy(.5f),fontSize=10.sp)}}
        Spacer(Modifier.height(10.dp))
        if(showFunctions){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){listOf("sin","cos","tan","ln","log").forEach{key->Box(Modifier.weight(1f).height(43.dp).clip(RoundedCornerShape(50.dp)).background(Color.White.copy(.46f)).clickable{val factor: (Double)->Double={n->val x=if(angleMode==AngleMode.DEG)n*PI/180 else n;when(key){"sin"->sin(x);"cos"->cos(x);"tan"->tan(x);"ln"->ln(n);else->log10(n)}};unary(key,factor)},contentAlignment=Alignment.Center){Text(key,color=CurioInk,fontSize=12.sp,fontWeight=FontWeight.Bold)}}};Spacer(Modifier.height(7.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){listOf("√","x²","1/x","π","e").forEach{key->Box(Modifier.weight(1f).height(43.dp).clip(RoundedCornerShape(50.dp)).background(Color.White.copy(.46f)).clickable{when(key){"√"->unary("√"){if(it<0)Double.NaN else sqrt(it)};"x²"->unary("x²"){it*it};"1/x"->unary("1/"){if(it==0.0)Double.NaN else 1.0/it};"π"->append("π");"e"->append("e")}},contentAlignment=Alignment.Center){Text(key,color=CurioInk,fontSize=13.sp,fontWeight=FontWeight.Bold)}}}}
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){listOf("MC","MR","M+","M−").forEach{key->Box(Modifier.weight(1f).height(35.dp).clip(RoundedCornerShape(50.dp)).background(CurioSurface.copy(.65f)).clickable{memoryAction(key)},contentAlignment=Alignment.Center){Text(key,color=CurioRoseDark,fontSize=10.sp,fontWeight=FontWeight.ExtraBold)}}}
        Spacer(Modifier.height(6.dp))
        val rows=listOf(listOf("AC","(",")","÷"),listOf("7","8","9","×"),listOf("4","5","6","−"),listOf("1","2","3","+"),listOf("⌫","0",".","="))
        rows.forEach{row->Row(Modifier.fillMaxWidth().padding(vertical=3.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{key->val op=key in listOf("÷","×","−","+");val eq=key=="=";val util=key in listOf("AC","(",")","⌫");val bg=when{eq->Brush.linearGradient(listOf(CurioInk,Color(0xFF6C3B52)));op->Brush.linearGradient(listOf(CurioRoseWood,Color(0xFF9A596B)));else->Brush.linearGradient(listOf(Color.White.copy(.72f),CurioSurface.copy(.76f)))};var pressed by remember{mutableStateOf(false)};val scale by animateFloatAsState(if(pressed).94f else 1f,animationSpec=spring(),label="keyScale");Box(Modifier.weight(1f).aspectRatio(1.38f).offset(y=(1f-scale)*2.dp).clip(RoundedCornerShape(25.dp)).background(bg).clickable{pressed=true;when(key){"AC"->clear();"⌫"->back();"="->equal();"("->append("(");")"->append(")");"."->append(".");else->append(key)};pressed=false},contentAlignment=Alignment.Center){if(key=="⌫")Icon(Icons.Outlined.Backspace,null,tint=CurioInk)else Text(key,color=if(eq||op)Color.White else CurioInk,fontSize=if(util)19.sp else 24.sp,fontWeight=FontWeight.Bold)}}}}
        Spacer(Modifier.height(5.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){Text(if(showFunctions)"Hide advanced"else"Advanced",color=CurioRoseDark,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.clip(RoundedCornerShape(50.dp)).clickable{haptic();showFunctions=!showFunctions}.padding(horizontal=10.dp,vertical=6.dp));if(display!="0"&&display!="Error"){Spacer(Modifier.width(5.dp));Box(Modifier.clip(RoundedCornerShape(50.dp)).background(CurioRoseWood.copy(.10f)).clickable{haptic();clipboard.setText(AnnotatedString(display))}.padding(horizontal=10.dp,vertical=6.dp)){Icon(Icons.Outlined.ContentCopy,null,tint=CurioRoseWood,modifier=Modifier.size(14.dp))}}};Spacer(Modifier.height(2.dp))
    }
    if(showHistory){@OptIn(ExperimentalMaterial3Api::class) ModalBottomSheet(onDismissRequest={showHistory=false},containerColor=CurioSurface){Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Calculations",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.ExtraBold,modifier=Modifier.weight(1f));Text("Clear",color=CurioRoseWood,fontWeight=FontWeight.Bold,modifier=Modifier.clickable{history=emptyList()}.padding(8.dp))};Spacer(Modifier.height(12.dp));if(history.isEmpty())Text("Your calculation story will appear here.",color=CurioRoseDark);history.forEach{item->Column(Modifier.fillMaxWidth().padding(vertical=5.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(.42f)).padding(15.dp)){Text(item.expression,color=CurioRoseDark,fontSize=14.sp,maxLines=2);Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(item.result,color=CurioInk,fontSize=26.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.weight(1f));Box(Modifier.clip(RoundedCornerShape(50.dp)).background(CurioRoseWood.copy(.10f)).clickable{display=item.result;expression=item.expression;showHistory=false;justCalculated=true}.padding(horizontal=10.dp,vertical=6.dp)){Text("Use",color=CurioRoseWood,fontSize=11.sp,fontWeight=FontWeight.Bold)}}}}}}}
}
