package com.hamed.smartdelivery

import kotlin.math.roundToInt
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.Context
import android.content.ActivityNotFoundException
import android.net.Uri
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.graphics.Color as AndroidColor
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.os.Handler
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.os.Bundle
import android.view.WindowManager
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import com.hamed.smartdelivery.domain.GpsMode

private val Night = Color(0xFF07111D)
private val Panel = Color(0xFF101F2D)
private val Mint = Color(0xFF5EF0C2)
private val Cyan = Color(0xFF53C7F4)
private val Muted = Color(0xFF8EA5B8)

private fun clusterStableId(cluster: RestaurantCluster): String = cluster.clusterId?.takeIf { it.isNotBlank() }
    ?: "geo:" + String.format(Locale.US, "%.5f:%.5f", cluster.latitude, cluster.longitude)

private fun readPersistedCluster(prefs: android.content.SharedPreferences): RestaurantCluster? {
    val latitude = prefs.getString("selected_cluster_latitude", null)?.toDoubleOrNull() ?: return null
    val longitude = prefs.getString("selected_cluster_longitude", null)?.toDoubleOrNull() ?: return null
    return RestaurantCluster(
        latitude = latitude,
        longitude = longitude,
        restaurantCount = prefs.getInt("selected_cluster_count", 0),
        names = prefs.getString("selected_cluster_names", "").orEmpty().split("\u001f").filter { it.isNotBlank() },
        distanceMeters = prefs.getString("selected_cluster_distance", "0")?.toDoubleOrNull() ?: 0.0,
        searchRadiusMeters = prefs.getInt("selected_cluster_search_radius", 5000),
        category = prefs.getString("selected_cluster_category", "restaurant").orEmpty(),
        clusterId = prefs.getString("selected_cluster_id", null),
        district = prefs.getString("selected_cluster_district", null),
        clusterRadiusMeters = prefs.getString("selected_cluster_radius", "0")?.toDoubleOrNull() ?: 0.0
    )
}

private fun writePersistedCluster(prefs: android.content.SharedPreferences, cluster: RestaurantCluster?) {
    val editor = prefs.edit()
    if (cluster == null) {
        listOf(
            "selected_cluster_latitude", "selected_cluster_longitude", "selected_cluster_count",
            "selected_cluster_names", "selected_cluster_distance", "selected_cluster_search_radius",
            "selected_cluster_category", "selected_cluster_id", "selected_cluster_district", "selected_cluster_radius"
        ).forEach { editor.remove(it) }
    } else {
        editor.putString("selected_cluster_latitude", cluster.latitude.toString())
            .putString("selected_cluster_longitude", cluster.longitude.toString())
            .putInt("selected_cluster_count", cluster.restaurantCount)
            .putString("selected_cluster_names", cluster.names.joinToString("\u001f"))
            .putString("selected_cluster_distance", cluster.distanceMeters.toString())
            .putInt("selected_cluster_search_radius", cluster.searchRadiusMeters)
            .putString("selected_cluster_category", cluster.category)
            .putString("selected_cluster_id", cluster.clusterId)
            .putString("selected_cluster_district", cluster.district)
            .putString("selected_cluster_radius", cluster.clusterRadiusMeters.toString())
    }
    editor.apply()
}

private fun readPersistedExclusions(prefs: android.content.SharedPreferences): Map<String, Long> =
    prefs.getString("temporary_exclusions", "").orEmpty().split('|').mapNotNull { entry ->
        val separator = entry.lastIndexOf('=')
        if (separator <= 0) null else {
            val id = Uri.decode(entry.substring(0, separator))
            val until = entry.substring(separator + 1).toLongOrNull()
            if (id.isBlank() || until == null) null else id to until
        }
    }.toMap()

private fun alertRouteChanged(context: Context, soundEnabled: Boolean, vibrationEnabled: Boolean) {
    if (vibrationEnabled) runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 220, 100, 220), -1))
        }
    }
    if (soundEnabled) runCatching {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 850)
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 1000L)
    }
}

class MainActivity : ComponentActivity() {
    private var sharedDestination by mutableStateOf<DeliveryDestination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        sharedDestination = parseSharedDestination(intent)
        setContent { HamedApp(sharedDestination) }
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedDestination = parseSharedDestination(intent)
    }

    private fun parseSharedDestination(source: Intent?): DeliveryDestination? {
        val sharedText = source?.getStringExtra(Intent.EXTRA_TEXT) ?: source?.dataString
        return DestinationLinkParser.parse(sharedText)
    }
}

