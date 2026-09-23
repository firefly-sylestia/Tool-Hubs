package com.sylestia.toolhubs.datawarden

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.DecimalFormat

private data class AppUsage(val label:String,val packageName:String,val uid:Int,val bytes:Long,val system:Boolean,val blocked:Boolean)
private const val PREFS="data_warden"
private const val MODE_KEY="firewall_mode"

class MainActivity:ComponentActivity(){
 override fun onCreate(state:Bundle?){super.onCreate(state);setContent{ToolHubsTheme{DataWardenApp(this)}}}
}

@Composable private fun DataWardenApp(context:Context){
 var tab by rememberSaveable{mutableIntStateOf(0)}
 var apps by remember{mutableStateOf(emptyList<AppUsage>())}
 var threshold by rememberSaveable{mutableFloatStateOf(500f)}
 var includeSystem by rememberSaveable{mutableStateOf(context.getSharedPreferences("data_warden",0).getBoolean(DataWardenService.KEY_INCLUDE_SYSTEM,true))}
 var background by rememberSaveable{mutableStateOf(context.getSharedPreferences("data_warden",0).getBoolean("background_enabled",false))}
 var loading by remember{mutableStateOf(false)}
 LaunchedEffect(Unit){threshold=context.getSharedPreferences("data_warden",0).getFloat(DataWardenService.KEY_THRESHOLD_MB,500f)}
 val scope=rememberCoroutineScope()
 fun refresh(){loading=true;scope.launch{val loaded=withContext(Dispatchers.IO){loadUsage(context,includeSystem)};withContext(Dispatchers.IO){enforceThreshold(context,loaded,threshold)};apps=withContext(Dispatchers.IO){loadUsage(context,includeSystem)};loading=false}}
 LaunchedEffect(includeSystem,threshold){refresh()}
 Scaffold(containerColor=CurioCream,bottomBar={
  NavigationBar(containerColor=CurioCream){
   val nav=listOf(Triple(Icons.Outlined.DataUsage,"Overview",0),Triple(Icons.Outlined.Apps,"Apps",1),Triple(Icons.Outlined.Settings,"Settings",2))
   nav.forEach{item->NavigationBarItem(selected=tab==item.third,onClick={tab=item.third},icon={Icon(item.first,null)},label={Text(item.second)})}
  }
 }){p->
  when(tab){
   0->Overview(apps,threshold,p,{refresh()})
   1->Apps(context,apps,p,{refresh()})
   else->Settings(context,threshold,{v->threshold=v;context.getSharedPreferences("data_warden",0).edit().putFloat(DataWardenService.KEY_THRESHOLD_MB,v).apply()},includeSystem,{v->includeSystem=v;context.getSharedPreferences("data_warden",0).edit().putBoolean(DataWardenService.KEY_INCLUDE_SYSTEM,v).apply()},background,{v->background=v;context.getSharedPreferences("data_warden",0).edit().putBoolean("background_enabled",v).apply();if(v)startProtection(context) else stopProtection(context)},p)
  }
 }
}

@Composable private fun Header(kicker:String,title:String){Column{Text(kicker.uppercase(),color=CurioRoseDark,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);Text(title,color=CurioInk,fontSize=30.sp,fontWeight=FontWeight.ExtraBold)}}

@Composable private fun Overview(apps:List<AppUsage>,threshold:Float,p:PaddingValues,refresh:()->Unit){
 val total=apps.sumOf{it.bytes};val over=apps.count{it.bytes>=threshold*1024*1024};val blocked=apps.count{it.blocked}
 LazyColumn(Modifier.fillMaxSize().padding(p),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{Header("DATA WARDEN","Your data, watched.")}
  item{Surface(shape=RoundedCornerShape(32.dp),color=CurioInk){Column(Modifier.padding(22.dp)){Text("TODAY",color=Color.White.copy(.6f),fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);Text(formatBytes(total),color=Color.White,fontSize=42.sp,fontWeight=FontWeight.Black);Text(over.toString()+" apps crossed "+formatMb(threshold)+" MB",color=Color.White.copy(.7f))}}}
  item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Stat("OVER LIMIT",over.toString(),Modifier.weight(1f));Stat("BLOCKED",blocked.toString(),Modifier.weight(1f))}}
  item{Text("BIGGEST USERS",color=CurioRoseDark,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)}
  items(apps.take(6),key={it.packageName}){AppRow(it)}
  item{OutlinedButton(onClick=refresh,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)){Icon(Icons.Outlined.Refresh,null);Spacer(Modifier.width(8.dp));Text("Refresh usage")}}
 }
}

