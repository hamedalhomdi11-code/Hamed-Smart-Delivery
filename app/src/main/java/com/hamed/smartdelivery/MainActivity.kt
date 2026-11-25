package com.hamed.smartdelivery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay

private val Night = Color(0xFF07111D)
private val Panel = Color(0xFF101F2D)
private val Mint = Color(0xFF5EF0C2)
private val Cyan = Color(0xFF53C7F4)
private val Muted = Color(0xFF8EA5B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HamedApp() }
    }
}

@Composable
fun HamedApp() {
    var searching by remember { mutableStateOf(false) }
    var hasOrder by remember { mutableStateOf(false) }
    var english by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    var showDetails by remember { mutableStateOf(false) }
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { pulse = !pulse; delay(1250) } }
    val title = if (english) "Hamed Smart Delivery" else "حامد سمارت دليفري"
    CompositionLocalProvider(LocalLayoutDirection provides if (english) androidx.compose.ui.unit.LayoutDirection.Ltr else androidx.compose.ui.unit.LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColorScheme(background = Night, surface = Panel, primary = Mint)) {
            Box(Modifier.fillMaxSize().background(Night)) {
                Column(Modifier.fillMaxSize()) {
                    Header(title, english, { english = !english })
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        SmartMap(pulse)
                        Column(Modifier.align(Alignment.TopStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            MiniMetric("GPS", if (english) "Waiting" else "بانتظار الإشارة", Icons.Rounded.GpsFixed)
                            MiniMetric(if (english) "Network" else "الشبكة", "—", Icons.Rounded.Wifi)
                        }
                        Column(Modifier.align(Alignment.TopEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            MapAction(Icons.Rounded.MyLocation) { }
                            MapAction(Icons.Rounded.Layers) { showDetails = !showDetails }
                        }
                        if (showDetails) {
                            Surface(
                                modifier = Modifier.align(Alignment.Center),
                                color = Panel.copy(alpha = .97f),
                                shape = RoundedCornerShape(22.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Mint.copy(alpha = .4f))
                            ) {
                                Column(Modifier.padding(18.dp)) {
                                    Text(if (english) "Map layers" else "طبقات الخريطة", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text(if (english) "Live map provider will be connected in the maps phase." else "سيتم ربط مزود الخرائط الفعلي في مرحلة الخرائط.", color = Muted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Dashboard(searching, hasOrder, english, { searching = !searching; if (!searching) hasOrder = false }, { hasOrder = true; searching = false }, { hasOrder = false; searching = true })
                    NavigationBar(containerColor = Color(0xFF0B1723), contentColor = Mint) {
                        val labels = if (english) listOf("Drive", "Orders", "Places", "Profile") else listOf("الملاحة", "الطلبات", "التجمعات", "حسابي")
                        val icons = listOf(Icons.Rounded.Navigation, Icons.Rounded.ReceiptLong, Icons.Rounded.Restaurant, Icons.Rounded.Person)
                        labels.forEachIndexed { i, label -> NavigationBarItem(selected = selected == i, onClick = { selected = i }, icon = { Icon(icons[i], null) }, label = { Text(label, fontSize=10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor=Mint, selectedTextColor=Mint, indicatorColor=Mint.copy(alpha=.12f), unselectedIconColor=Muted, unselectedTextColor=Muted)) }
                    }
                }
            }
        }
    }
}

@Composable private fun Header(title: String, english: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF0B1723)).padding(horizontal=18.dp, vertical=14.dp), verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Mint, Cyan))), contentAlignment=Alignment.Center) { Icon(Icons.Rounded.Bolt, null, tint=Night, modifier=Modifier.size(27.dp)) }
        Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) { Text(title, color=Color.White, fontWeight=FontWeight.ExtraBold, fontSize=16.sp); Text("SMART ROUTE • DELIVERY INTELLIGENCE", color=Mint, fontSize=8.sp, letterSpacing=1.2.sp) }
        Surface(color=Panel, shape=CircleShape, modifier=Modifier.clickable { toggle() }) { Text(if(english) "عربي" else "EN", color=Mint, fontWeight=FontWeight.Bold, modifier=Modifier.padding(horizontal=13.dp, vertical=9.dp), fontSize=12.sp) }
    }
}

