package com.hamed.smartdelivery

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

private val Night = Color(0xFF07111D)
private val Panel = Color(0xFF101F2D)
private val Mint = Color(0xFF5EF0C2)
private val Cyan = Color(0xFF53C7F4)
private val Muted = Color(0xFF8EA5B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContent { HamedApp() }
    }
}

@Composable
fun HamedApp() {
    val context = LocalContext.current
    var searching by remember { mutableStateOf(false) }
    var hasOrder by remember { mutableStateOf(false) }
    var trackingActive by remember { mutableStateOf(false) }
    var english by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasLocationPermission) {
            ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_START))
            trackingActive = true
        }
    }
    val title = if (english) "Hamed Smart Delivery" else "حامد سمارت دليفري"
    CompositionLocalProvider(LocalLayoutDirection provides if (english) androidx.compose.ui.unit.LayoutDirection.Ltr else androidx.compose.ui.unit.LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColorScheme(background = Night, surface = Panel, primary = Mint)) {
            Column(Modifier.fillMaxSize().background(Night)) {
                Header(title, english) { english = !english }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    SmartMap(hasLocationPermission)
                    Column(Modifier.align(Alignment.TopStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        MiniMetric("GPS", if (trackingActive) if (english) "Tracking active" else "التتبع يعمل" else if (hasLocationPermission) if (english) "Ready" else "جاهز" else if (english) "Permission needed" else "بانتظار إذن الموقع", Icons.Rounded.GpsFixed)
                        MiniMetric(if (english) "Map" else "الخريطة", "OpenStreetMap", Icons.Rounded.Map)
                    }
                    Column(Modifier.align(Alignment.TopEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        MapAction(Icons.Rounded.MyLocation) {
                            if (!hasLocationPermission) {
                                locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            } else if (trackingActive) {
                                context.stopService(Intent(context, LocationTrackingService::class.java))
                                trackingActive = false
                            } else {
                                ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_START))
                                trackingActive = true
                            }
                        }
                        MapAction(Icons.Rounded.Layers) { }
                    }
                    Column(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalAlignment = Alignment.End) {
                        Surface(color = Panel.copy(alpha = .9f), shape = RoundedCornerShape(8.dp)) {
                            Text("© OpenStreetMap contributors", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                        }
                    }
                }
                Dashboard(searching, hasOrder, english,
                    { searching = !searching; if (!searching) hasOrder = false },
                    { hasOrder = true; searching = false },
                    { hasOrder = false; searching = true })
                NavigationBar(containerColor = Color(0xFF0B1723), contentColor = Mint) {
                    val labels = if (english) listOf("Drive", "Orders", "Places", "Profile") else listOf("الملاحة", "الطلبات", "التجمعات", "حسابي")
                    val icons = listOf(Icons.Rounded.Navigation, Icons.Rounded.ReceiptLong, Icons.Rounded.Restaurant, Icons.Rounded.Person)
                    labels.forEachIndexed { i, label ->
                        NavigationBarItem(selected = selected == i, onClick = { selected = i }, icon = { Icon(icons[i], null) },
                            label = { Text(label, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Mint, selectedTextColor = Mint, indicatorColor = Mint.copy(alpha = .12f), unselectedIconColor = Muted, unselectedTextColor = Muted))
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, english: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF0B1723)).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Mint, Cyan))), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Bolt, null, tint = Night, modifier = Modifier.size(27.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            Text("SMART ROUTE • DELIVERY INTELLIGENCE", color = Mint, fontSize = 8.sp, letterSpacing = 1.2.sp)
        }
        Surface(color = Panel, shape = CircleShape, modifier = Modifier.clickable { toggle() }) {
            Text(if (english) "عربي" else "EN", color = Mint, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp), fontSize = 12.sp)
        }
    }
}