@Composable private fun Stat(label:String,value:String,modifier:Modifier){Surface(modifier,shape=RoundedCornerShape(26.dp),color=Color.White.copy(.55f)){Column(Modifier.padding(17.dp)){Text(value,color=CurioInk,fontSize=27.sp,fontWeight=FontWeight.ExtraBold);Text(label,color=CurioRoseDark,fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp)}}}

@Composable private fun Apps(context:Context,apps:List<AppUsage>,p:PaddingValues,refresh:()->Unit){
 var query by rememberSaveable{mutableStateOf("")}
 val filtered=apps.filter{it.label.contains(query,true)||it.packageName.contains(query,true)}
 Column(Modifier.fillMaxSize().padding(p).padding(horizontal=18.dp)){
  Spacer(Modifier.height(18.dp));Header("INVENTORY","Every app, accounted for.");Spacer(Modifier.height(12.dp))
  OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("Search apps")},shape=RoundedCornerShape(20.dp))
  Spacer(Modifier.height(10.dp))
  LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=24.dp)){
   items(filtered,key={it.packageName}){app->AppRow(app,true){toggleBlock(context,app);refresh()}}
  }
 }
}

@Composable private fun AppRow(app:AppUsage,showAction:Boolean=false,onAction:()->Unit={}){
 Surface(shape=RoundedCornerShape(24.dp),color=Color.White.copy(.48f),modifier=Modifier.fillMaxWidth()){
  Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){
   Surface(Modifier.size(44.dp),shape=RoundedCornerShape(15.dp),color=CurioInk){Box(contentAlignment=Alignment.Center){Text(app.label.take(1).uppercase(),color=Color.White,fontWeight=FontWeight.Black)}}
   Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(app.label,color=CurioInk,fontWeight=FontWeight.Bold);Text(if(app.system)"SYSTEM • "+app.packageName else app.packageName,color=CurioRoseDark,fontSize=9.sp);Text(formatBytes(app.bytes)+" today",color=CurioInk,fontSize=12.sp,fontWeight=FontWeight.SemiBold)}
   if(showAction)TextButton(onClick=onAction){Text(if(app.blocked)"Allow" else "Block")}
  }
 }
}

@Composable private fun Settings(context:Context,threshold:Float,onThreshold:(Float)->Unit,includeSystem:Boolean,onSystem:(Boolean)->Unit,background:Boolean,onBackground:(Boolean)->Unit,p:PaddingValues){
 val usage=remember{usageAccessGranted(context)};val shizuku=remember{shizukuReady()}
 LazyColumn(Modifier.fillMaxSize().padding(p),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{Header("CONTROL ROOM","Rules & permissions")}
  item{Setting("Usage access","Required to inspect other apps.",{Button(onClick={context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))}){Text(if(usage)"Granted" else "Grant access")}})}
  item{Setting("Daily threshold","Crossing this amount marks an app for blocking.",{Text(formatMb(threshold)+" MB per day",fontWeight=FontWeight.ExtraBold,color=CurioInk);Slider(value=threshold,onValueChange=onThreshold,valueRange=50f..5000f,steps=49)})}
  item{Setting("System apps","Keep Android and preinstalled services visible.",{Switch(includeSystem,onSystem)})}
  item{Setting("Background protection","Keep Data Warden watching while the app is closed. Requires Usage Access and Shizuku for automatic blocking.",{Row(verticalAlignment=Alignment.CenterVertically){Text(if(background)"Active" else "Off",modifier=Modifier.weight(1f),color=CurioInk,fontWeight=FontWeight.Bold);Switch(background,onBackground)}})}
  item{Setting("Block engine","Shizuku is used only when Data Warden needs to enforce a per-app limit.",{Column{Text(if(shizuku)"Shizuku connected" else "Shizuku not connected",fontWeight=FontWeight.Bold,color=CurioRoseDark);if(!shizuku){Spacer(Modifier.height(8.dp));Button(onClick={Shizuku.requestPermission(100)}){Text("Grant Shizuku permission")}}}})}
  item{Text("Important: Android's public netpolicy blacklist restricts background data on metered networks. It is not a universal all-network kill switch. The UI deliberately reports this limitation.",color=CurioRoseDark,fontSize=11.sp)}
 }
}

