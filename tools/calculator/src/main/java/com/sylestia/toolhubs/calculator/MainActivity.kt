@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.sylestia.toolhubs.calculator

import android.os.Bundle
import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.*
import kotlin.math.*
import java.util.Locale

class MainActivity:ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContent{ToolHubsTheme{CalculatorScreen()}}}}
private data class Calculation(val expression:String,val result:String)
private fun fmt(n:Double)=when{!n.isFinite()->"Error";abs(n)<1e-10->"0";abs(n-n.toLong())<1e-10->n.toLong().toString();else->String.format(Locale.US,"%.10f",n).trimEnd('0').trimEnd('.')}
private class Parser(private val s:String){private var i=0;private fun skip(){while(i<s.length&&s[i].isWhitespace())i++};fun expr():Double{var v=term();while(true){skip();if(i>=s.length||s[i]!='+'&&s[i]!='-')return v;val c=s[i++];v=if(c=='+')v+term()else v-term()}};private fun term():Double{var v=factor();while(true){skip();if(i>=s.length||s[i]!='*'&&s[i]!='/')return v;val c=s[i++];val r=factor();v=if(c=='*')v*r else if(r==0.0)Double.NaN else v/r}};private fun factor():Double{skip();if(i<s.length&&(s[i]=='+'||s[i]=='-')){val n=factor();return if(s[i-1]=='-')-n else n};if(i<s.length&&s[i]=='('){i++;val v=expr();skip();if(i<s.length&&s[i]==')')i++;return v};val st=i;while(i<s.length&&(s[i].isDigit()||s[i]=='.'))i++;return s.substring(st,i).toDoubleOrNull()?:Double.NaN}}
private fun eval(s:String)=Parser(s.replace('×','*').replace('÷','/').replace('−','-')).expr()
private fun loadHistory(c:Context):List<Calculation>{val p=c.getSharedPreferences("calculator",0);return (0 until p.getInt("count",0)).mapNotNull{val e=p.getString("e$it",null);val r=p.getString("r$it",null);if(e!=null&&r!=null)Calculation(e,r)else null}}
private fun saveHistory(c:Context,h:List<Calculation>){val p=c.getSharedPreferences("calculator",0).edit().clear();p.putInt("count",h.size);h.forEachIndexed{i,x->p.putString("e$i",x.expression);p.putString("r$i",x.result)};p.apply()}