@Composable private fun SmartMap(pulse: Boolean) {
    val infinite = rememberInfiniteTransition(label="map")
    val dash by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing=LinearEasing)), label="route")
    Box(Modifier.fillMaxSize()) {
    Canvas(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF122536), Color(0xFF0A1724))))) {
        val w=size.width; val h=size.height
        val road=Color(0xFF294052); val thin=Color(0xFF1D3445)
        for(i in 0..8){ val y=h*i/8f; drawLine(thin, Offset(0f,y), Offset(w,y+35f), 2.dp.toPx()); val x=w*i/8f; drawLine(thin, Offset(x,0f), Offset(x+45f,h), 2.dp.toPx()) }
        val paths=listOf(listOf(Offset(-20f,h*.72f),Offset(w*.22f,h*.63f),Offset(w*.42f,h*.69f),Offset(w*.65f,h*.42f),Offset(w+20f,h*.35f)), listOf(Offset(w*.08f,-20f),Offset(w*.28f,h*.3f),Offset(w*.52f,h*.45f),Offset(w*.72f,h*.85f)))
        paths.forEach { p -> for(i in 0 until p.lastIndex) drawLine(road,p[i],p[i+1],9.dp.toPx(),cap=StrokeCap.Round) }
        val route=listOf(Offset(w*.16f,h*.78f),Offset(w*.35f,h*.68f),Offset(w*.52f,h*.72f),Offset(w*.68f,h*.48f),Offset(w*.82f,h*.37f))
        for(i in 0 until route.lastIndex) drawLine(Mint.copy(alpha=.18f),route[i],route[i+1],12.dp.toPx(),cap=StrokeCap.Round)
        for(i in 0 until route.lastIndex) drawLine(Mint,route[i],route[i+1],4.dp.toPx(),cap=StrokeCap.Round)
        val p=route[2]
        drawCircle(Mint.copy(alpha=.12f), 28.dp.toPx()*(if(pulse)1.2f else .9f), p)
        drawCircle(Mint, 8.dp.toPx(), p); drawCircle(Color.White,3.dp.toPx(),p)
        listOf(Offset(w*.27f,h*.29f),Offset(w*.76f,h*.66f),Offset(w*.87f,h*.22f)).forEachIndexed { i,pt -> drawCircle(if(i==0) Cyan else Color(0xFFFFC66D),7.dp.toPx(),pt); drawCircle(Color.White.copy(alpha=.7f),2.dp.toPx(),pt) }
        val car=Offset(w*.52f,h*.72f); drawCircle(Mint.copy(alpha=.2f),25.dp.toPx(),car); drawCircle(Mint,11.dp.toPx(),car); drawCircle(Night,5.dp.toPx(),car)
    }
    Column(Modifier.fillMaxWidth().padding(18.dp).align(Alignment.BottomStart)) { Text("RIYADH • LIVE NAVIGATION", color=Muted, fontSize=9.sp, letterSpacing=1.5.sp); Text("— : —", color=Color.White, fontSize=30.sp, fontWeight=FontWeight.Black); Text("بانتظار تفعيل الموقع والخرائط", color=Muted, fontSize=11.sp) }
    }
}

@Composable private fun MiniMetric(label:String, value:String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(color=Panel.copy(alpha=.94f), shape=RoundedCornerShape(14.dp), border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.06f))) { Row(Modifier.padding(horizontal=11.dp, vertical=8.dp), verticalAlignment=Alignment.CenterVertically) { Icon(icon,null,tint=Mint,modifier=Modifier.size(15.dp)); Spacer(Modifier.width(7.dp)); Column { Text(label,color=Muted,fontSize=9.sp); Text(value,color=Color.White,fontSize=11.sp,fontWeight=FontWeight.SemiBold) } } }
}
@Composable private fun MapAction(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick:()->Unit) { Surface(color=Panel.copy(alpha=.95f),shape=CircleShape,modifier=Modifier.size(43.dp).clickable { onClick() },border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.08f))) { Box(contentAlignment=Alignment.Center) { Icon(icon,null,tint=Color.White) } } }

@Composable private fun Dashboard(searching:Boolean, order:Boolean, english:Boolean, start:()->Unit, gotOrder:()->Unit, done:()->Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF0B1723)).padding(horizontal=17.dp, vertical=15.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(if(english) "DRIVER CONTROL" else "مركز تحكم المندوب", color=Muted, fontSize=9.sp, letterSpacing=1.4.sp); Text(if(order) if(english) "Delivery in progress" else "طلب قيد التوصيل" else if(searching) if(english) "Finding best restaurant cluster" else "نبحث عن أفضل تجمع مطاعم" else if(english) "Ready when you are" else "جاهز للانطلاق", color=Color.White, fontSize=17.sp, fontWeight=FontWeight.Bold) }; Surface(color=if(searching) Mint.copy(alpha=.13f) else Panel,shape=CircleShape) { Text(if(searching) "● LIVE" else "● IDLE", color=if(searching) Mint else Muted, fontSize=9.sp, modifier=Modifier.padding(horizontal=11.dp,vertical=7.dp)) } }
        Spacer(Modifier.height(13.dp))
        AnimatedContent(targetState=order, label="action") { isOrder -> if(isOrder) {
            Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) { Button(onClick=done,modifier=Modifier.weight(1f).height(54.dp),colors=ButtonDefaults.buttonColors(containerColor=Mint,contentColor=Night),shape=RoundedCornerShape(17.dp)) { Icon(Icons.Rounded.CheckCircle,null); Spacer(Modifier.width(7.dp)); Text(if(english) "Complete delivery" else "إنهاء التوصيل",fontWeight=FontWeight.Bold) }; OutlinedButton(onClick={},modifier=Modifier.height(54.dp),shape=RoundedCornerShape(17.dp),colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)) { Icon(Icons.Rounded.Route,null) } }
        } else {
            Button(onClick=if(searching) gotOrder else start,modifier=Modifier.fillMaxWidth().height(57.dp),shape=RoundedCornerShape(18.dp),colors=ButtonDefaults.buttonColors(containerColor=if(searching) Color(0xFFFFC66D) else Mint,contentColor=Night),elevation=ButtonDefaults.buttonElevation(defaultElevation=5.dp)) { Icon(if(searching) Icons.Rounded.LocalShipping else Icons.Rounded.Radar,null,modifier=Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Text(if(searching) if(english) "I GOT AN ORDER" else "وصلني طلب" else if(english) "FIND ORDERS" else "ابدأ البحث عن الطلبات",fontWeight=FontWeight.Black,fontSize=15.sp,letterSpacing=.3.sp) }
        } }
        Spacer(Modifier.height(11.dp)); LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { items(3) { i -> val txt=if(english) listOf("Fuel smart","Restaurant AI","Safe drive")[i] else listOf("وقود بذكاء","ذكاء المطاعم","قيادة آمنة")[i]; Surface(color=Panel,shape=CircleShape) { Text("✦  $txt",color=Muted,fontSize=10.sp,modifier=Modifier.padding(horizontal=11.dp,vertical=8.dp)) } } }
    }
}
