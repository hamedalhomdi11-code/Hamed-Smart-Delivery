package com.hamed.smartdelivery

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.graphics.Color as AndroidColor
import android.os.Looper
import android.os.Build
import android.speech.tts.TextToSpeech
import java.util.Locale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import com.hamed.smartdelivery.domain.DriverCommand
import com.hamed.smartdelivery.domain.DriverMode
import com.hamed.smartdelivery.domain.DriverReducer
import com.hamed.smartdelivery.domain.DriverSnapshot

private val Night = Color(0xFF07111D)
private val Panel = Color(0xFF101F2D)
private val Mint = Color(0xFF5EF0C2)
private val Cyan = Color(0xFF53C7F4)
private val Muted = Color(0xFF8EA5B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString
        setContent { HamedApp(DestinationLinkParser.parse(sharedText)) }
    }
}

@Composable
fun HamedApp(initialDestination: DeliveryDestination? = null) {
    val context = LocalContext.current
    var driverSnapshot by remember { mutableStateOf(DriverSnapshot()) }
    var trackingActive by remember { mutableStateOf(false) }
    var perspective3D by remember { mutableStateOf(true) }
    var english by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    var searchRequestId by remember { mutableIntStateOf(0) }
    var pendingSearchAfterPermission by remember { mutableStateOf(false) }
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
            if (pendingSearchAfterPermission) {
                searchRequestId += 1
                pendingSearchAfterPermission = false
            }
        }
    }
    val requestLocationPermissions: () -> Unit = {
        val requested = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
        locationPermissionLauncher.launch(requested.toTypedArray())
    }
    val title = if (english) "Hamed Smart Delivery" else "حامد سمارت دليفري"
    val toggleTracking: () -> Unit = {
        if (!hasLocationPermission) {
            requestLocationPermissions()
        } else if (trackingActive) {
            context.stopService(Intent(context, LocationTrackingService::class.java))
            trackingActive = false
        } else {
            ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_START))
            trackingActive = true
        }
    }
    CompositionLocalProvider(LocalLayoutDirection provides if (english) androidx.compose.ui.unit.LayoutDirection.Ltr else androidx.compose.ui.unit.LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColorScheme(background = Night, surface = Panel, primary = Mint)) {
            Column(Modifier.fillMaxSize().background(Night)) {
                Header(title, english) { english = !english }
                if (selected == 0) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        SmartMap(hasLocationPermission, initialDestination, trackingActive, perspective3D, searchRequestId)
                        Column(Modifier.align(Alignment.TopStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            MiniMetric("GPS", if (trackingActive) if (english) "Tracking active" else "التتبع يعمل" else if (hasLocationPermission) if (english) "Ready" else "جاهز" else if (english) "Permission needed" else "بانتظار إذن الموقع", Icons.Rounded.GpsFixed)
                            MiniMetric(if (english) "Map" else "الخريطة", "MapLibre • OSM", Icons.Rounded.Map)
                        }
                        Column(Modifier.align(Alignment.TopEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            MapAction(Icons.Rounded.MyLocation, toggleTracking)
                            MapAction(Icons.Rounded.Layers) { perspective3D = !perspective3D }
                        }
                        Column(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalAlignment = Alignment.End) {
                            Surface(color = Panel.copy(alpha = .9f), shape = RoundedCornerShape(8.dp)) {
                                Text("© OpenStreetMap contributors", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                            }
                        }
                    }
                    Dashboard(driverSnapshot, english) { command ->
                        if (command == DriverCommand.StartSearch) {
                            if (!hasLocationPermission) {
                                pendingSearchAfterPermission = true
                                requestLocationPermissions()
                            } else searchRequestId += 1
                        }
                        val transition = DriverReducer.reduce(driverSnapshot, command)
                        if (transition.accepted) driverSnapshot = transition.snapshot
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(18.dp)) {
                        when (selected) {
                            1 -> OrdersScreen(driverSnapshot, english)
                            2 -> PlacesScreen(english)
                            else -> ProfileScreen(english, { english = !english }, trackingActive, toggleTracking)
                        }
                    }
                }
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

private fun navigationLookAhead(latitude: Double, longitude: Double, bearingDegrees: Double, distanceMeters: Double): LatLng {
    val earthRadius = 6371000.0
    val angular = distanceMeters / earthRadius
    val bearing = Math.toRadians(bearingDegrees)
    val lat1 = Math.toRadians(latitude)
    val lon1 = Math.toRadians(longitude)
    val lat2 = kotlin.math.asin(kotlin.math.sin(lat1) * kotlin.math.cos(angular) +
        kotlin.math.cos(lat1) * kotlin.math.sin(angular) * kotlin.math.cos(bearing))
    val lon2 = lon1 + kotlin.math.atan2(
        kotlin.math.sin(bearing) * kotlin.math.sin(angular) * kotlin.math.cos(lat1),
        kotlin.math.cos(angular) - kotlin.math.sin(lat1) * kotlin.math.sin(lat2)
    )
    return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
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
private fun SmartMap(hasLocationPermission: Boolean, destination: DeliveryDestination?, trackingActive: Boolean, perspective3D: Boolean, searchRequestId: Int) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var activeDestination by remember { mutableStateOf(destination) }
    var lastProcessedSearch by remember { mutableIntStateOf(0) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var userMarker by remember { mutableStateOf<Marker?>(null) }
    var routeStatus by remember(activeDestination) { mutableStateOf(if (activeDestination == null) "" else "جارٍ انتظار موقع GPS لحساب المسار") }
    var nextTurnInstruction by remember(activeDestination) { mutableStateOf("") }
    var routePoints by remember(activeDestination) { mutableStateOf<List<LatLng>>(emptyList()) }
    var routeSteps by remember(activeDestination) { mutableStateOf<List<RouteStep>>(emptyList()) }
    var currentStepIndex by remember(activeDestination) { mutableIntStateOf(0) }
    var routeRequested by remember(activeDestination) { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    val speech = remember(context) { TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS } }
    LaunchedEffect(ttsReady) {
        if (ttsReady) speech.language = Locale("ar", "SA")
    }
    DisposableEffect(speech) {
        onDispose { speech.stop(); speech.shutdown() }
    }
    val scope = rememberCoroutineScope()
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
                    .target(activeDestination?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(24.7136, 46.6753))
                    .zoom(if (activeDestination == null) 13.5 else 15.0)
                    .tilt(if (perspective3D) 50.0 else 0.0)
                    .build()
                if (activeDestination != null) {
                    map.addMarker(MarkerOptions().position(LatLng(activeDestination!!.latitude, activeDestination!!.longitude)).title("وجهة التوصيل"))
                }
                styleReady = true
            }
        }
    }

    LaunchedEffect(mapLibreMap, styleReady, perspective3D) {
        if (styleReady) mapLibreMap?.let { map ->
            val current = map.cameraPosition
            map.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(current.target).zoom(current.zoom).bearing(current.bearing)
                    .tilt(if (perspective3D) 50.0 else 0.0).build()
            ), 500)
        }
    }

    DisposableEffect(mapLibreMap, styleReady, hasLocationPermission, activeDestination, trackingActive, perspective3D, searchRequestId) {
        val map = mapLibreMap
        if (map == null || !styleReady || !hasLocationPermission) {
            onDispose { }
        } else {
            val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            var firstFix = true
            var lastCameraLocation: Location? = null
            var lastRerouteAt = 0L
            var routePolyline: org.maplibre.android.annotations.Polyline? = null
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val point = LatLng(location.latitude, location.longitude)
                    val existing = userMarker
                    if (existing == null) {
                        userMarker = map.addMarker(MarkerOptions().position(point).title("موقع المندوب"))
                    } else {
                        existing.position = point
                    }
                    val previousCameraLocation = lastCameraLocation
                    val navigating = activeDestination != null
                    val shouldFollow = firstFix || ((trackingActive || navigating) && (previousCameraLocation == null || previousCameraLocation.distanceTo(location) >= 4f))
                    if (shouldFollow) {
                        firstFix = false
                        val bearing = if (location.hasBearing() && location.speed >= 1.2f) location.bearing.toDouble() else map.cameraPosition.bearing
                        map.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder().target(if (navigating) navigationLookAhead(location.latitude, location.longitude, bearing, 115.0) else point)
                                    .zoom(if (navigating) 18.0 else 16.5)
                                    .tilt(if (perspective3D || navigating) 58.0 else 0.0).bearing(bearing).build()
                            ),
                            850
                        )
                        lastCameraLocation = Location(location)
                    }
                    if (searchRequestId > lastProcessedSearch) {
                        lastProcessedSearch = searchRequestId
                        routeStatus = "جارٍ البحث الحي عن تجمعات المطاعم حولك"
                        scope.launch {
                            try {
                                val clusters = withContext(Dispatchers.IO) {
                                    RestaurantDiscoveryClient.findNearestClusters(location.latitude, location.longitude)
                                }
                                val best = clusters.firstOrNull()
                                if (best == null) {
                                    routeStatus = "لم تُعثر على مطاعم قريبة في بيانات الخريطة"
                                } else {
                                    clusters.take(8).forEach { cluster ->
                                        map.addMarker(MarkerOptions().position(LatLng(cluster.latitude, cluster.longitude))
                                            .title("تجمع مطاعم • ${cluster.restaurantCount}"))
                                    }
                                    activeDestination = DeliveryDestination(best.latitude, best.longitude)
                                    routeRequested = false
                                    routeStatus = "أفضل تجمع: ${best.restaurantCount} مطعم • ${String.format(Locale.US, "%.1f", best.distanceMeters / 1000.0)} كم"
                                }
                            } catch (error: Exception) {
                                routeStatus = "تعذر البحث عن المطاعم: ${error.message ?: "تحقق من الإنترنت"}"
                            }
                        }
                    }
                    if (activeDestination != null && !routeRequested) {
                        routeRequested = true
                        routeStatus = "جارٍ حساب المسار"
                        scope.launch {
                            val route = withContext(Dispatchers.IO) {
                                OsrmRouteClient.fetchRoute(location.latitude, location.longitude, activeDestination!!.latitude, activeDestination!!.longitude)
                            }
                            if (route != null && route.points.size > 1) {
                                routePolyline?.let { map.removePolyline(it) }
                                routePolyline = map.addPolyline(
                                    PolylineOptions().addAll(route.points)
                                        .color(AndroidColor.rgb(94, 240, 194))
                                        .width(6f)
                                )
                                routePoints = route.points
                                routeSteps = route.steps
                                currentStepIndex = 0
                                nextTurnInstruction = route.steps.firstOrNull()?.instruction ?: "واصل السير على المسار"
                                if (ttsReady) speech.speak(nextTurnInstruction, TextToSpeech.QUEUE_FLUSH, null, "hamed-next-turn")
                                routeStatus = "تم رسم المسار • " + (route.distanceMeters / 1000.0).toInt() + " كم • " + (route.durationSeconds / 60.0).toInt() + " د"
                            } else {
                                routeStatus = "تعذر حساب المسار حالياً"
                            }
                        }
                    }
                    val currentStep = routeSteps.getOrNull(currentStepIndex)
                    val maneuverPoint = currentStep?.location
                    if (maneuverPoint != null) {
                        val distanceToManeuver = FloatArray(1)
                        Location.distanceBetween(location.latitude, location.longitude, maneuverPoint.latitude, maneuverPoint.longitude, distanceToManeuver)
                        if (distanceToManeuver[0] <= 45f && currentStepIndex < routeSteps.lastIndex) {
                            currentStepIndex += 1
                            nextTurnInstruction = routeSteps.getOrNull(currentStepIndex)?.instruction ?: "وصلت إلى وجهتك"
                            if (ttsReady) speech.speak(nextTurnInstruction, TextToSpeech.QUEUE_FLUSH, null, "hamed-turn-$currentStepIndex")
                        }
                    }
                    val currentRoute = routePoints
                    if (activeDestination != null && currentRoute.size > 1) {
                        val nearestDistance = currentRoute.minOf { routePoint ->
                            val result = FloatArray(1)
                            Location.distanceBetween(location.latitude, location.longitude, routePoint.latitude, routePoint.longitude, result)
                            result[0]
                        }
                        val now = System.currentTimeMillis()
                        if (nearestDistance > 85f && now - lastRerouteAt > 20000L) {
                            lastRerouteAt = now
                            routeStatus = "خرجت عن المسار • جارٍ إعادة التوجيه"
                            scope.launch {
                                val updated = withContext(Dispatchers.IO) {
                                    OsrmRouteClient.fetchRoute(location.latitude, location.longitude, activeDestination!!.latitude, activeDestination!!.longitude)
                                }
                                if (updated != null && updated.points.size > 1) {
                                    routePolyline?.let { map.removePolyline(it) }
                                    routePolyline = map.addPolyline(
                                        PolylineOptions().addAll(updated.points)
                                            .color(AndroidColor.rgb(94, 240, 194))
                                            .width(6f)
                                    )
                                    routePoints = updated.points
                                    routeSteps = updated.steps
                                    currentStepIndex = 0
                                    nextTurnInstruction = updated.steps.firstOrNull()?.instruction ?: "واصل السير على المسار"
                                    if (ttsReady) speech.speak("تم تحديث المسار. $nextTurnInstruction", TextToSpeech.QUEUE_FLUSH, null, "hamed-reroute")
                                    routeStatus = "تم تحديث المسار • " + (updated.distanceMeters / 1000.0).toInt() + " كم"
                                } else {
                                    routeStatus = "تعذر تحديث المسار حالياً"
                                }
                            }
                        }
                    }
                }
            }
            try {
                val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                locationManager.requestLocationUpdates(provider, 3000L, 5f, listener, Looper.getMainLooper())
                // Use a cached fix immediately: discovery and route calculation must work while the driver is stationary.
                (locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let { listener.onLocationChanged(it) }
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

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { }
        )
        if (nextTurnInstruction.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 70.dp, vertical = 12.dp),
                color = Panel.copy(alpha = .96f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Navigation, null, tint = Mint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(nextTurnInstruction, color = Color.White, fontSize = 12.sp, maxLines = 2)
                }
            }
        }
        if (routeStatus.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                color = Panel.copy(alpha = .94f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(routeStatus, color = Mint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
    }
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
private fun Dashboard(snapshot: DriverSnapshot, english: Boolean, onCommand: (DriverCommand) -> Unit) {
    val mode = snapshot.mode
    val title = when (mode) {
        DriverMode.IDLE -> if (english) "Ready when you are" else "جاهز للانطلاق"
        DriverMode.SEARCHING -> if (english) "Searching for orders" else "البحث عن الطلبات"
        DriverMode.ORDER_RECEIVED -> if (english) "Order received" else "تم استلام الطلب"
        DriverMode.TO_RESTAURANT -> if (english) "Heading to restaurant" else "في الطريق إلى المطعم"
        DriverMode.PICKUP -> if (english) "Order picked up" else "تم استلام الطلب من المطعم"
        DriverMode.TO_CUSTOMER -> if (english) "Heading to customer" else "في الطريق إلى العميل"
        DriverMode.COMPLETED -> if (english) "Delivery completed" else "اكتمل التوصيل"
        DriverMode.SAFE_MODE -> if (english) "Safe mode" else "وضع الأمان"
    }
    Column(Modifier.fillMaxWidth().background(Color(0xFF0B1723)).padding(horizontal = 17.dp, vertical = 15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (english) "DRIVER CONTROL" else "مركز تحكم المندوب", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Surface(color = if (mode == DriverMode.SEARCHING) Mint.copy(alpha = .13f) else Panel, shape = CircleShape) {
                Text(mode.name.replace('_', ' '), color = if (mode == DriverMode.SEARCHING) Mint else Muted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
            }
        }
        Spacer(Modifier.height(13.dp))
        AnimatedContent(targetState = mode, label = "driver-mode") { currentMode ->
            val currentCommand = when (currentMode) {
                DriverMode.IDLE, DriverMode.COMPLETED -> DriverCommand.StartSearch
                DriverMode.SEARCHING -> DriverCommand.OrderReceived
                DriverMode.ORDER_RECEIVED -> DriverCommand.NavigateToRestaurant
                DriverMode.TO_RESTAURANT -> DriverCommand.ConfirmPickup
                DriverMode.PICKUP -> DriverCommand.NavigateToCustomer
                DriverMode.TO_CUSTOMER -> DriverCommand.CompleteDelivery
                DriverMode.SAFE_MODE -> DriverCommand.ExitSafeMode
            }
            val currentLabel = when (currentMode) {
                DriverMode.IDLE, DriverMode.COMPLETED -> if (english) "FIND ORDERS" else "ابدأ البحث عن الطلبات"
                DriverMode.SEARCHING -> if (english) "I GOT AN ORDER" else "وصلني طلب"
                DriverMode.ORDER_RECEIVED -> if (english) "NAVIGATE TO RESTAURANT" else "توجه إلى المطعم"
                DriverMode.TO_RESTAURANT -> if (english) "CONFIRM PICKUP" else "تأكيد استلام الطلب"
                DriverMode.PICKUP -> if (english) "START CUSTOMER TRIP" else "انطلق إلى العميل"
                DriverMode.TO_CUSTOMER -> if (english) "COMPLETE DELIVERY" else "تأكيد التسليم"
                DriverMode.SAFE_MODE -> if (english) "RESUME" else "استئناف العمل"
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onCommand(currentCommand) }, modifier = Modifier.fillMaxWidth().height(57.dp), shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (currentMode == DriverMode.SEARCHING) Color(0xFFFFC66D) else Mint, contentColor = Night)) {
                    Icon(
                        when (currentMode) {
                            DriverMode.IDLE, DriverMode.COMPLETED -> Icons.Rounded.Radar
                            DriverMode.SEARCHING -> Icons.Rounded.LocalShipping
                            DriverMode.TO_CUSTOMER, DriverMode.TO_RESTAURANT -> Icons.Rounded.Navigation
                            DriverMode.SAFE_MODE -> Icons.Rounded.PlayArrow
                            else -> Icons.Rounded.CheckCircle
                        },
                        null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(currentLabel, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = .2.sp)
                }
                if (currentMode == DriverMode.SEARCHING) {
                    OutlinedButton(onClick = { onCommand(DriverCommand.StopSearch) }, modifier = Modifier.fillMaxWidth().height(42.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Muted), shape = RoundedCornerShape(14.dp)) {
                        Text(if (english) "STOP SEARCH" else "إيقاف البحث")
                    }
                }
            }
        }
        Spacer(Modifier.height(11.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(if (english) listOf("Fuel smart", "Restaurant AI", "Safe drive") else listOf("وقود بذكاء", "ذكاء المطاعم", "قيادة آمنة")) { label ->
                Surface(color = Panel, shape = CircleShape) {
                    Text("✦  $label", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun OrdersScreen(snapshot: DriverSnapshot, english: Boolean) {
    val current = when (snapshot.mode) {
        DriverMode.IDLE -> if (english) "No active delivery" else "لا يوجد توصيل نشط"
        DriverMode.SEARCHING -> if (english) "Searching for an order" else "البحث عن طلب"
        DriverMode.ORDER_RECEIVED -> if (english) "Order received" else "تم استلام طلب"
        DriverMode.TO_RESTAURANT -> if (english) "On the way to restaurant" else "في الطريق إلى المطعم"
        DriverMode.PICKUP -> if (english) "Picked up from restaurant" else "تم الاستلام من المطعم"
        DriverMode.TO_CUSTOMER -> if (english) "On the way to customer" else "في الطريق إلى العميل"
        DriverMode.COMPLETED -> if (english) "Last delivery completed" else "اكتمل آخر توصيل"
        DriverMode.SAFE_MODE -> if (english) "Safe mode is active" else "وضع الأمان مفعّل"
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (english) "MY DELIVERY" else "طلباتي", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (english) "CURRENT DRIVER STATE" else "حالة المندوب الحالية", color = Muted, fontSize = 10.sp)
                Text(current, color = Mint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (english) "This state is controlled locally. No private HungerStation account data is read." else "هذه الحالة تُدار محلياً، ولا تتم قراءة بيانات حساب هنقرستيشن الخاصة.", color = Muted, fontSize = 12.sp)
            }
        }
        Text(if (english) "Delivery stages" else "مراحل التوصيل", color = Color.White, fontWeight = FontWeight.SemiBold)
        listOf(
            DriverMode.ORDER_RECEIVED to if (english) "Order received" else "استلام الطلب",
            DriverMode.TO_RESTAURANT to if (english) "Go to restaurant" else "التوجه للمطعم",
            DriverMode.PICKUP to if (english) "Confirm pickup" else "تأكيد الاستلام",
            DriverMode.TO_CUSTOMER to if (english) "Go to customer" else "التوجه للعميل",
            DriverMode.COMPLETED to if (english) "Complete delivery" else "إتمام التوصيل"
        ).forEach { (stage, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (snapshot.mode == stage) Icons.Rounded.CheckCircle else Icons.Rounded.Circle, null, tint = if (snapshot.mode == stage) Mint else Muted, modifier = Modifier.size(17.dp))
                Text(label, color = if (snapshot.mode == stage) Color.White else Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PlacesScreen(english: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (english) "RESTAURANT CLUSTERS" else "تجمعات المطاعم", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Rounded.Restaurant, null, tint = Mint, modifier = Modifier.size(28.dp))
                Text(if (english) "Restaurant discovery is being connected" else "يجري تجهيز اكتشاف المطاعم", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    if (english) "The ranking model separates public map signals from your observed order history. No live demand is fabricated. Nearby restaurant feeds and saved observations are not active yet."
                    else "نموذج الترتيب يفرّق بين إشارات الخرائط العامة وسجل الطلبات الذي ترصده بنفسك. لن نخترع طلباً أو ازدحاماً غير حقيقي. مصادر المطاعم القريبة وحفظ الملاحظات لم تُفعّل بعد.",
                    color = Muted, fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ProfileScreen(english: Boolean, onToggleLanguage: () -> Unit, trackingActive: Boolean, onToggleTracking: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (english) "PROFILE & SETTINGS" else "حسابي والإعدادات", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { onToggleLanguage() }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Language, null, tint = Mint)
                Spacer(Modifier.width(12.dp))
                Text(if (english) "Switch to Arabic" else "تغيير اللغة إلى الإنجليزية", color = Color.White, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { onToggleTracking() }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GpsFixed, null, tint = Mint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (english) "Background GPS session" else "جلسة تتبع GPS", color = Color.White)
                    Text(if (trackingActive) if (english) "Active" else "نشطة" else if (english) "Stopped" else "متوقفة", color = Muted, fontSize = 11.sp)
                }
                Icon(if (trackingActive) Icons.Rounded.ToggleOn else Icons.Rounded.ToggleOff, null, tint = if (trackingActive) Mint else Muted)
            }
        }
        Text("HAMED SMART DELIVERY • 0.4.0", color = Muted, fontSize = 10.sp)
    }
}

