@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.sylestia.toolhubs.musicanalyzer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlin.math.*

data class AudioAnalysis(val name:String,val durationMs:Long,val sampleRate:Int,val channels:Int,val rmsDb:Float,val peakDb:Float,val dominantHz:Float,val note:String,val waveform:List<Float>,val spectrum:List<Float>,val analyzedAt:Long=System.currentTimeMillis())

class MainActivity:ComponentActivity(){
 override fun onCreate(state:Bundle?){
  super.onCreate(state)
  setContent{ToolHubsTheme{MusicAnalyzerScreen()}}
 }
 fun requestMic(){requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),42)}
}

private fun db(v:Double):Float=(20.0*log10(kotlin.math.max(v,1e-7))).toFloat().coerceAtLeast(-120f)
private fun noteFor(f:Double):String{if(f<20.0||!f.isFinite())return "—";val n=arrayOf("C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B");val m=(69.0+12.0*log2(f/440.0)).roundToInt();return "${n[(m%12+12)%12]}${m/12-1}"}

private fun fft(r:DoubleArray,i:DoubleArray){var j=0;for(x in 1 until r.size){var b=r.size/2;while(j and b!=0){j=j xor b;b/=2};j=j xor b;if(x<j){var t=r[x];r[x]=r[j];r[j]=t;t=i[x];i[x]=i[j];i[j]=t}};var len=2;while(len<=r.size){val a=-2.0*PI/len;val wr0=cos(a);val wi0=sin(a);for(s in 0 until r.size step len){var wr=1.0;var wi=0.0;for(k in 0 until len/2){val ur=r[s+k];val ui=i[s+k];val vr=r[s+k+len/2]*wr-i[s+k+len/2]*wi;val vi=r[s+k+len/2]*wi+i[s+k+len/2]*wr;r[s+k]=ur+vr;i[s+k]=ui+vi;r[s+k+len/2]=ur-vr;i[s+k+len/2]=ui-vi;val nr=wr*wr0-wi*wi0;wi=wr*wi0+wi*wr0;wr=nr}};len*=2}}

private fun pitch(samples:FloatArray,rate:Int):Double{if(samples.size<64)return 0.0;val minLag=kotlin.math.max(2,rate/1000);val maxLag=kotlin.math.min(samples.size/2,rate/55);if(maxLag<=minLag)return 0.0;var best=-1.0;var lagBest=0;for(lag in minLag..maxLag){var c=0.0;var e1=0.0;var e2=0.0;var k=0;while(k+lag<samples.size){val a=samples[k].toDouble();val b=samples[k+lag].toDouble();c+=a*b;e1+=a*a;e2+=b*b;k++};val norm=c/sqrt(kotlin.math.max(1e-12,e1*e2));if(norm>best){best=norm;lagBest=lag}};return if(lagBest>0&&best>.45)rate.toDouble()/lagBest else 0.0}

private fun analyzePcm(pcm:FloatArray,rate:Int,ch:Int):Triple<List<Float>,List<Float>,Double>{if(pcm.isEmpty())return Triple(emptyList(),emptyList(),0.0);val frames=pcm.size/kotlin.math.max(1,ch);val mono=FloatArray(kotlin.math.min(frames,rate*12));for(f in mono.indices){var s=0f;for(c in 0 until ch){val idx=f*ch+c;if(idx<pcm.size)s+=pcm[idx]};mono[f]=s/kotlin.math.max(1,ch)};val wave=ArrayList<Float>(160);val block=kotlin.math.max(1,mono.size/160);var start=0;while(start<mono.size&&wave.size<160){val end=kotlin.math.min(mono.size,start+block);var p=0f;for(k in start until end)p=kotlin.math.max(p,abs(mono[k]));wave.add(p.coerceIn(0f,1f));start=end};val n=4096;val real=DoubleArray(n);val imag=DoubleArray(n);val source=mono.copyOf(kotlin.math.min(n,mono.size));for(k in source.indices)real[k]=source[k]*(.5-.5*cos(2*PI*k/kotlin.math.max(1,source.size-1)));fft(real,imag);val spec=ArrayList<Float>(96);for(b in 0 until 96){val lo=kotlin.math.max(1,2.0.pow(b/16.0).toInt());val hi=kotlin.math.min(n/2,kotlin.math.max(lo+1,2.0.pow((b+1)/16.0).toInt()));var m=0.0;for(q in lo until hi)m=kotlin.math.max(m,hypot(real[q],imag[q])/n.toDouble());spec.add((ln(1.0+m*60.0)/ln(61.0)).toFloat().coerceIn(0f,1f))};return Triple(wave,spec,pitch(source,rate))}

