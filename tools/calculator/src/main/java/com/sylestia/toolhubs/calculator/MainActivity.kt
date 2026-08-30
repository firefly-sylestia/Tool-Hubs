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
import androidx.compose.material3.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*
import java.util.Locale

class MainActivity:ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContent{ToolHubsTheme{CalculatorScreen()}}}}
private data class Calculation(val expression:String,val result:String)
private enum class AngleMode{DEG,RAD}
private fun fmt(n:Double):String=when{!n.isFinite()->"Error";abs(n)<1e-10->"0";abs(n-n.toLong())<1e-10->n.toLong().toString();else->String.format(Locale.US,"%.10f",n).trimEnd('0').trimEnd('.')}
private class Parser(private val s:String){private var i=0;private fun skip(){while(i<s.length&&s[i].isWhitespace())i++};fun expr():Double{var v=term();while(true){skip();if(i>=s.length||s[i]!='+'&&s[i]!='-')return v;val c=s[i++];val r=term();v=if(c=='+')v+r else v-r}};private fun term():Double{var v=factor();while(true){skip();if(i>=s.length||s[i]!='*'&&s[i]!='/')return v;val c=s[i++];val r=factor();v=if(c=='*')v*r else if(r==0.0)Double.NaN else v/r}};private fun factor():Double{skip();if(i<s.length&&(s[i]=='+'||s[i]=='-')){val neg=s[i++]=='-';val v=factor();return if(neg)-v else v};if(i<s.length&&s[i]=='('){i++;val v=expr();skip();if(i<s.length&&s[i]==')')i++;return v};val st=i;while(i<s.length&&(s[i].isDigit()||s[i]=='.'))i++;return s.substring(st,i).toDoubleOrNull()?:Double.NaN}}
private fun eval(s:String)=Parser(s.replace('×','*').replace('÷','/').replace('−','-').replace("π",Math.PI.toString()).replace("e",Math.E.toString())).expr()

