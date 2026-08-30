package com.sylestia.toolhubs.musicanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { ToolHubsTheme { MusicAnalyzerScreen() } } }
    fun requestMic() { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 42) }
}

private fun fft(real: DoubleArray, imag: DoubleArray) {
    val n=real.size; var j=0
    for(i in 1 until n){var bit=n shr 1;while(j and bit!=0){j=j xor bit;bit=bit shr 1};j=j xor bit;if(i<j){val tr=real[i];real[i]=real[j];real[j]=tr}}
    var len=2
    while(len<=n){val a=-2.0*PI/len;val wlenR=cos(a);val wlenI=sin(a);for(i in 0 until n step len){var wr=1.0;var wi=0.0;for(k in 0 until len/2){val uR=real[i+k];val uI=imag[i+k];val vR=real[i+k+len/2]*wr-imag[i+k+len/2]*wi;val vI=real[i+k+len/2]*wi+imag[i+k+len/2]*wr;real[i+k]=uR+vR;imag[i+k]=uI+vI;real[i+k+len/2]=uR-vR;imag[i+k+len/2]=uI-vI;val nr=wr*wlenR-wi*wlenI;wi=wr*wlenI+wi*wlenR;wr=nr}};len=len shl 1}
}

private fun noteFor(freq:Double):String { if(freq<20) return "—"; val names=arrayOf("C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B"); val midi=(69+12*log2(freq/440.0)).roundToInt(); return "${names[(midi%12+12)%12]}${midi/12-1}" }

@Composable private fun MusicAnalyzerScreen() {
    val context=LocalContext.current;val activity=context as MainActivity
    var active by remember { mutableStateOf(false) };var level by remember { mutableStateOf(0f) };var peak by remember { mutableStateOf(0f) };var frequency by remember { mutableStateOf(0.0) };var bars by remember { mutableStateOf(List(32){0f}) }
    LaunchedEffect(active){if(!active)return@LaunchedEffect;if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){activity.requestMic();active=false;return@LaunchedEffect};withContext(Dispatchers.Default){val rate=44100;val size=1024;val min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<=0)return@withContext;val record=AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,max(min,size*2));val samples=ShortArray(size);val real=DoubleArray(size);val imag=DoubleArray(size);try{record.startRecording();while(isActive&&active){val read=record.read(samples,0,size);if(read<=0)continue;var sum=0.0;var maxSample=0.0;for(i in 0 until read){val v=samples[i].toDouble()/32768.0;sum+=v*v;maxSample=max(maxSample,abs(v));real[i]=v*(0.5-0.5*cos(2*PI*i/(size-1)));imag[i]=0.0};for(i in read until size){real[i]=0.0;imag[i]=0.0};fft(real,imag);val mags=FloatArray(32);var bestBin=1;var best=0.0;for(bin in 1 until size/2){val mag=sqrt(real[bin]*real[bin]+imag[bin]*imag[bin]);if(mag>best){best=mag;bestBin=bin};val band=min(31,(ln(bin.toDouble())/ln(size/2.0)*32).toInt());mags[band]=max(mags[band],(mag/120.0).coerceIn(0.02,1.0).toFloat())};val rms=sqrt(sum/max(1,read)).toFloat().coerceIn(0f,1f);withContext(Dispatchers.Main){level=rms;peak=max(peak*0.94f,maxSample.toFloat());frequency=bestBin*rate.toDouble()/size;bars=mags.toList()}}}finally{record.stop();record.release()}}}

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CurioCream,Color(0xFFF1E3E6)))).statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp)) {
        Row(Modifier.fillMaxWidth().height(70.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("AUDIO LAB",color=CurioRoseDark,fontSize=10.sp,letterSpacing=2.sp);Text("Music Analyzer",color=CurioInk,fontSize=25.sp,fontWeight=FontWeight.ExtraBold)};Box(Modifier.size(46.dp).clip(RoundedCornerShape(50.dp)).background(Color.White.copy(.5f)),contentAlignment=Alignment.Center){Icon(Icons.Outlined.GraphicEq,null,tint=CurioInk)}}
        Column(Modifier.fillMaxWidth().weight(1f),verticalArrangement=Arrangement.Center){Box(Modifier.fillMaxWidth().height(245.dp).clip(RoundedCornerShape(34.dp)).background(Brush.linearGradient(listOf(Color.White.copy(.62f),CurioSurface.copy(.68f)))).padding(horizontal=18.dp,vertical=20.dp)){Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.Bottom){bars.forEach{v->val h by animateFloatAsState(if(active)v else .06f,animationSpec=spring(stiffness=700f),label="bar");Box(Modifier.weight(1f).fillMaxHeight(h.coerceIn(.04f,1f)).clip(RoundedCornerShape(8.dp)).background(Brush.verticalGradient(listOf(CurioRoseWood,CurioRoseDark))))}};Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomStart){Column(Modifier.padding(16.dp)){Text(if(active)"LISTENING"else"READY",color=CurioRoseDark,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp);Text(if(frequency>20)"${frequency.roundToInt()} Hz  ·  ${noteFor(frequency)}"else"Tap analyze to listen",color=CurioInk,fontSize=14.sp,fontWeight=FontWeight.SemiBold)}}}
        Spacer(Modifier.height(16.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){InfoCard("LEVEL","${(level*100).roundToInt()}%",Modifier.weight(1f));InfoCard("PEAK","${(peak*100).roundToInt()}%",Modifier.weight(1f));InfoCard("NOTE",noteFor(frequency),Modifier.weight(1f))}
        Spacer(Modifier.height(16.dp));Box(Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(31.dp)).background(if(active)CurioRoseWood else CurioInk).clickable{if(active)active=false else active=true},contentAlignment=Alignment.Center){Row(verticalAlignment=Alignment.CenterVertically){Icon(if(active)Icons.Outlined.Stop else Icons.Outlined.Mic,null,tint=Color.White);Spacer(Modifier.width(9.dp));Text(if(active)"Stop analyzing"else"Analyze live audio",color=Color.White,fontSize=16.sp,fontWeight=FontWeight.Bold)}}
        Spacer(Modifier.height(9.dp));Text("Live spectrum • dominant frequency • musical note",color=CurioRoseDark.copy(.58f),fontSize=11.sp,modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center);Spacer(Modifier.height(4.dp))
    }
}

@Composable private fun InfoCard(title:String,value:String,modifier:Modifier){Column(modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(.42f)).padding(13.dp)){Text(title,color=CurioRoseDark,fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp);Spacer(Modifier.height(3.dp));Text(value,color=CurioInk,fontSize=18.sp,fontWeight=FontWeight.ExtraBold,maxLines=1)}}