private fun displayName(c:Context,u:Uri):String{var n="Audio file";c.contentResolver.query(u,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())n=it.getString(0)?:n};return n}

private fun decodeAudio(c:Context,u:Uri):AudioAnalysis{val ex=MediaExtractor();ex.setDataSource(c,u,null);var track=-1;var fmt:MediaFormat?=null;for(i in 0 until ex.trackCount){val f=ex.getTrackFormat(i);if((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")){track=i;fmt=f;break}};if(track<0||fmt==null){ex.release();throw IllegalArgumentException("No audio track")};ex.selectTrack(track);val f=fmt!!;val rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE,44100);val ch=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT,1);val duration=f.getLong(MediaFormat.KEY_DURATION,0L)/1000L;val codec=MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!);codec.configure(f,null,null,0);codec.start();val out=ArrayList<Float>();val info=MediaCodec.BufferInfo();var inputDone=false;var outputDone=false;try{while(!outputDone&&out.size<rate*ch*120){if(!inputDone){val ii=codec.dequeueInputBuffer(10000);if(ii>=0){val b=codec.getInputBuffer(ii)!!;val size=ex.readSampleData(b,0);if(size<0){codec.queueInputBuffer(ii,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}else{codec.queueInputBuffer(ii,0,size,ex.sampleTime,0);ex.advance()}}};val oi=codec.dequeueOutputBuffer(info,10000);if(oi>=0){if(info.size>0){val b=codec.getOutputBuffer(oi)!!;b.position(info.offset);b.limit(info.offset+info.size);while(b.remaining()>=2){val lo=b.get().toInt()and 255;val hi=b.get().toInt();out.add(((((hi shl 8)or lo).toShort()).toInt()/32768f).coerceIn(-1f,1f))}};codec.releaseOutputBuffer(oi,false);if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)outputDone=true}else if(oi==MediaCodec.INFO_TRY_AGAIN_LATER&&inputDone&&out.size>rate*ch*8)outputDone=true}}finally{runCatching{codec.stop()};codec.release();ex.release()};var sum=0.0;var peak=0.0;for(v in out){sum+=v*v;peak=kotlin.math.max(peak,abs(v.toDouble()))};val rms=sqrt(sum/kotlin.math.max(1,out.size));val a=analyzePcm(out.toFloatArray(),rate,ch);return AudioAnalysis(displayName(c,u),duration,rate,ch,db(rms),db(peak),a.third.toFloat(),noteFor(a.third),a.first,a.second)}

@Composable private fun Graph(title:String,values:List<Float>){Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color.White.copy(.48f)).padding(15.dp)){Text(title,color=CurioInk,fontWeight=FontWeight.ExtraBold,fontSize=15.sp);Spacer(Modifier.height(9.dp));Canvas(Modifier.fillMaxWidth().height(125.dp)){if(values.isEmpty())return@Canvas;val path=Path();values.forEachIndexed{idx,v->val x=idx.toFloat()*size.width/kotlin.math.max(1,values.size-1);val y=size.height-v.coerceIn(0f,1f)*size.height;if(idx==0)path.moveTo(x,y)else path.lineTo(x,y)};drawPath(path,Brush.horizontalGradient(listOf(CurioRoseWood,CurioRoseDark)),style=androidx.compose.ui.graphics.drawscope.Stroke(3f,cap=StrokeCap.Round))}}}

@Composable private fun AnalyzerDetail(a:AudioAnalysis,onClose:()->Unit){ModalBottomSheet(onDismissRequest=onClose,containerColor=CurioCream){Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(18.dp)){Text(a.name,color=CurioInk,fontSize=23.sp,fontWeight=FontWeight.ExtraBold);Text("${a.sampleRate} Hz • ${a.channels} channel${if(a.channels==1)""else"s"} • ${a.durationMs/1000}s",color=CurioRoseDark,fontSize=11.sp);Spacer(Modifier.height(12.dp));Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)){Text("RMS ${a.rmsDb.roundToInt()} dB");Text("Peak ${a.peakDb.roundToInt()} dB");Text("${a.note} ${a.dominantHz.roundToInt()} Hz")};Spacer(Modifier.height(12.dp));Graph("Waveform",a.waveform);Spacer(Modifier.height(10.dp));Graph("Frequency spectrum",a.spectrum);Spacer(Modifier.height(14.dp))}}}