@Composable
fun HamedApp(initialDestination: DeliveryDestination? = null) {
    val context = LocalContext.current
    val workflowPrefs = remember { context.getSharedPreferences("workflow_state", Context.MODE_PRIVATE) }
    val restoredDriverMode = remember {
        runCatching { DriverMode.valueOf(workflowPrefs.getString("driver_mode", DriverMode.IDLE.name)!!) }
            .getOrDefault(DriverMode.IDLE)
    }
    var driverSnapshot by remember {
        mutableStateOf(DriverSnapshot(restoredDriverMode, workflowPrefs.getString("last_command_id", null)))
    }
    var trackingActive by remember { mutableStateOf(false) }
    var gpsMode by remember { mutableStateOf(GpsMode.STOPPED) }
    var perspective3D by remember { mutableStateOf(workflowPrefs.getBoolean("perspective_3d", true)) }
    var english by remember { mutableStateOf(workflowPrefs.getBoolean("english", false))}
    val uiStatePrefs = remember { context.getSharedPreferences("ui_state", Context.MODE_PRIVATE) }
    var selected by remember { mutableIntStateOf(uiStatePrefs.getInt("selected_tab", 0).coerceIn(0, 3)) }
    var mapExpanded by remember { mutableStateOf(false) }

    // Keep the last top-level page across process restarts without persisting live location data.
    LaunchedEffect(selected) {
        uiStatePrefs.edit().putInt("selected_tab", selected).apply()
    }
    var searchRequestId by remember { mutableIntStateOf(if (restoredDriverMode == DriverMode.SEARCHING) 1 else 0) }
    var manualSwitchRequestId by remember { mutableIntStateOf(0) }
    var selectedCluster by remember { mutableStateOf(readPersistedCluster(workflowPrefs)) }
    var excludedClusters by remember { mutableStateOf(readPersistedExclusions(workflowPrefs)) }
    val clusterPrefs = remember { context.getSharedPreferences("cluster_visit_history", Context.MODE_PRIVATE) }
    var visitedClusterIds by remember { mutableStateOf(clusterPrefs.getString("visited_ids", "").orEmpty().split('|').filter { it.isNotBlank() }) }
    var orderProducingClusterIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var clusterWaitStartedAt by remember {
        mutableStateOf(workflowPrefs.getLong("cluster_wait_started_at", -1L).takeIf { it > 0L })
    }
    var waitRemainingSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(driverSnapshot) {
        workflowPrefs.edit()
            .putString("driver_mode", driverSnapshot.mode.name)
            .putString("last_command_id", driverSnapshot.lastCommandId)
            .apply()
    }
    LaunchedEffect(selectedCluster) { writePersistedCluster(workflowPrefs, selectedCluster) }
    LaunchedEffect(excludedClusters) {
        val encoded = excludedClusters.entries.joinToString("|") { "${Uri.encode(it.key)}=${it.value}" }
        workflowPrefs.edit().putString("temporary_exclusions", encoded).apply()
    }
    LaunchedEffect(clusterWaitStartedAt) {
        if (clusterWaitStartedAt == null) workflowPrefs.edit().remove("cluster_wait_started_at").apply()
        else workflowPrefs.edit().putLong("cluster_wait_started_at", clusterWaitStartedAt!!).apply()
    }
    LaunchedEffect(perspective3D, english) {
        workflowPrefs.edit().putBoolean("perspective_3d", perspective3D).putBoolean("english", english).apply()
    }
    val routeAlertPrefs = remember { context.getSharedPreferences("route_alert_preferences", Context.MODE_PRIVATE) }
    var soundAlertsEnabled by remember { mutableStateOf(routeAlertPrefs.getBoolean("sound", true)) }
    var vibrationAlertsEnabled by remember { mutableStateOf(routeAlertPrefs.getBoolean("vibration", true)) }
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
            gpsMode = GpsMode.TRACKING
            if (pendingSearchAfterPermission) {
                searchRequestId += 1
                pendingSearchAfterPermission = false
            }
        }
    }
    // Safe build profile: location tracking and live navigation are disabled.
    val requestLocationPermissions: () -> Unit = { }
    LaunchedEffect(Unit) {
        val database = OrderClusterDatabase(context)
        orderProducingClusterIds = withContext(Dispatchers.IO) {
            try { database.orderProducingClusterIds() } finally { database.close() }
        }
    }
    LaunchedEffect(clusterWaitStartedAt, driverSnapshot.mode) {
        val startedAt = clusterWaitStartedAt
        if (startedAt == null || driverSnapshot.mode != DriverMode.SEARCHING) {
            waitRemainingSeconds = 0
        } else {
            while (driverSnapshot.mode == DriverMode.SEARCHING && clusterWaitStartedAt == startedAt) {
                waitRemainingSeconds = ((startedAt + 5 * 60 * 1000L - System.currentTimeMillis()) / 1000L)
                    .coerceAtLeast(0L).toInt()
                delay(1000L)
            }
        }
    }
    // Location permission is intentionally not requested in this safe build profile.
    val title = if (english) "Hamed Smart Delivery" else "حامد سمارت دليفري"
    val toggleTracking: () -> Unit = {
        if (!hasLocationPermission) {
            requestLocationPermissions()
        } else {
            if (gpsMode == GpsMode.STOPPED) {
                ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_START))
                gpsMode = GpsMode.TRACKING
                trackingActive = true
            } else {
                context.stopService(Intent(context, LocationTrackingService::class.java))
                gpsMode = GpsMode.STOPPED
                trackingActive = false
            }
        }
    }
    CompositionLocalProvider(LocalLayoutDirection provides if (english) androidx.compose.ui.unit.LayoutDirection.Ltr else androidx.compose.ui.unit.LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColorScheme(background = Night, surface = Panel, primary = Mint)) {
            Column(Modifier.fillMaxSize().background(Night).safeDrawingPadding()) {
                if (!mapExpanded) Header(title, english) { english = !english }
                if (selected == 0) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        SmartMap(hasLocationPermission, initialDestination, trackingActive, gpsMode, soundAlertsEnabled, vibrationAlertsEnabled, perspective3D, searchRequestId, manualSwitchRequestId, driverSnapshot.mode == DriverMode.SEARCHING, excludedClusters, visitedClusterIds, orderProducingClusterIds, onClusterSelected = { selectedCluster = it }, onClusterEntered = { id -> if (visitedClusterIds.lastOrNull() != id) { visitedClusterIds = visitedClusterIds + id; clusterPrefs.edit().putString("visited_ids", visitedClusterIds.joinToString("|")).apply() }; if (clusterWaitStartedAt == null) clusterWaitStartedAt = System.currentTimeMillis() }, onAutoExclude = { id, until -> excludedClusters = excludedClusters + (id to until); clusterWaitStartedAt = null; searchRequestId += 1 })
                        Column(Modifier.align(Alignment.TopStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            MiniMetric("GPS", if (trackingActive) if (english) "Tracking active" else "التتبع يعمل" else if (hasLocationPermission) if (english) "Ready" else "جاهز" else if (english) "Permission needed" else "بانتظار إذن الموقع", Icons.Rounded.GpsFixed)
                            MiniMetric(if (english) "Map" else "الخريطة", "MapLibre • OSM", Icons.Rounded.Map)
                        }
                        Column(Modifier.align(Alignment.TopEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            MapAction(Icons.Rounded.MyLocation, toggleTracking)
                            MapAction(Icons.Rounded.Layers) { perspective3D = !perspective3D }
                            MapAction(if (mapExpanded) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen) { mapExpanded = !mapExpanded }
                        }
                        Column(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalAlignment = Alignment.End) {
                            Surface(color = Panel.copy(alpha = .9f), shape = RoundedCornerShape(8.dp)) {
                                Text("© OpenStreetMap contributors", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                            }
                        }
                    }
                    if (!mapExpanded) Dashboard(driverSnapshot, english, waitRemainingSeconds, onManualSwitch = { selectedCluster?.let { cluster -> excludedClusters = excludedClusters + (clusterStableId(cluster) to (System.currentTimeMillis() + 10 * 60 * 1000L)); selectedCluster = null; clusterWaitStartedAt = null; manualSwitchRequestId += 1; searchRequestId += 1 } }, onResetExclusions = { excludedClusters = emptyMap(); if (driverSnapshot.mode == DriverMode.SEARCHING) searchRequestId += 1 }) { command ->
                        if (command == DriverCommand.StartSearch && !hasLocationPermission) {
                            pendingSearchAfterPermission = true
                            requestLocationPermissions()
                        } else if (command == DriverCommand.StartSearch && gpsMode == GpsMode.STOPPED) {
                            ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_START))
                            gpsMode = GpsMode.TRACKING
                            trackingActive = true
                        }
                        val transition = DriverReducer.reduce(driverSnapshot, command)
                        if (transition.accepted) {
                            when (command) {
                                DriverCommand.OrderReceived -> {
                                    clusterWaitStartedAt = null
                                    selectedCluster?.let { cluster ->
                                        runCatching { OrderClusterDatabase(context).recordOrder(cluster) }
                                        orderProducingClusterIds = orderProducingClusterIds + clusterStableId(cluster)
                                    }
                                }
                                DriverCommand.CompleteDelivery -> {
                                    clusterWaitStartedAt = null
                                    // A fresh request ID restarts discovery after the delivery pause.
                                    searchRequestId += 1
                                }
                                else -> Unit
                            }
                            driverSnapshot = transition.snapshot
                            if (command == DriverCommand.StartSearch && hasLocationPermission) searchRequestId += 1
                        }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(18.dp)) {
                        when (selected) {
                            1 -> OrdersScreen(driverSnapshot, english)
                            2 -> PlacesScreen(english)
                            else -> ProfileScreen(english, { english = !english }, trackingActive, toggleTracking, soundAlertsEnabled, vibrationAlertsEnabled, { enabled -> soundAlertsEnabled = enabled; routeAlertPrefs.edit().putBoolean("sound", enabled).apply() }, { enabled -> vibrationAlertsEnabled = enabled; routeAlertPrefs.edit().putBoolean("vibration", enabled).apply() })
                        }
                    }
                }
                if (!mapExpanded) NavigationBar(containerColor = Color(0xFF0B1723), contentColor = Mint) {
                    val labels = if (english) listOf("Home", "Orders", "Places", "Profile") else listOf("الرئيسية", "الطلبات", "التجمعات", "حسابي")
                    val icons = listOf(Icons.Rounded.Home, Icons.Rounded.ReceiptLong, Icons.Rounded.Restaurant, Icons.Rounded.Person)
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

private fun openGoogleMaps(context: Context, destination: DeliveryDestination) {
    val navigationUri = Uri.parse("google.navigation:q=${destination.latitude},${destination.longitude}&mode=d")
    val googleMapsIntent = Intent(Intent.ACTION_VIEW, navigationUri).setPackage("com.google.android.apps.maps")
    try {
        context.startActivity(googleMapsIntent)
    } catch (_: ActivityNotFoundException) {
        val webUri = Uri.parse(
            "https://www.google.com/maps/dir/?api=1&destination=${destination.latitude},${destination.longitude}&travelmode=driving"
        )
        context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
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
private fun SmartMap(hasLocationPermission: Boolean, destination: DeliveryDestination?, trackingActive: Boolean, gpsMode: GpsMode, soundAlertsEnabled: Boolean, vibrationAlertsEnabled: Boolean, perspective3D: Boolean, searchRequestId: Int, manualSwitchRequestId: Int, searchActive: Boolean, excludedClusters: Map<String, Long>, visitedClusterIds: List<String>, orderProducingClusterIds: Set<String>, onClusterSelected: (RestaurantCluster) -> Unit, onClusterEntered: (String) -> Unit, onAutoExclude: (String, Long) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var activeDestination by remember(destination) { mutableStateOf(destination) }
    var lastProcessedSearch by remember { mutableIntStateOf(0) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var userMarker by remember { mutableStateOf<Marker?>(null) }
    var displayedRoutePolyline by remember { mutableStateOf<org.maplibre.android.annotations.Polyline?>(null) }
    val navigationActive = false
    var currentBearing by remember { mutableFloatStateOf(0f) }
    var routeStatus by remember(activeDestination) { mutableStateOf(if (activeDestination == null) "" else "جارٍ انتظار موقع GPS لحساب المسار") }
    var nextTurnInstruction by remember(activeDestination) { mutableStateOf("") }
    var routePoints by remember(activeDestination) { mutableStateOf<List<LatLng>>(emptyList()) }
    var routeSteps by remember(activeDestination) { mutableStateOf<List<RouteStep>>(emptyList()) }
    var currentStepIndex by remember(activeDestination) { mutableIntStateOf(0) }
    var routeRequested by remember(activeDestination) { mutableStateOf(false) }
    var showMapProviderDialog by remember { mutableStateOf(false) }
    var mapProviderNotice by remember { mutableStateOf("") }
    var currentCluster by remember { mutableStateOf<RestaurantCluster?>(null) }
    var clusterEnteredAt by remember { mutableStateOf<Long?>(null) }
    var autoSwitchTriggered by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    val speech = remember(context) { TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS } }
    LaunchedEffect(ttsReady) {
        if (ttsReady) speech.language = Locale("ar", "SA")
    }
    DisposableEffect(speech) {
        onDispose { speech.stop(); speech.shutdown() }
    }
    LaunchedEffect(manualSwitchRequestId) {
        if (manualSwitchRequestId > 0) {
            currentCluster = null
            clusterEnteredAt = null
            autoSwitchTriggered = false
        }
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

    DisposableEffect(mapLibreMap, styleReady, hasLocationPermission, trackingActive, gpsMode, soundAlertsEnabled, vibrationAlertsEnabled, perspective3D, searchRequestId, manualSwitchRequestId, searchActive, excludedClusters, visitedClusterIds, orderProducingClusterIds) {
        val map = mapLibreMap
        if (map == null || !styleReady || !hasLocationPermission || gpsMode == GpsMode.STOPPED) {
            onDispose { }
        } else {
            val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            var firstFix = true
            var lastCameraLocation: Location? = null
            var lastRerouteAt = 0L
            var discoveryJob: Job? = null
            var routeJob: Job? = null
            var markerAnimationJob: Job? = null
            val restaurantMarkers = linkedMapOf<String, Marker>()
            val pharmacyMarkers = linkedMapOf<String, Marker>()
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val point = LatLng(location.latitude, location.longitude)
                    val clusterAtLocation = currentCluster
                    if (searchActive && clusterAtLocation != null && !autoSwitchTriggered) {
                        val clusterDistance = FloatArray(1)
                        Location.distanceBetween(
                            location.latitude, location.longitude,
                            clusterAtLocation.latitude, clusterAtLocation.longitude,
                            clusterDistance
                        )
                        val radius = clusterAtLocation.clusterRadiusMeters.takeIf { it > 0.0 } ?: 150.0
                        val now = System.currentTimeMillis()
                        if (clusterDistance[0].toDouble() <= radius) {
                            if (clusterEnteredAt == null) {
                                clusterEnteredAt = now
                                onClusterEntered(clusterStableId(clusterAtLocation))
                            }
                            val enteredAt = clusterEnteredAt
                            if (enteredAt != null && now - enteredAt >= 5 * 60 * 1000L) {
                                autoSwitchTriggered = true
                                onAutoExclude(clusterStableId(clusterAtLocation), now + 15 * 60 * 1000L)
                            }
                        }
                    }
                    if (location.hasBearing() && location.speed >= 1.2f) currentBearing = location.bearing
                    // The driver symbol is rendered as a single centered overlay (car in tracking,
                    // direction arrow in navigation), so the map never shows a pin and arrow together.
                    userMarker?.let { map.removeMarker(it); userMarker = null }
                    val previousCameraLocation = lastCameraLocation
                    val navigating = navigationActive && activeDestination != null
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
                    if (searchActive && searchRequestId > lastProcessedSearch) {
                        lastProcessedSearch = searchRequestId
                        val requestId = searchRequestId
                        routeStatus = "جارٍ البحث المتوازي عن تجمعات المطاعم"
                        routeJob?.cancel()
                        displayedRoutePolyline?.let { map.removePolyline(it) }
                        displayedRoutePolyline = null
                        restaurantMarkers.values.toList().forEach { map.removeMarker(it) }
                        restaurantMarkers.clear()
                        pharmacyMarkers.values.toList().forEach { map.removeMarker(it) }
                        pharmacyMarkers.clear()
                        routePoints = emptyList()
                        routeSteps = emptyList()
                        currentStepIndex = 0
                        nextTurnInstruction = ""
                        activeDestination = null
                        routeRequested = false
                        discoveryJob?.cancel()
                        discoveryJob = scope.launch {
                            var retry = 0
                            while (isActive && searchActive && requestId == searchRequestId) {
                                try {
                                    val clusters = RestaurantDiscoveryClient.findNearestClustersStreaming(
                                        context, location.latitude, location.longitude
                                    ) { updatedClusters ->
                                        if (!isActive || requestId != searchRequestId) return@findNearestClustersStreaming
                                        updatedClusters.take(8).forEach { cluster ->
                                            val markerKey = "${(cluster.latitude * 100000).roundToInt()}:${(cluster.longitude * 100000).roundToInt()}"
                                            val markerPosition = LatLng(cluster.latitude, cluster.longitude)
                                            val existingClusterMarker = restaurantMarkers[markerKey]
                                            if (existingClusterMarker == null) {
                                                restaurantMarkers[markerKey] = map.addMarker(
                                                    MarkerOptions().position(markerPosition).title("تجمع مطاعم • ${cluster.restaurantCount}")
                                                )
                                            } else {
                                                existingClusterMarker.position = markerPosition
                                                existingClusterMarker.title = "تجمع مطاعم • ${cluster.restaurantCount}"
                                            }
                                        }
                                        val now = System.currentTimeMillis()
                                        val best = updatedClusters.firstOrNull { cluster ->
                                            val id = clusterStableId(cluster)
                                            val excludedUntil = excludedClusters[id]
                                            val isExcluded = excludedUntil != null && excludedUntil > now
                                            val lastVisit = visitedClusterIds.indexOfLast { it == id }
                                            val otherVisits = if (lastVisit < 0) 4 else visitedClusterIds.drop(lastVisit + 1).distinct().count { it != id }
                                            val repeatAllowed = lastVisit < 0 || otherVisits >= 4 || id in orderProducingClusterIds
                                            !isExcluded && repeatAllowed
                                        } ?: run {
                                            routeStatus = "جميع التجمعات القريبة مستبعدة مؤقتاً أو لم يحن وقت تكرارها"
                                            return@findNearestClustersStreaming
                                        }
                                        if (currentCluster?.let { clusterStableId(it) } != clusterStableId(best)) {
                                            currentCluster = best
                                            clusterEnteredAt = null
                                            autoSwitchTriggered = false
                                        }
                                        onClusterSelected(best)
                                        val nextDestination = DeliveryDestination(best.latitude, best.longitude)
                                        val previousDestination = activeDestination
                                        val destinationChanged = if (previousDestination == null) true else {
                                            val delta = FloatArray(1)
                                            Location.distanceBetween(
                                                previousDestination.latitude, previousDestination.longitude,
                                                nextDestination.latitude, nextDestination.longitude, delta
                                            )
                                            delta[0] > 100f
                                        }
                                        if (destinationChanged) {
                                            activeDestination = nextDestination
                                            routeRequested = true
                                            routeJob?.cancel()
                                            routeStatus = "وصلت أول نتيجة صالحة • جارٍ رسم المسار"
                                            routeJob = scope.launch {
                                                val route = withContext(Dispatchers.IO) {
                                                    OsrmRouteClient.fetchRoute(
                                                        location.latitude, location.longitude,
                                                        nextDestination.latitude, nextDestination.longitude
                                                    )
                                                }
                                                if (route != null && route.points.size > 1 && requestId == searchRequestId) {
                                                    displayedRoutePolyline?.let { map.removePolyline(it) }
                                displayedRoutePolyline = null
                                                    displayedRoutePolyline = map.addPolyline(
                                                        PolylineOptions().addAll(route.points)
                                                            .color(AndroidColor.rgb(94, 240, 194)).width(6f)
                                                    )
                                                    routePoints = route.points
                                                    routeSteps = route.steps
                                                    currentStepIndex = 0
                                                    nextTurnInstruction = route.steps.firstOrNull()?.instruction ?: "واصل السير على المسار"
                                                    routeStatus = "تم رسم المسار • اضغط ابدأ الملاحة لبدء التوجيه"
                                                    routeStatus = "تم رسم المسار • ${(route.distanceMeters / 1000.0).toInt()} كم • ${(route.durationSeconds / 60.0).toInt()} د"
                                                } else if (requestId == searchRequestId) {
                                                    routeRequested = false
                                                    routeStatus = "تعذر حساب المسار حالياً • ستتم إعادة المحاولة"
                                                }
                                            }
                                        } else {
                                            routeStatus = "تم تحديث نتائج مصادر المطاعم • أفضل تجمع: ${best.restaurantCount} مطعم"
                                        }
                                    }
                                    val nearbyPharmacies = withContext(Dispatchers.IO) {
                                        RiyadhPlacesDatabase.nearestPharmacies(
                                            context, location.latitude, location.longitude, radiusMeters = 5000, limit = 30
                                        )
                                    }
                                    nearbyPharmacies.forEach { pharmacy ->
                                        val key = pharmacy.id
                                        val position = LatLng(pharmacy.latitude, pharmacy.longitude)
                                        val existingPharmacy = pharmacyMarkers[key]
                                        if (existingPharmacy == null) {
                                            pharmacyMarkers[key] = map.addMarker(
                                                MarkerOptions().position(position).title("صيدلية • ${pharmacy.name}")
                                            )
                                        } else {
                                            existingPharmacy.position = position
                                            existingPharmacy.title = "صيدلية • ${pharmacy.name}"
                                        }
                                    }
                                    if (clusters.isNotEmpty()) break
                                    routeStatus = "لم تصل نتائج بعد • مستمر في البحث"
                                } catch (error: Exception) {
                                    routeStatus = "تعذر مصدر مؤقتاً • مستمر في إعادة المحاولة"
                                }
                                retry += 1
                                delay((2000L * (1L shl retry.coerceAtMost(4))).coerceAtMost(30000L))
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
                                displayedRoutePolyline?.let { map.removePolyline(it) }
                                displayedRoutePolyline = null
                                displayedRoutePolyline = map.addPolyline(
                                    PolylineOptions().addAll(route.points)
                                        .color(AndroidColor.rgb(94, 240, 194))
                                        .width(6f)
                                )
                                routePoints = route.points
                                routeSteps = route.steps
                                currentStepIndex = 0
                                nextTurnInstruction = route.steps.firstOrNull()?.instruction ?: "واصل السير على المسار"
                                routeStatus = "تم رسم المسار • اضغط ابدأ الملاحة لبدء التوجيه"
                                routeStatus = "تم رسم المسار • " + (route.distanceMeters / 1000.0).toInt() + " كم • " + (route.durationSeconds / 60.0).toInt() + " د"
                            } else {
                                routeRequested = false
                                routeStatus = "تعذر حساب المسار حالياً • ستتم إعادة المحاولة"
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
                            if (navigationActive && ttsReady) speech.speak(nextTurnInstruction, TextToSpeech.QUEUE_FLUSH, null, "hamed-turn-$currentStepIndex")
                        }
                    }
                    val currentRoute = routePoints
                    if (navigationActive && currentRoute.size > 3) {
                        var nearestIndex = 0
                        var nearestOnRoute = Float.MAX_VALUE
                        currentRoute.forEachIndexed { index, routePoint ->
                            val result = FloatArray(1)
                            Location.distanceBetween(location.latitude, location.longitude, routePoint.latitude, routePoint.longitude, result)
                            if (result[0] < nearestOnRoute) {
                                nearestOnRoute = result[0]
                                nearestIndex = index
                            }
                        }
                        if (nearestIndex >= 3 && nearestOnRoute <= 70f && currentRoute.size - nearestIndex >= 2) {
                            val remaining = currentRoute.drop(nearestIndex)
                            routePoints = remaining
                            displayedRoutePolyline?.let { map.removePolyline(it) }
                            displayedRoutePolyline = map.addPolyline(
                                PolylineOptions().addAll(remaining)
                                    .color(AndroidColor.rgb(94, 240, 194)).width(6f)
                            )
                        }
                    }
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
                                    displayedRoutePolyline?.let { map.removePolyline(it) }
                                displayedRoutePolyline = null
                                    displayedRoutePolyline = map.addPolyline(
                                        PolylineOptions().addAll(updated.points)
                                            .color(AndroidColor.rgb(94, 240, 194))
                                            .width(6f)
                                    )
                                    routePoints = updated.points
                                    routeSteps = updated.steps
                                    currentStepIndex = 0
                                    nextTurnInstruction = updated.steps.firstOrNull()?.instruction ?: "واصل السير على المسار"
                                    alertRouteChanged(context, soundAlertsEnabled, vibrationAlertsEnabled)
                                    if (ttsReady && soundAlertsEnabled) speech.speak("تم تحديث المسار. $nextTurnInstruction", TextToSpeech.QUEUE_FLUSH, null, "hamed-reroute")
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
                discoveryJob?.cancel()
                routeJob?.cancel()
                markerAnimationJob?.cancel()
                restaurantMarkers.values.forEach { map.removeMarker(it) }
                restaurantMarkers.clear()
                pharmacyMarkers.values.forEach { map.removeMarker(it) }
                pharmacyMarkers.clear()
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
        if (gpsMode == GpsMode.TRACKING) {
            Surface(
                modifier = Modifier.align(Alignment.Center).size(42.dp),
                color = Panel.copy(alpha = .96f),
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Navigation,
                        null,
                        tint = Mint,
                        modifier = Modifier.size(26.dp).rotate(currentBearing)
                    )
                }
            }
        }
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
        if (activeDestination != null && routePoints.size > 1) {
            OutlinedButton(
                onClick = { showMapProviderDialog = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 54.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Rounded.Map, null)
                Spacer(Modifier.width(5.dp))
                Text("تبديل تطبيق الخريطة")
            }
        }
        if (showMapProviderDialog && activeDestination != null) {
            AlertDialog(
                onDismissRequest = { showMapProviderDialog = false },
                title = { Text("اختيار تطبيق الخريطة", color = Color.White) },
                text = { Text("اختر طريقة عرض هذا المسار. يمكنك إبقاء الخريطة المحلية أو فتح الوجهة في Google Maps.", color = Muted) },
                confirmButton = {
                    TextButton(onClick = {
                        showMapProviderDialog = false
                        openGoogleMaps(context, activeDestination!!)
                    }) { Text("فتح Google Maps", color = Mint) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showMapProviderDialog = false
                        mapProviderNotice = "الخريطة المحلية هي المحددة"
                    }) { Text("الخريطة المحلية", color = Color.White) }
                },
                containerColor = Panel,
                titleContentColor = Color.White
            )
        }
        if (mapProviderNotice.isNotBlank()) {
            Surface(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp), color = Panel.copy(alpha = .96f), shape = RoundedCornerShape(12.dp)) {
                Text(mapProviderNotice, color = Mint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        if (routeStatus.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
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
private fun Dashboard(snapshot: DriverSnapshot, english: Boolean, waitRemainingSeconds: Int, onManualSwitch: () -> Unit, onResetExclusions: () -> Unit, onCommand: (DriverCommand) -> Unit) {
    val mode = snapshot.mode
    val title = when (mode) {
        DriverMode.IDLE -> if (english) "Ready" else "جاهز للبحث"
        DriverMode.SEARCHING -> if (english) "Searching for orders" else "البحث عن الطلبات"
        DriverMode.ORDER_RECEIVED -> if (english) "Order received — delivery in progress" else "وصلني طلب من هذا التجمع — أكمل التوصيل"
        DriverMode.SAFE_MODE -> if (english) "Safe mode" else "وضع الأمان"
    }
    Column(Modifier.fillMaxWidth().background(Color(0xFF0B1723)).padding(horizontal = 14.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (english) "DRIVER CONTROL" else "مركز تحكم المندوب", color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp)
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Surface(color = if (mode == DriverMode.SEARCHING) Mint.copy(alpha = .13f) else Panel, shape = CircleShape) {
                Text(mode.name.replace('_', ' '), color = if (mode == DriverMode.SEARCHING) Mint else Muted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(targetState = mode, label = "driver-mode") { currentMode ->
            val command = when (currentMode) {
                DriverMode.IDLE -> DriverCommand.StartSearch
                DriverMode.SEARCHING -> DriverCommand.OrderReceived
                DriverMode.ORDER_RECEIVED -> DriverCommand.CompleteDelivery
                DriverMode.SAFE_MODE -> DriverCommand.ExitSafeMode
            }
            val label = when (currentMode) {
                DriverMode.IDLE -> if (english) "FIND ORDERS" else "البحث عن الطلبات"
                DriverMode.SEARCHING -> if (english) "I GOT AN ORDER FROM THIS CLUSTER" else "وصلني طلب من هذا التجمع"
                DriverMode.ORDER_RECEIVED -> if (english) "DELIVERY TASK COMPLETED" else "تم اكتمال المهمة بالطلب"
                DriverMode.SAFE_MODE -> if (english) "RESUME" else "استئناف العمل"
            }
            Button(onClick = { onCommand(command) }, modifier = Modifier.fillMaxWidth().height(49.dp), shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (currentMode == DriverMode.SEARCHING) Color(0xFFFFC66D) else Mint, contentColor = Night)) {
                Icon(when (currentMode) {
                    DriverMode.IDLE -> Icons.Rounded.Radar
                    DriverMode.SEARCHING -> Icons.Rounded.LocalShipping
                    DriverMode.ORDER_RECEIVED -> Icons.Rounded.CheckCircle
                    DriverMode.SAFE_MODE -> Icons.Rounded.PlayArrow
                }, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, fontWeight = FontWeight.Black, fontSize = 13.sp)
            }
        }
        if (mode == DriverMode.SEARCHING) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onManualSwitch, modifier = Modifier.weight(1f).height(37.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                    Text(if (english) "SWITCH CLUSTER" else "تبديل التجمع", fontSize = 10.sp)
                }
                OutlinedButton(onClick = onResetExclusions, modifier = Modifier.weight(1f).height(37.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Mint)) {
                    Text(if (english) "RESET EXCLUSIONS" else "إعادة تهيئة التجمعات", fontSize = 10.sp)
                }
            }
            if (waitRemainingSeconds > 0) {
                val timerText = String.format(Locale.US, "%02d:%02d", waitRemainingSeconds / 60, waitRemainingSeconds % 60)
                Text(if (english) "Cluster wait: $timerText" else "وقت انتظار التجمع: $timerText", color = Color(0xFFFFC66D), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(5.dp))
            Text(if (english) "The app keeps searching between suitable clusters automatically." else "يستمر التطبيق في البحث والتنقل بين التجمعات تلقائياً.", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun OrdersScreen(snapshot: DriverSnapshot, english: Boolean) {
    val current = when (snapshot.mode) {
        DriverMode.IDLE -> if (english) "No active delivery" else "لا يوجد طلب نشط"
        DriverMode.SEARCHING -> if (english) "Searching for orders" else "البحث عن الطلبات يعمل"
        DriverMode.ORDER_RECEIVED -> if (english) "Delivery in progress" else "تم تسجيل وصول الطلب — البحث متوقف حتى إكمال التوصيل"
        DriverMode.SAFE_MODE -> if (english) "Safe mode is active" else "وضع الأمان مفعّل"
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (english) "MY DELIVERY" else "طلباتي", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (english) "CURRENT DRIVER STATE" else "حالة المندوب الحالية", color = Muted, fontSize = 10.sp)
                Text(current, color = Mint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (english) "The order workflow uses the three agreed actions only." else "دورة الطلب تعتمد الأزرار الثلاثة المتفق عليها فقط، دون مراحل المطعم والعميل القديمة.", color = Muted, fontSize = 12.sp)
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
private fun ProfileScreen(english: Boolean, onToggleLanguage: () -> Unit, trackingActive: Boolean, onToggleTracking: () -> Unit, soundAlertsEnabled: Boolean, vibrationAlertsEnabled: Boolean, onSoundChange: (Boolean) -> Unit, onVibrationChange: (Boolean) -> Unit) {
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
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (english) "Route change sound" else "صوت تغيير المسار", color = Color.White)
                    Text(if (english) "Audible warning when route changes" else "تنبيه صوتي عند إعادة حساب المسار", color = Muted, fontSize = 11.sp)
                }
                Switch(checked = soundAlertsEnabled, onCheckedChange = onSoundChange)
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (english) "Route change vibration" else "اهتزاز تغيير المسار", color = Color.White)
                    Text(if (english) "Vibration warning when route changes" else "اهتزاز متزامن مع التنبيه الصوتي", color = Muted, fontSize = 11.sp)
                }
                Switch(checked = vibrationAlertsEnabled, onCheckedChange = onVibrationChange)
            }
        }
        Text("HAMED SMART DELIVERY • 0.4.3", color = Muted, fontSize = 10.sp)
    }
}