@Composable private fun CalculatorScreen(){
 val context=LocalContext.current;var expression by rememberSaveable{mutableStateOf("")};var display by rememberSaveable{mutableStateOf("0")};var justCalculated by rememberSaveable{mutableStateOf(false)};var typing by rememberSaveable{mutableStateOf(false)};var preview by remember{mutableStateOf<String?>(null)};var history by remember{mutableStateOf(loadHistory(context))};var historyOpen by remember{mutableStateOf(false)};var advanced by rememberSaveable{mutableStateOf(false)};var repeatOp by rememberSaveable{mutableStateOf<String?>(null)};var repeatValue by rememberSaveable{mutableStateOf(0.0)};val view=LocalView.current;val clipboard=LocalClipboardManager.current
 fun h(){view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)}
 LaunchedEffect(expression,justCalculated){if(justCalculated||expression.isBlank()){preview=null;return@LaunchedEffect};val r=runCatching{fmt(eval(expression))}.getOrNull();preview=r?.takeIf{it!="Error"}}
 fun append(s:String){h();if(justCalculated){expression=if(s in listOf("+","−","×","÷"))display+s else s;typing=s !in listOf("+","−","×","÷");justCalculated=false}else{expression+=s;typing=true};if(s=="."&&display.contains('.'))return;display=if(!typing||display=="0"&&s.firstOrNull()?.isDigit()==true)s else display+s;repeatOp=null}
 fun clear(){h();expression="";display="0";typing=false;justCalculated=false;repeatOp=null}
 fun back(){h();if(expression.isNotEmpty()){expression=expression.dropLast(1);display=expression.takeLastWhile{it.isDigit()||it=='.'}.ifEmpty{"0"}}}
 fun equal(){h();if(expression.isBlank())return;if(justCalculated&&repeatOp!=null){expression="$display$repeatOp${fmt(repeatValue)}"};val r=fmt(eval(expression));if(r!="Error"){val item=Calculation(expression,r);history=(listOf(item)+history).take(100);saveHistory(context,history);display=r;preview=null;val m=Regex("([+−×÷])([0-9.]+)$").find(expression);if(m!=null){repeatOp=m.groupValues[1];repeatValue=m.groupValues[2].toDoubleOrNull()?:0.0}else repeatOp=null;justCalculated=true;typing=false}}
 fun op(s:String){h();if(justCalculated){expression=display+s;justCalculated=false;typing=false}else{expression+=s;typing=false};repeatOp=null}
 Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CurioCream,Color(0xFFF3E9E8)))).statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp)){
  Row(Modifier.fillMaxWidth().height(64.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("CALCULATOR",color=CurioRoseDark,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);Text("Beautifully calculated",color=CurioInk,fontSize=23.sp,fontWeight=FontWeight.ExtraBold)};Box(Modifier.size(46.dp).clip(RoundedCornerShape(50.dp)).background(Color.White.copy(.48f)).clickable{h();historyOpen=true},contentAlignment=Alignment.Center){Icon(Icons.Outlined.History,null,tint=CurioInk)}}
  Spacer(Modifier.height(7.dp));Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(listOf(Color.White.copy(.58f),CurioSurface.copy(.62f)))).padding(20.dp),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.Bottom){Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.End){Text(expression,color=CurioRoseDark.copy(.7f),fontSize=16.sp,maxLines=1,softWrap=false,overflow=TextOverflow.Clip)};Spacer(Modifier.height(7.dp));if(preview!=null)Text("≈ $preview",color=CurioRoseWood.copy(.72f),fontSize=13.sp);else if(justCalculated)Text("RESULT",color=CurioRoseWood.copy(.72f),fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp);Spacer(Modifier.height(4.dp));Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.End){Text(display,color=CurioInk,fontSize=when{display.length>24->26.sp;display.length>18->31.sp;display.length>13->38.sp;display.length>9->46.sp;else->56.sp},fontWeight=FontWeight.ExtraBold,maxLines=1,softWrap=false,overflow=TextOverflow.Clip,textAlign=TextAlign.End)}}
  Spacer(Modifier.height(9.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){listOf("AC","(",")","÷").forEach{key->Box(Modifier.weight(1f).aspectRatio(1.38f).clip(RoundedCornerShape(25.dp)).background(if(key=="÷")CurioRoseWood else Color.White.copy(.74f)).clickable{when(key){"AC"->clear();"÷"->op(key);else->append(key)}},contentAlignment=Alignment.Center){Text(key,color=if(key=="÷")Color.White else CurioInk,fontSize=22.sp,fontWeight=FontWeight.Bold)}}}
  val rows=listOf(listOf("7","8","9","×"),listOf("4","5","6","−"),listOf("1","2","3","+"),listOf("⌫","0",".","="));rows.forEach{row->Row(Modifier.fillMaxWidth().padding(vertical=3.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{key->val op=key in listOf("÷","×","−","+");val eq=key=="=";Box(Modifier.weight(1f).aspectRatio(1.38f).clip(RoundedCornerShape(25.dp)).background(if(eq)CurioInk else if(op)CurioRoseWood else Color.White.copy(.74f)).clickable{when{key=="⌫"->back();eq->equal();op->op(key);else->append(key)}},contentAlignment=Alignment.Center){if(key=="⌫")Icon(Icons.Outlined.Backspace,null,tint=CurioInk)else Text(key,color=if(eq||op)Color.White else CurioInk,fontSize=24.sp,fontWeight=FontWeight.Bold)}}}}
  Spacer(Modifier.height(6.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){Text("Advanced",color=CurioRoseDark,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.clip(RoundedCornerShape(50.dp)).clickable{h();advanced=!advanced}.padding(9.dp));if(display!="0"){Spacer(Modifier.width(8.dp));Icon(Icons.Outlined.ContentCopy,null,tint=CurioRoseWood,modifier=Modifier.size(17.dp).clickable{clipboard.setText(AnnotatedString(display))})}}
 }
 if(historyOpen)ModalBottomSheet(onDismissRequest={historyOpen=false},containerColor=CurioSurface){Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)){Row(Modifier.fillMaxWidth()){Text("Calculations",fontSize=25.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.weight(1f));Text("Clear",color=CurioRoseWood,fontWeight=FontWeight.Bold,modifier=Modifier.clickable{history=emptyList();saveHistory(context,history)}.padding(8.dp))};Spacer(Modifier.height(12.dp));history.forEach{item->Column(Modifier.fillMaxWidth().padding(vertical=5.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(.42f)).padding(15.dp)){Text(item.expression,color=CurioRoseDark);Text(item.result,color=CurioInk,fontSize=26.sp,fontWeight=FontWeight.ExtraBold)}}}}
}