@Composable
private fun SmartMap(hasLocationPermission: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var userMarker by remember { mutableStateOf<Marker?>(null) }
    val mapView = remember {
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.setStyle("https://tiles.openfreemap.org/styles/liberty") {
                map.uiSettings.isAttributionEnabled = true
                map.uiSettings.isLogoEnabled = true
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(24.7136, 46.6753))
                    .zoom(13.5)
                    .tilt(50.0)
                    .build()
            }
        }
    }

    DisposableEffect(mapLibreMap, hasLocationPermission) {
        val map = mapLibreMap
        if (map == null || !hasLocationPermission) {
            onDispose { }
        } else {
            val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            var firstFix = true
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val point = LatLng(location.latitude, location.longitude)
                    val existing = userMarker
                    if (existing == null) {
                        userMarker = map.addMarker(MarkerOptions().position(point).title("موقع المندوب"))
                    } else {
                        existing.position = point
                    }
                    if (firstFix) {
                        firstFix = false
                        map.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder().target(point).zoom(16.0).tilt(50.0).build()
                            ),
                            700
                        )
                    }
                }
            }
            try {
                val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                locationManager.requestLocationUpdates(provider, 3000L, 5f, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
                // Permission may be revoked while the map is open.
            } catch (_: IllegalArgumentException) {
                // No location provider is available on this device.
            }
            onDispose {
                try { locationManager.removeUpdates(listener) } catch (_: Exception) { }
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { mapView },
        update = { }
    )
}

@Composable
private fun MiniMetric(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(color = Panel.copy(alpha = .94f), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Mint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Column {
                Text(label, color = Muted, fontSize = 9.sp)
                Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MapAction(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(color = Panel.copy(alpha = .95f), shape = CircleShape, modifier = Modifier.size(43.dp).clickable { onClick() }) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White) }
    }
}

@Composable
private fun Dashboard(searching: Boolean, order: Boolean, english: Boolean, start: () -> Unit, gotOrder: () -> Unit, done: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF0B1723)).padding(horizontal = 17.dp, vertical = 15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (english) "DRIVER CONTROL" else "مركز تحكم المندوب", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                Text(if (order) if (english) "Delivery in progress" else "طلب قيد التوصيل" else if (searching) if (english) "Searching mode" else "وضع البحث عن الطلبات" else if (english) "Ready when you are" else "جاهز للانطلاق", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Surface(color = if (searching) Mint.copy(alpha = .13f) else Panel, shape = CircleShape) {
                Text(if (searching) "● LIVE" else "● IDLE", color = if (searching) Mint else Muted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
            }
        }
        Spacer(Modifier.height(13.dp))
        AnimatedContent(targetState = order, label = "action") { isOrder ->
            if (isOrder) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(onClick = done, modifier = Modifier.weight(1f).height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Night), shape = RoundedCornerShape(17.dp)) {
                        Icon(Icons.Rounded.CheckCircle, null); Spacer(Modifier.width(7.dp)); Text(if (english) "Complete delivery" else "إنهاء التوصيل", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = {}, modifier = Modifier.height(54.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                        Icon(Icons.Rounded.Route, null)
                    }
                }
            } else {
                Button(onClick = if (searching) gotOrder else start, modifier = Modifier.fillMaxWidth().height(57.dp), shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (searching) Color(0xFFFFC66D) else Mint, contentColor = Night)) {
                    Icon(if (searching) Icons.Rounded.LocalShipping else Icons.Rounded.Radar, null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (searching) if (english) "I GOT AN ORDER" else "وصلني طلب" else if (english) "FIND ORDERS" else "ابدأ البحث عن الطلبات", fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = .3.sp)
                }
            }
        }
        Spacer(Modifier.height(11.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(if (english) listOf("Fuel smart", "Restaurant AI", "Safe drive") else listOf("وقود بذكاء", "ذكاء المطاعم", "قيادة آمنة")) { label ->
                Surface(color = Panel, shape = CircleShape) { Text("✦  $label", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) }
            }
        }
    }
}