@Composable private fun MusicAnalyzerScreen(){val context=LocalContext.current;val activity=context as MainActivity;val scope=rememberCoroutineScope();var selected by remember{mutableStateOf<AudioAnalysis?>(null)};var selectedUri by remember{mutableStateOf<Uri?>(null)};var selectedName by remember{mutableStateOf<String?>(null)};var history by remember{mutableStateOf(emptyList<AudioAnalysis>())};var analyzing by remember{mutableStateOf(false)};var live by remember{mutableStateOf(false)};var bars by remember{mutableStateOf(List(48){0f})};var level by remember{mutableFloatStateOf(0f)};var peak by remember{mutableFloatStateOf(-120f)};var frequency by remember{mutableFloatStateOf(0f)};var note by remember{mutableStateOf("—")};var message by remember{mutableStateOf<String?>(null)};var liveJob by remember{mutableStateOf<Job?>(null)};val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){selectedUri=uri;selectedName=displayName(context,uri);selected=null;message="Ready to analyze "+displayName(context,uri)}};fun analyzeSelected(){val uri=selectedUri?:return;analyzing=true;message=null;scope.launch{val decoded=withContext(Dispatchers.IO){runCatching{decodeAudio(context,uri)}};analyzing=false;decoded.onSuccess{result->history=(listOf(result)+history.filter{it.name!=result.name}).take(30);selected=result;message="Analysis complete"}.onFailure{e->message=e.message ?: "Could not analyze this audio file."}}};LaunchedEffect(Unit){history=loadHistory(context)};Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CurioCream,Color(0xFFF3E7E9)))).statusBarsPadding().navigationBarsPadding().padding(16.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("AUDIO LAB",color=CurioRoseDark,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);Text("Music Analyzer",color=CurioInk,fontSize=25.sp,fontWeight=FontWeight.ExtraBold)};Icon(Icons.Outlined.History,null,tint=CurioInk,modifier=Modifier.size(25.dp).clickable{selected=history.firstOrNull()})};Spacer(Modifier.height(12.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(CurioInk).clickable{if(selectedUri==null)picker.launch(arrayOf("audio/*"))else if(!analyzing)analyzeSelected()},contentAlignment=Alignment.Center){Row(verticalAlignment=Alignment.CenterVertically){Icon(if(selectedUri==null)Icons.Outlined.FolderOpen else Icons.Outlined.History,null,tint=Color.White,modifier=Modifier.size(18.dp));Spacer(Modifier.width(7.dp));Text(if(analyzing)"Analyzing…" else if(selectedUri!=null)"Analyze file" else "Choose audio",color=Color.White,fontWeight=FontWeight.Bold)}};Box(Modifier.width(52.dp).height(52.dp).clip(RoundedCornerShape(26.dp)).background(if(live) CurioRoseDark else Color.White.copy(.58f)).clickable{
 if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
  activity.requestMic()
  message="Microphone permission is required for live analysis."
 }else{
  live=!live
  if(live){
   message="Listening…"
   liveJob=scope.launch(Dispatchers.IO){
    val sampleRate=44100
    val minBuffer=AudioRecord.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
    if(minBuffer<=0){message="This device cannot provide microphone input.";live=false;return@launch}
    val bufferSize=max(minBuffer*2,4096)
    val recorder=AudioRecord(MediaRecorder.AudioSource.MIC,sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,bufferSize)
    if(recorder.state!=AudioRecord.STATE_INITIALIZED){recorder.release();message="Microphone could not be initialized.";live=false;return@launch}
    val buffer=ShortArray(2048)
    val floats=FloatArray(2048)
    try{
     recorder.startRecording()
     while(isActive&&live){
      val read=recorder.read(buffer,0,buffer.size,AudioRecord.READ_BLOCKING)
      if(read>0){
       var sum=0.0;var p=0f
       for(i in 0 until read){val v=(buffer[i]/32768f);floats[i]=v;sum+=v.toDouble()*v;p=max(p,abs(v))}
       val rms=sqrt(sum/read.coerceAtLeast(1))
       val hz=pitch(floats.copyOf(read),sampleRate)
       val barsNow=List(48){idx->val start=idx*read/48;val end=max(start+1,(idx+1)*read/48).coerceAtMost(read);var m=0f;for(i in start until end)m=max(m,abs(floats[i]));m}
       withContext(Dispatchers.Main){
        level=rms.toFloat().coerceIn(0f,1f);peak=db(p.toDouble());frequency=hz.toFloat();note=noteFor(hz);bars=barsNow
       }
      }
     }
    }catch(e:Exception){withContext(Dispatchers.Main){message=e.message ?: "Microphone analysis stopped."}}
    finally{runCatching{recorder.stop()};recorder.release()}
   }
  }else{
   liveJob?.cancel();liveJob=null;message="Live analysis stopped."
  }
 }
},contentAlignment=Alignment.Center){Icon(if(live)Icons.Outlined.Stop else Icons.Outlined.Mic,null,tint=if(live)Color.White else CurioInk)}};Spacer(Modifier.height(12.dp));if(analyzing)Text("Decoding audio and building analysis…",color=CurioRoseDark,fontSize=12.sp);message?.let{Text(it,color=CurioRoseDark,fontSize=12.sp)};history.take(5).forEach{a->Column(Modifier.fillMaxWidth().padding(vertical=4.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(.43f)).clickable{selected=a}.padding(14.dp)){Text(a.name,color=CurioInk,fontWeight=FontWeight.Bold);Text("${a.note} • ${a.peakDb.roundToInt()} dB peak • ${a.durationMs/1000}s",color=CurioRoseDark,fontSize=11.sp)}};Spacer(Modifier.weight(1f));Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(30.dp)).background(Color.White.copy(.48f)).padding(18.dp)){if(selected!=null&&!live){val result=selected!!;Column(Modifier.fillMaxSize()){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("ANALYSIS",color=CurioRoseDark,fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);Text(result.name,color=CurioInk,fontSize=17.sp,fontWeight=FontWeight.ExtraBold);Text("${result.sampleRate} Hz • ${result.channels} ch • ${result.durationMs/1000}s",color=CurioRoseDark,fontSize=10.sp)}Text(result.note,color=CurioInk,fontSize=28.sp,fontWeight=FontWeight.Black)};Spacer(Modifier.height(12.dp));Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalAlignment=Alignment.Bottom){result.waveform.forEach{v->Box(Modifier.weight(1f).fillMaxHeight(kotlin.math.max(.04f,v)).clip(RoundedCornerShape(4.dp)).background(Brush.verticalGradient(listOf(CurioRoseWood,CurioRoseDark))))}};Spacer(Modifier.height(10.dp));Text("RMS ${result.rmsDb.roundToInt()} dB   •   Peak ${result.peakDb.roundToInt()} dB   •   ${result.dominantHz.roundToInt()} Hz",color=CurioRoseDark,fontSize=10.sp,fontWeight=FontWeight.SemiBold)}}else{Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.Bottom){bars.forEach{v->val av by animateFloatAsState(v,label="spectrum");Box(Modifier.weight(1f).fillMaxHeight(kotlin.math.max(.03f,av)).clip(RoundedCornerShape(8.dp)).background(Brush.verticalGradient(listOf(CurioRoseWood,CurioRoseDark))))}}}};Spacer(Modifier.height(10.dp));Text(if(live)"LIVE • ${(level*60f-60f).roundToInt()} dB • ${peak.roundToInt()} dB peak • ${frequency.roundToInt()} Hz • $note"else if(selectedUri!=null)(selectedName ?: "Audio selected")else"Select an audio file for detailed waveform and spectrum analysis",color=CurioRoseDark,fontSize=11.sp);if(selected!=null)AnalyzerDetail(selected!!){selected=null}}}

private fun loadHistory(c:Context):List<AudioAnalysis>{return emptyList()}