@Composable private fun CalculatorScreen(){
 var expression by rememberSaveable{mutableStateOf("")};var display by rememberSaveable{mutableStateOf("0")};var justCalculated by rememberSaveable{mutableStateOf(false)};var typing by rememberSaveable{mutableStateOf(false)};var preview by remember{mutableStateOf<String?>(null)};var history by remember{mutableStateOf(emptyList<Calculation>())};var historyOpen by remember{mutableStateOf(false)};var advanced by rememberSaveable{mutableStateOf(false)};var angle by rememberSaveable{mutableStateOf(AngleMode.DEG)};var memory by rememberSaveable{mutableStateOf(0.0)};var hasMemory by rememberSaveable{mutableStateOf(false)};val view=LocalView.current;val clipboard=LocalClipboardManager.current
 fun h(){view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)}
 LaunchedEffect(expression,justCalculated){if(justCalculated||expression.isBlank()){preview=null;return@LaunchedEffect};val snap=expression;if(snap.matches(Regex("[+\\-]?\\d+(\\.\\d+)?"))){preview=null;return@LaunchedEffect};preview=withContext(Dispatchers.Default){fmt(eval(snap)).takeIf{it!="Error"}}}
 fun append(s:String){h();if(justCalculated){expression="";display="0";justCalculated=false};if(s=="."&&typing&&display.contains('.'))return;expression+=s;display=when{!typing&&s=="."->"0.";!typing->s;display=="0"&&s.firstOrNull()?.isDigit()==true->s;else->display+s};typing=true}
 fun back(){h();if(expression.isNotEmpty()){expression=expression.dropLast(1);val t=expression.takeLastWhile{it.isDigit()||it=='.'};display=t.ifEmpty{"0"}}}
 fun clear(){h();expression="";display="0";typing=false;justCalculated=false}
 fun equal(){h();if(expression.isBlank())return;val r=fmt(eval(expression));if(r!="Error")history=(listOf(Calculation(expression,r))+history).take(80);display=r;preview=null;justCalculated=true;typing=false}
 fun unary(name:String,f:(Double)->Double){h();val n=display.toDoubleOrNull()?:return;display=fmt(f(n));expression="$name($n)";preview=null;justCalculated=true;typing=false}
 fun mem(a:String){h();val n=display.toDoubleOrNull()?:0.0;when(a){"MC"->{memory=0.0;hasMemory=false};"MR"->{display=fmt(memory);typing=false};"M+"->{memory+=n;hasMemory=true};"M−"->{memory-=n;hasMemory=true}}}
 Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CurioCream,Color(0xFFF3E9E8)))).statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp)){
  Row(Modifier.fillMaxWidth().height(64.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Row(verticalAlignment=Alignment.CenterVertically){Text("CALCULATOR",color=CurioRoseDark,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);if(hasMemory){Spacer(Modifier.width(7.dp));Text("M",color=CurioRoseWood,fontSize=10.sp,fontWeight=FontWeight.Bold,modifier=Modifier.clip(RoundedCornerShape(50.dp)).background(CurioRoseWood.copy(.12f)).padding(horizontal=7.dp,vertical=3.dp))}};Text("Beautifully calculated",color=CurioInk,fontSize=23.sp,fontWeight=FontWeight.ExtraBold)};Box(Modifier.size(46.dp).clip(RoundedCornerShape(50.dp)).background(Color.White.copy(.48f)).clickable{h();historyOpen=true},contentAlignment=Alignment.Center){Icon(Icons.Outlined.History,null,tint=CurioInk)}}
  Spacer(Modifier.height(7.dp));Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(listOf(Color.White.copy(.58f),CurioSurface.copy(.62f)))).padding(horizontal=20.dp,vertical=18.dp),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.Bottom){Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.End){if(expression.isNotEmpty())Text(expression,color=CurioRoseDark.copy(.7f),fontSize=16.sp,maxLines=1,softWrap=false,overflow=TextOverflow.Clip)};Spacer(Modifier.height(7.dp));if(preview!=null)Text("≈ $preview",color=CurioRoseWood.copy(.72f),fontSize=13.sp,maxLines=1,softWrap=false)else if(justCalculated)Text("RESULT",color=CurioRoseWood.copy(.72f),fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp);Spacer(Modifier.height(4.dp));Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.End){Text(display,color=CurioInk,fontSize=when{display.length>24->26.sp;display.length>18->31.sp;display.length>13->38.sp;display.length>9->46.sp;else->56.sp},fontWeight=FontWeight.ExtraBold,maxLines=1,softWrap=false,overflow=TextOverflow.Clip,textAlign=TextAlign.End)};Spacer(Modifier.height(8.dp));Row(verticalAlignment=Alignment.CenterVertically){Text(if(angle==AngleMode.DEG)"DEG"else"RAD",color=CurioRoseWood,fontSize=10.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.clip(RoundedCornerShape(50.dp)).background(CurioRoseWood.copy(.1f)).clickable{h();angle=if(angle==AngleMode.DEG)AngleMode.RAD else AngleMode.DEG}.padding(horizontal=9.dp,vertical=5.dp));Spacer(Modifier.width(8.dp));Text("tap to switch angle mode",color=CurioRoseDark.copy(.5f),fontSize=10.sp)}}
  Spacer(Modifier.height(9.dp));if(advanced){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){listOf("sin","cos","tan","ln","log").forEach{key->Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(50.dp)).background(Color.White.copy(.48f)).clickable{val f:(Double)->Double={n->val x=if(angle==AngleMode.DEG)n*PI/180 else n;when(key){"sin"->sin(x);"cos"->cos(x);"tan"->tan(x);"ln"->ln(n);else->log10(n)}};unary(key,f)},contentAlignment=Alignment.Center){Text(key,color=CurioInk,fontSize=12.sp,fontWeight=FontWeight.Bold)}}};Spacer(Modifier.height(7.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){listOf("√","x²","1/x","π","e").forEach{key->Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(50.dp)).background(Color.White.copy(.48f)).clickable{when(key){"√"->unary("√"){if(it<0)Double.NaN else sqrt(it)};"x²"->unary("x²"){it*it};"1/x"->unary("1/"){if(it==0.0)Double.NaN else 1.0/it};else->append(key)}},contentAlignment=Alignment.Center){Text(key,color=CurioInk,fontSize=13.sp,fontWeight=FontWeight.Bold)}}}}
  Spacer(Modifier.height(8.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){listOf("MC","MR","M+","M−").forEach{key->Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(50.dp)).background(CurioSurface.copy(.7f)).clickable{mem(key)},contentAlignment=Alignment.Center){Text(key,color=CurioRoseDark,fontSize=10.sp,fontWeight=FontWeight.ExtraBold)}}}
  Spacer(Modifier.height(6.dp));val rows=listOf(listOf("AC","(",")","÷"),listOf("7","8","9","×"),listOf("4","5","6","−"),listOf("1","2","3","+"),listOf("⌫","0",".","="));rows.forEach{row->Row(Modifier.fillMaxWidth().padding(vertical=3.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{key->val op=key in listOf("÷","×","−","+");val eq=key=="=";val bg=when{eq->Brush.linearGradient(listOf(CurioInk,Color(0xFF6C3B52)));op->Brush.linearGradient(listOf(CurioRoseWood,Color(0xFF9A596B)));else->Brush.linearGradient(listOf(Color.White.copy(.74f),CurioSurface.copy(.78f)))};Box(Modifier.weight(1f).aspectRatio(1.38f).clip(RoundedCornerShape(25.dp)).background(bg).clickable{when(key){"AC"->clear();"⌫"->back();"="->equal();else->append(key)}},contentAlignment=Alignment.Center){if(key=="⌫")Icon(Icons.Outlined.Backspace,null,tint=CurioInk)else Text(key,color=if(eq||op)Color.White else CurioInk,fontSize=if(key in listOf("AC","(",")"))19.sp else 24.sp,fontWeight=FontWeight.Bold)}}}}
  Spacer(Modifier.height(5.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){Text(if(advanced)"Hide advanced"else"Advanced",color=CurioRoseDark,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.clip(RoundedCornerShape(50.dp)).clickable{h();advanced=!advanced}.padding(horizontal=10.dp,vertical=6.dp));if(display!="0"&&display!="Error"){Spacer(Modifier.width(5.dp));Box(Modifier.clip(RoundedCornerShape(50.dp)).background(CurioRoseWood.copy(.1f)).clickable{h();clipboard.setText(AnnotatedString(display))}.padding(horizontal=10.dp,vertical=6.dp)){Icon(Icons.Outlined.ContentCopy,null,tint=CurioRoseWood,modifier=Modifier.size(14.dp))}}};Spacer(Modifier.height(2.dp))
 }
 if(historyOpen){ModalBottomSheet(onDismissRequest={historyOpen=false},containerColor=CurioSurface){Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Calculations",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.ExtraBold,modifier=Modifier.weight(1f));Text("Clear",color=CurioRoseWood,fontWeight=FontWeight.Bold,modifier=Modifier.clickable{history=emptyList()}.padding(8.dp))};Spacer(Modifier.height(12.dp));if(history.isEmpty())Text("Your calculation story will appear here.",color=CurioRoseDark);history.forEach{item->Column(Modifier.fillMaxWidth().padding(vertical=5.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(.42f)).padding(15.dp)){Text(item.expression,color=CurioRoseDark,fontSize=14.sp,maxLines=2);Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(item.result,color=CurioInk,fontSize=26.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.weight(1f));Text("Use",color=CurioRoseWood,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.clickable{display=item.result;expression=item.expression;historyOpen=false;justCalculated=true}.padding(8.dp))}}}}}}
}