@Composable private fun Setting(title:String,subtitle:String,content:@Composable ColumnScope.()->Unit){Surface(shape=RoundedCornerShape(28.dp),color=Color.White.copy(.52f)){Column(Modifier.padding(18.dp)){Text(title,color=CurioInk,fontSize=18.sp,fontWeight=FontWeight.ExtraBold);Text(subtitle,color=CurioRoseDark,fontSize=11.sp);Spacer(Modifier.height(10.dp));content()}}}

private fun loadUsage(context:Context,includeSystem:Boolean):List<AppUsage>{
 val pm=context.packageManager
 val packages=pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
 val end=System.currentTimeMillis();val start=end-86400000L
 val nsm=context.getSystemService(NetworkStatsManager::class.java);val blocked=blockedPackages(context)
 val stats=runCatching{nsm.queryDetails(ConnectivityManager.TYPE_WIFI,null,start,end)}.getOrNull()
 val totals=HashMap<Int,Long>()
 if(stats!=null){val b=NetworkStats.Bucket();while(stats.hasNextBucket()){stats.getNextBucket(b);totals[b.uid]=(totals[b.uid]?:0L)+b.rxBytes+b.txBytes};stats.close()}
 val mobile=runCatching{nsm.queryDetails(ConnectivityManager.TYPE_MOBILE,null,start,end)}.getOrNull()
 if(mobile!=null){val b=NetworkStats.Bucket();while(mobile.hasNextBucket()){mobile.getNextBucket(b);totals[b.uid]=(totals[b.uid]?:0L)+b.rxBytes+b.txBytes};mobile.close()}
 return packages.mapNotNull{a->if(!includeSystem&&(a.flags and ApplicationInfo.FLAG_SYSTEM)!=0)return@mapNotNull null;if(a.uid<10000)return@mapNotNull null;AppUsage(pm.getApplicationLabel(a).toString(),a.packageName,a.uid,totals[a.uid]?:0L,(a.flags and ApplicationInfo.FLAG_SYSTEM)!=0,blocked.contains(a.packageName))}.sortedByDescending{it.bytes}
}

private fun today():String=java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(java.util.Date())
(c:Context,a:AppUsage){
 val set=blockedPackages(c).toMutableSet();val block=!a.blocked
 if(block)set.add(a.packageName)else set.remove(a.packageName)
 c.getSharedPreferences(PREFS,0).edit().putStringSet(DataWardenService.KEY_BLOCKED_PACKAGES,set).putString("blocked_day",today()).apply()
 if(ShizukuFirewall.isReady()){ShizukuFirewall.setFirewallEnabled(c,true);ShizukuFirewall.setPackageNetworking(c,a.packageName,!block)}
}
private fun enforceThreshold(c:Context,apps:List<AppUsage>,threshold:Float){if(!ShizukuFirewall.isReady())return;ShizukuFirewall.setFirewallEnabled(c,true);val limit=(threshold*1024f*1024f).toLong();apps.filter{it.bytes>=limit&&!it.blocked}.forEach{toggleBlock(c,it)}}
private fun usageAccessGranted(c:Context):Boolean{val ops=c.getSystemService(AppOpsManager::class.java);return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),c.packageName)==AppOpsManager.MODE_ALLOWED}
private fun formatBytes(b:Long):String{if(b<1024)return b.toString()+" B";var v=b.toDouble();val u=arrayOf("KB","MB","GB");var i=-1;do{v/=1024;i++}while(v>=1024&&i<u.lastIndex);return DecimalFormat("0.##").format(v)+" "+u[i]}
private fun formatMb(m:Float)=if(m>=1024)DecimalFormat("0.##").format(m/1024f)+" GB" else DecimalFormat("0").format(m)

private fun enforceThreshold(c:Context,apps:List<AppUsage>,threshold:Float){if(!shizukuReady())return;val limit=(threshold*1024f*1024f).toLong();apps.filter{it.bytes>=limit&&!it.blocked}.forEach{toggleBlock(c,it)}}

private fun startProtection(c:Context){if(Build.VERSION.SDK_INT>=26)androidx.core.content.ContextCompat.startForegroundService(c,Intent(c,DataWardenService::class.java))else c.startService(Intent(c,DataWardenService::class.java))}
private fun stopProtection(c:Context){c.stopService(Intent(c,DataWardenService::class.java).apply{action=DataWardenService.ACTION_STOP})}
