package com.hamed.smartdelivery

import kotlin.math.roundToInt
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.ActivityNotFoundException
import android.net.Uri
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import org.maplibre.android.annotations.IconFactory
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
import com.hamed.smartdelivery.domain.ClusterPolicy

private var workSessionInitialized = false

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

private fun playStatusTone(context: Context, volume: Float, kind: String) {
    runCatching {
        val (toneType, durationMs) = when (kind) {
            "welcome" -> ToneGenerator.TONE_PROP_BEEP to 240
            "success" -> ToneGenerator.TONE_PROP_ACK to 420
            else -> ToneGenerator.TONE_PROP_NACK to 520
        }
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, (volume * 100f).roundToInt().coerceIn(0, 100))
        tone.startTone(toneType, durationMs)
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, durationMs + 150L)
    }
}

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
    val gpsPrefs = remember { context.getSharedPreferences(LocationTrackingService.PREFERENCES_NAME, Context.MODE_PRIVATE) }
    var announcementsEnabled by remember { mutableStateOf(workflowPrefs.getBoolean("announcements_enabled", true)) }
    var announcementVolume by remember { mutableFloatStateOf(workflowPrefs.getFloat("announcement_volume", 0.85f).coerceIn(0f, 1f)) }
    var taskReminderIntervalMinutes by remember { mutableIntStateOf(workflowPrefs.getInt("task_reminder_interval_minutes", 3).coerceIn(1, 10)) }
    var english by remember { mutableStateOf(workflowPrefs.getBoolean("english", false)) }
    var speechReady by remember { mutableStateOf(false) }
    val announcementsSpeech = remember(context) { TextToSpeech(context) { status -> speechReady = status == TextToSpeech.SUCCESS } }
    LaunchedEffect(speechReady, english) {
        if (speechReady) announcementsSpeech.language = if (english) Locale.US else Locale("ar", "SA")
    }
    DisposableEffect(announcementsSpeech) { onDispose { announcementsSpeech.stop(); announcementsSpeech.shutdown() } }
    fun announceArabic(message: String, englishOverride: String? = null) {
        val spoken = if (!english) message else englishOverride ?: when (message) {
            "مبروك إتمام المهمة بنجاح. حان الوقت للبحث عن الطلبات، جاري البحث" -> "Delivery completed successfully. Searching for new orders."
            "حامد سمارت دليفري يتمنى لكم يوماً مليئاً بالأرباح والطلبات. حان الوقت للبحث عن الطلبات، جاري البحث" -> "Hamed Smart Delivery wishes you a profitable day. Searching for orders."
            "تم استلام الطلب. توقف البحث مؤقتاً" -> "Order received. Order search is paused."
            "اكتملت المهمة. يمكنك بدء البحث عن طلب جديد" -> "The delivery is complete. You can start searching for another order."
            "تم تفعيل وضع الأمان" -> "Safety mode is enabled."
            "بدأ مؤقت انتظار التجمع لمدة خمس دقائق" -> "The five-minute cluster waiting timer has started."
            "هل انتهيت من توصيل طلبك؟ إذا انتهيت، يرجى الضغط على زر انتهاء المهمة" -> "Have you completed the delivery? If so, tap Complete Delivery."
            "مرحباً بك في حامد سمارت دليفري" -> "Welcome to Hamed Smart Delivery."
            "تم تغيير المسار. يرجى التوجه إلى التجمع الجديد عبر المسار الجديد" -> "The route has changed. Follow the updated route to the new cluster."
            "انتهت خمس دقائق ولم يصل طلب من هذا التجمع. تم استبعاده لمدة خمس عشرة دقيقة ويجري البحث عن تجمع آخر" -> "Five minutes passed without an order. This cluster is excluded for fifteen minutes while another is selected."
            "تم استبعاد التجمع السابق لمدة عشر دقائق. يجري البحث عن التجمع التالي" -> "The previous cluster is excluded for ten minutes. Searching for the next cluster."
            "هذا اختبار للتعليمات الصوتية في حامد سمارت دليفري" -> "This is a test of Hamed Smart Delivery voice guidance."
            "انتهى وقت العمل. يمكنك بدء البحث عن الطلبات من جديد." -> "The work session has ended. You can start searching for orders again."
            else -> message
        }
        if (announcementsEnabled && speechReady && spoken.isNotBlank()) {
            announcementsSpeech.language = if (english) Locale.US else Locale("ar", "SA")
            announcementsSpeech.setSpeechRate(1.0f)
            announcementsSpeech.speak(spoken, TextToSpeech.QUEUE_FLUSH,
                android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, announcementVolume) },
                "hamed-event")
        }
    }
    val restoredDriverMode = remember {
        if (!workSessionInitialized) {
            workflowPrefs.edit().remove("driver_mode").remove("last_command_id")
                .remove("cluster_wait_started_at").remove("temporary_exclusions").apply()
            writePersistedCluster(workflowPrefs, null)
            workSessionInitialized = true
        }
        DriverMode.IDLE
    }
    var driverSnapshot by remember {
        mutableStateOf(DriverSnapshot(restoredDriverMode, workflowPrefs.getString("last_command_id", null)))
    }
    var trackingActive by remember { mutableStateOf(gpsPrefs.getBoolean(LocationTrackingService.KEY_ACTIVE, false)) }
    var gpsMode by remember { mutableStateOf(if (trackingActive) GpsMode.TRACKING else GpsMode.STOPPED) }
    var pendingSearchAfterPermission by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == LocationTrackingService.ACTION_TRACKING_STATE) {
                    val active = intent.getBooleanExtra(LocationTrackingService.EXTRA_TRACKING_ACTIVE, false)
                    val failure = intent.getStringExtra(LocationTrackingService.EXTRA_TRACKING_ERROR)
                    trackingActive = active
                    gpsMode = if (active) GpsMode.TRACKING else GpsMode.STOPPED
                    if (!active && !failure.isNullOrBlank()) {
                        pendingSearchAfterPermission = false
                        if (announcementsEnabled) playStatusTone(context, announcementVolume, "error")
                        android.widget.Toast.makeText(context, failure, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(LocationTrackingService.ACTION_TRACKING_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        // The service may have started before this receiver was registered (cold launch).
        // Reconcile UI state with the service's persisted state immediately after registration.
        val activeNow = context.getSharedPreferences(LocationTrackingService.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(LocationTrackingService.KEY_ACTIVE, false)
        trackingActive = activeNow
        gpsMode = if (activeNow) GpsMode.TRACKING else GpsMode.STOPPED
        onDispose { context.unregisterReceiver(receiver) }
    }
    var perspective3D by remember { mutableStateOf(workflowPrefs.getBoolean("perspective_3d", true)) }
    val uiStatePrefs = remember { context.getSharedPreferences("ui_state", Context.MODE_PRIVATE) }
    var selected by remember { mutableIntStateOf(0) }
    var mapExpanded by remember { mutableStateOf(false) }

    // Keep the last top-level page across process restarts without persisting live location data.
    LaunchedEffect(selected) {
        uiStatePrefs.edit().putInt("selected_tab", selected).apply()
    }
    var searchRequestId by remember { mutableIntStateOf(0) }
    var manualSwitchRequestId by remember { mutableIntStateOf(0) }
    var recenterRequestId by remember { mutableIntStateOf(0) }
    var selectedCluster by remember { mutableStateOf<RestaurantCluster?>(null) }
    var excludedClusters by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    val clusterPrefs = remember { context.getSharedPreferences("cluster_visit_history", Context.MODE_PRIVATE) }
    var visitedClusterIds by remember { mutableStateOf(clusterPrefs.getString("visited_ids", "").orEmpty().split('|').filter { it.isNotBlank() }) }
    var orderProducingClusterIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var clusterWaitStartedAt by remember {
        mutableStateOf(
            if (restoredDriverMode == DriverMode.SEARCHING)
                workflowPrefs.getLong("cluster_wait_started_at", -1L).takeIf { it > 0L }
            else null
        )
    }
    var waitRemainingSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(driverSnapshot) {
        workflowPrefs.edit().putString("driver_mode", driverSnapshot.mode.name)
            .putString("last_command_id", driverSnapshot.lastCommandId).apply()
    }
    var previousDriverMode by remember { mutableStateOf(driverSnapshot.mode) }
    LaunchedEffect(driverSnapshot.mode) {
        if (driverSnapshot.mode != previousDriverMode) {
            when (driverSnapshot.mode) {
                DriverMode.SEARCHING -> if (previousDriverMode == DriverMode.ORDER_RECEIVED) {
                    if (announcementsEnabled) playStatusTone(context, announcementVolume, "success")
                    announceArabic("مبروك إتمام المهمة بنجاح. حان الوقت للبحث عن الطلبات، جاري البحث")
                } else announceArabic("حامد سمارت دليفري يتمنى لكم يوماً مليئاً بالأرباح والطلبات. حان الوقت للبحث عن الطلبات، جاري البحث")
                DriverMode.ORDER_RECEIVED -> announceArabic("تم استلام الطلب. توقف البحث مؤقتاً")
                DriverMode.IDLE -> if (previousDriverMode == DriverMode.ORDER_RECEIVED) announceArabic("اكتملت المهمة. يمكنك بدء البحث عن طلب جديد")
                DriverMode.SAFE_MODE -> announceArabic("تم تفعيل وضع الأمان")
            }
            previousDriverMode = driverSnapshot.mode
        }
    }
    LaunchedEffect(clusterWaitStartedAt) {
        if (clusterWaitStartedAt != null && driverSnapshot.mode == DriverMode.SEARCHING)
            announceArabic("بدأ مؤقت انتظار التجمع لمدة خمس دقائق")
    }
    LaunchedEffect(driverSnapshot.mode, announcementsEnabled, taskReminderIntervalMinutes) {
        if (driverSnapshot.mode == DriverMode.ORDER_RECEIVED && announcementsEnabled) {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                delay(taskReminderIntervalMinutes * 60_000L)
                if (driverSnapshot.mode == DriverMode.ORDER_RECEIVED && announcementsEnabled) {
                    announceArabic("هل انتهيت من توصيل طلبك؟ إذا انتهيت، يرجى الضغط على زر انتهاء المهمة")
                }
            }
        }
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
    var welcomeAnnounced by remember { mutableStateOf(false) }
    LaunchedEffect(speechReady, announcementsEnabled) {
        if (speechReady && announcementsEnabled && !welcomeAnnounced) {
            playStatusTone(context, announcementVolume, "welcome")
            announceArabic("مرحباً بك في حامد سمارت دليفري")
            welcomeAnnounced = true
        }
    }
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

        } else {
            pendingSearchAfterPermission = false
        }
    }
    val requestLocationPermissions: () -> Unit = {
        locationPermissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        ))
    }
    // Do not mark the driver as searching until the foreground GPS service confirms it is active.
    LaunchedEffect(pendingSearchAfterPermission, gpsMode, hasLocationPermission) {
        if (pendingSearchAfterPermission && hasLocationPermission) {
            if (gpsMode == GpsMode.TRACKING) {
                val transition = DriverReducer.reduce(driverSnapshot, DriverCommand.StartSearch)
                if (transition.accepted) {
                    driverSnapshot = transition.snapshot
                    searchRequestId += 1
                }
                pendingSearchAfterPermission = false
            } else {
                delay(15_000L)
                if (pendingSearchAfterPermission && gpsMode == GpsMode.STOPPED) {
                    pendingSearchAfterPermission = false
                    android.widget.Toast.makeText(
                        context,
                        "لم تبدأ خدمة GPS. تحقق من تفعيل الموقع ومنح صلاحياته ثم حاول مجدداً.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            requestLocationPermissions()
        } else {
            ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_START))
        }
    }
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
    // GPS permission is requested at launch; the foreground location service owns the live session.
    val title = if (english) "Hamed Smart Delivery" else "حامد سمارت دليفري"
    val toggleTracking: () -> Unit = {
        if (!hasLocationPermission) {
            requestLocationPermissions()
        } else {
            // Read the service's persisted truth at click time instead of relying on a possibly
            // one-frame-old Compose state while the foreground-service broadcast is in flight.
            val activeNow = context.getSharedPreferences(
                LocationTrackingService.PREFERENCES_NAME, Context.MODE_PRIVATE
            ).getBoolean(LocationTrackingService.KEY_ACTIVE, trackingActive)
            try {
                if (activeNow) {
                    context.startService(
                        Intent(context, LocationTrackingService::class.java)
                            .setAction(LocationTrackingService.ACTION_STOP)
                    )
                } else {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, LocationTrackingService::class.java)
                            .setAction(LocationTrackingService.ACTION_START)
                    )
                }
            } catch (error: Exception) {
                android.widget.Toast.makeText(
                    context,
                    "تعذر تغيير حالة التتبع: ${error.localizedMessage ?: error.javaClass.simpleName}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    CompositionLocalProvider(LocalLayoutDirection provides if (english) androidx.compose.ui.unit.LayoutDirection.Ltr else androidx.compose.ui.unit.LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColorScheme(background = Night, surface = Panel, primary = Mint)) {
            Column(Modifier.fillMaxSize().background(Night).safeDrawingPadding()) {
                if (!mapExpanded) Header(title, english) { english = !english }
                if (selected == 0) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        SmartMap(hasLocationPermission, initialDestination, trackingActive, gpsMode, soundAlertsEnabled, vibrationAlertsEnabled, perspective3D, searchRequestId, manualSwitchRequestId, recenterRequestId, driverSnapshot.mode == DriverMode.SEARCHING, clusterWaitStartedAt, selectedCluster?.let { clusterStableId(it) }, excludedClusters, visitedClusterIds, orderProducingClusterIds, onVoiceInstruction = { arabic, englishText -> announceArabic(arabic, englishText) }, onRouteChanged = { announceArabic("تم تغيير المسار. يرجى التوجه إلى التجمع الجديد عبر المسار الجديد") }, onClusterSelected = { selectedCluster = it }, onClusterEntered = { id -> if (visitedClusterIds.lastOrNull() != id) { visitedClusterIds = visitedClusterIds + id; clusterPrefs.edit().putString("visited_ids", visitedClusterIds.joinToString("|")).apply() }; if (clusterWaitStartedAt == null) clusterWaitStartedAt = System.currentTimeMillis() }, onAutoExclude = { id, until -> excludedClusters = excludedClusters + (id to until); selectedCluster = null; clusterWaitStartedAt = null; searchRequestId += 1; announceArabic("انتهت خمس دقائق ولم يصل طلب من هذا التجمع. تم استبعاده لمدة خمس عشرة دقيقة ويجري البحث عن تجمع آخر") })
                        Column(Modifier.align(Alignment.TopStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            MiniMetric("GPS", if (trackingActive) if (english) "Tracking active" else "التتبع يعمل" else if (hasLocationPermission) if (english) "Ready" else "جاهز" else if (english) "Permission needed" else "بانتظار إذن الموقع", Icons.Rounded.GpsFixed)
                            MiniMetric(if (english) "Map" else "الخريطة", "MapLibre • OSM", Icons.Rounded.Map)
                        }
                        Column(Modifier.align(Alignment.TopEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            // Keep GPS start/stop available directly on the map as well as in Profile.
                            MapAction(if (trackingActive) Icons.Rounded.ToggleOn else Icons.Rounded.ToggleOff) { toggleTracking() }
                            MapAction(Icons.Rounded.MyLocation) { recenterRequestId += 1 }
                            MapAction(Icons.Rounded.Layers) { perspective3D = !perspective3D }
                            MapAction(if (mapExpanded) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen) { mapExpanded = !mapExpanded }
                        }
                        Column(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalAlignment = Alignment.End) {
                            Surface(color = Panel.copy(alpha = .9f), shape = RoundedCornerShape(8.dp)) {
                                Text("© OpenStreetMap contributors", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                            }
                        }
                    }
                    if (!mapExpanded) Dashboard(driverSnapshot, english, waitRemainingSeconds, onEndWork = {
                        val transition = DriverReducer.reduce(driverSnapshot, DriverCommand.EndWork)
                        if (transition.accepted) {
                            driverSnapshot = transition.snapshot
                            selectedCluster = null
                            clusterWaitStartedAt = null
                            excludedClusters = emptyMap()
                            searchRequestId += 1
                            announceArabic("انتهى وقت العمل. يمكنك بدء البحث عن الطلبات من جديد.")
                        }
                    }, onManualSwitch = { selectedCluster?.let { cluster ->
                        val now = System.currentTimeMillis()
                        val exclusion = com.hamed.smartdelivery.domain.ClusterPolicy.manualSwitch(clusterStableId(cluster), now)
                        excludedClusters = excludedClusters + (exclusion.clusterId to exclusion.untilEpochMillis)
                        selectedCluster = null
                        clusterWaitStartedAt = null
                        manualSwitchRequestId += 1
                        searchRequestId += 1
                        announceArabic("تم استبعاد التجمع السابق لمدة عشر دقائق. يجري البحث عن التجمع التالي")
                    } }, onResetExclusions = { excludedClusters = emptyMap(); if (driverSnapshot.mode == DriverMode.SEARCHING) searchRequestId += 1 }) { command ->
                        val waitingForGps = command == DriverCommand.StartSearch &&
                            (!hasLocationPermission || gpsMode == GpsMode.STOPPED)
                        if (command == DriverCommand.StartSearch && !hasLocationPermission) {
                            pendingSearchAfterPermission = true
                            requestLocationPermissions()
                        } else if (command == DriverCommand.StartSearch && gpsMode == GpsMode.STOPPED) {
                            pendingSearchAfterPermission = true
                            try {
                                ContextCompat.startForegroundService(
                                    context,
                                    Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_START)
                                )
                            } catch (error: Exception) {
                                pendingSearchAfterPermission = false
                                android.widget.Toast.makeText(
                                    context,
                                    "تعذر تشغيل خدمة GPS: ${error.localizedMessage ?: error.javaClass.simpleName}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        if (!waitingForGps) {
                            val transition = DriverReducer.reduce(driverSnapshot, command)
                            if (transition.accepted) {
                            when (command) {
                                DriverCommand.OrderReceived -> {
                                    clusterWaitStartedAt = null
                                    selectedCluster?.let { cluster ->
                                        runCatching {
                                            val orderDatabase = OrderClusterDatabase(context)
                                            try { orderDatabase.recordOrder(cluster) } finally { orderDatabase.close() }
                                        }
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
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(18.dp)) {
                        when (selected) {
                            1 -> OrdersScreen(driverSnapshot, english)
                            2 -> PlacesScreen(english)
                            else -> ProfileScreen(english, { english = !english }, soundAlertsEnabled, vibrationAlertsEnabled, { enabled -> soundAlertsEnabled = enabled; routeAlertPrefs.edit().putBoolean("sound", enabled).apply() }, { enabled -> vibrationAlertsEnabled = enabled; routeAlertPrefs.edit().putBoolean("vibration", enabled).apply() }, announcementsEnabled, { enabled -> announcementsEnabled = enabled; workflowPrefs.edit().putBoolean("announcements_enabled", enabled).apply() }, announcementVolume, { value -> announcementVolume = value.coerceIn(0f, 1f); workflowPrefs.edit().putFloat("announcement_volume", announcementVolume).apply() }, taskReminderIntervalMinutes, { value -> taskReminderIntervalMinutes = value.coerceIn(1, 10); workflowPrefs.edit().putInt("task_reminder_interval_minutes", taskReminderIntervalMinutes).apply() }, { announceArabic("هذا اختبار للتعليمات الصوتية في حامد سمارت دليفري") })
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
private fun SmartMap(hasLocationPermission: Boolean, destination: DeliveryDestination?, trackingActive: Boolean, gpsMode: GpsMode, soundAlertsEnabled: Boolean, vibrationAlertsEnabled: Boolean, perspective3D: Boolean, searchRequestId: Int, manualSwitchRequestId: Int, recenterRequestId: Int, searchActive: Boolean, clusterWaitStartedAt: Long?, selectedClusterId: String?, excludedClusters: Map<String, Long>, visitedClusterIds: List<String>, orderProducingClusterIds: Set<String>, onVoiceInstruction: (String, String) -> Unit, onRouteChanged: () -> Unit, onClusterSelected: (RestaurantCluster) -> Unit, onClusterEntered: (String) -> Unit, onAutoExclude: (String, Long) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var activeDestination by remember(destination) { mutableStateOf(destination) }
    var lastProcessedSearch by remember { mutableIntStateOf(0) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var userMarker by remember { mutableStateOf<Marker?>(null) }
    var followUserCamera by remember { mutableStateOf(true) }
    var latestUserLocation by remember { mutableStateOf<Location?>(null) }
    var lastArrowBearing by remember { mutableFloatStateOf(Float.NaN) }
    var displayedRoutePolyline by remember { mutableStateOf<org.maplibre.android.annotations.Polyline?>(null) }
    var completedRoutePolyline by remember { mutableStateOf<org.maplibre.android.annotations.Polyline?>(null) }
    var currentBearing by remember { mutableFloatStateOf(0f) }
    var routeStatus by remember(activeDestination) { mutableStateOf(if (activeDestination == null) "" else "جارٍ انتظار موقع GPS لحساب المسار") }
    var routePoints by remember(activeDestination) { mutableStateOf<List<LatLng>>(emptyList()) }
    var routeSteps by remember(activeDestination) { mutableStateOf<List<RouteStep>>(emptyList()) }
    var spokenRouteStepIndex by remember(activeDestination) { mutableIntStateOf(0) }
    var routeRequested by remember(activeDestination) { mutableStateOf(false) }
    var currentCluster by remember { mutableStateOf<RestaurantCluster?>(null) }
    var clusterEnteredAt by remember(clusterWaitStartedAt, selectedClusterId) { mutableStateOf(clusterWaitStartedAt) }
    var autoSwitchTriggered by remember { mutableStateOf(false) }
    LaunchedEffect(searchRequestId, manualSwitchRequestId) {
        if (searchRequestId > 0 || manualSwitchRequestId > 0) {
            currentCluster = null
            clusterEnteredAt = null
            autoSwitchTriggered = false
            routeSteps = emptyList()
            spokenRouteStepIndex = 0
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
                // Offset the camera's usable center upward so the driver arrow sits lower on screen.
                map.setPadding(0, (mapView.height * 0.38f).roundToInt().coerceAtLeast(0), 0, 0)
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

    DisposableEffect(mapLibreMap, styleReady) {
        val map = mapLibreMap
        if (map == null || !styleReady) onDispose { } else {
            val cameraListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) followUserCamera = false
            }
            map.addOnCameraMoveStartedListener(cameraListener)
            onDispose { map.removeOnCameraMoveStartedListener(cameraListener) }
        }
    }

    LaunchedEffect(recenterRequestId, mapLibreMap, styleReady) {
        val map = mapLibreMap
        val location = latestUserLocation
        if (recenterRequestId > 0 && map != null && styleReady && location != null) {
            followUserCamera = true
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(location.latitude, location.longitude))
                        .zoom(16.5).tilt(if (perspective3D) 58.0 else 0.0).build()
                ), 650
            )
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

    LaunchedEffect(searchActive, currentCluster, clusterEnteredAt) {
        val cluster = currentCluster
        val enteredAt = clusterEnteredAt
        if (!searchActive || cluster == null || enteredAt == null) return@LaunchedEffect
        val id = clusterStableId(cluster)
        val remaining = (ClusterPolicy.WAIT_MILLIS - (System.currentTimeMillis() - enteredAt)).coerceAtLeast(0L)
        if (remaining > 0L) delay(remaining)
        if (searchActive && currentCluster?.let { clusterStableId(it) } == id &&
            clusterEnteredAt == enteredAt && !autoSwitchTriggered) {
            autoSwitchTriggered = true
            val now = System.currentTimeMillis()
            onAutoExclude(id, ClusterPolicy.automaticTimeout(id, now).untilEpochMillis)
        }
    }

    val latestExcludedClusters by rememberUpdatedState(excludedClusters)
    val latestVisitedClusterIds by rememberUpdatedState(visitedClusterIds)
    val latestOrderProducingClusterIds by rememberUpdatedState(orderProducingClusterIds)
    val latestClusterWaitStartedAt by rememberUpdatedState(clusterWaitStartedAt)
    val latestSelectedClusterId by rememberUpdatedState(selectedClusterId)
    val latestTrackingActive by rememberUpdatedState(trackingActive)
    val latestSoundAlertsEnabled by rememberUpdatedState(soundAlertsEnabled)
    val latestVibrationAlertsEnabled by rememberUpdatedState(vibrationAlertsEnabled)
    val latestPerspective3D by rememberUpdatedState(perspective3D)
    val latestRoutePoints by rememberUpdatedState(routePoints)

    DisposableEffect(mapLibreMap, styleReady, hasLocationPermission, gpsMode, searchRequestId, manualSwitchRequestId, searchActive) {
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
            var positionAnimationJob: Job? = null
            var pulseAnimationJob: Job? = null
            var displayedMarkerLocation: LatLng? = null
            var displayedArrowBearing = 0f
            val gpsMotionFilter = GpsMotionFilter()
            var monotonicRouteProgress = 0.0
            var lastProgressDrawn = -2.0
            var offRouteFixCount = 0
            fun drawRouteProgress(points: List<LatLng>, progressMeters: Double) {
                val split = RouteGeometryMatcher.splitAtProgress(points, progressMeters)
                displayedRoutePolyline?.let { map.removePolyline(it) }
                completedRoutePolyline?.let { map.removePolyline(it) }
                displayedRoutePolyline = if (split.remaining.size > 1) map.addPolyline(
                    PolylineOptions().addAll(split.remaining).color(AndroidColor.rgb(13, 71, 161)).width(7f)
                ) else null
                completedRoutePolyline = if (split.completed.size > 1) map.addPolyline(
                    PolylineOptions().addAll(split.completed).color(AndroidColor.rgb(135, 145, 155)).width(7f)
                ) else null
            }
            val restaurantMarkers = linkedMapOf<String, Marker>()
            val pharmacyMarkers = linkedMapOf<String, Marker>()
            val listener = object : LocationListener {
                override fun onLocationChanged(rawLocation: Location) {
                    val location = gpsMotionFilter.filter(rawLocation) ?: return
                    val rawPoint = LatLng(location.latitude, location.longitude)
                    val routeMatch = RouteGeometryMatcher.match(rawPoint, latestRoutePoints, monotonicRouteProgress)
                    val confidentlyMatched = routeMatch?.takeIf { it.crossTrackMeters <= 45.0 }
                    if (confidentlyMatched != null) {
                        monotonicRouteProgress = maxOf(monotonicRouteProgress, confidentlyMatched.progressMeters)
                        if (monotonicRouteProgress - lastProgressDrawn >= 1.5) {
                            drawRouteProgress(latestRoutePoints, monotonicRouteProgress)
                            lastProgressDrawn = monotonicRouteProgress
                        }
                    }
                    val point = confidentlyMatched?.takeIf { it.crossTrackMeters <= 35.0 }?.snappedPoint ?: rawPoint
                    latestUserLocation = Location(location)
                    // A manual pan pauses follow only temporarily; resume it automatically on movement.
                    val distanceSinceCameraFix = lastCameraLocation?.distanceTo(location) ?: 0f
                    if (location.speed >= 1.2f || distanceSinceCameraFix >= 4f) {
                        followUserCamera = true
                    }
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
                            // Expiry is driven by a dedicated timer, not the next GPS callback.
                        }
                    }
                    if (location.hasBearing() && location.speed >= 1.2f) currentBearing = location.bearing
                    // Marker rotation is relative to the map camera, so the arrow points
                    // toward the top of the screen when the camera follows the driver's heading.
                    val hasReliableBearing = location.hasBearing() && location.speed >= 1.2f
                    val targetArrowBearing = if (hasReliableBearing) {
                        ((location.bearing - map.cameraPosition.bearing.toFloat()) % 360f + 360f) % 360f
                    } else displayedArrowBearing
                    if (userMarker == null) {
                        displayedMarkerLocation = point
                        displayedArrowBearing = targetArrowBearing
                        lastArrowBearing = targetArrowBearing
                        userMarker = map.addMarker(
                            MarkerOptions().position(point)
                                .icon(createLocationArrowIcon(context, targetArrowBearing))
                                .title("موقعك الحالي")
                        )
                    } else {
                        val from = displayedMarkerLocation ?: userMarker!!.position
                        val fromBearing = displayedArrowBearing
                        var bearingDelta = ((targetArrowBearing - fromBearing + 540f) % 360f) - 180f
                        positionAnimationJob?.cancel()
                        positionAnimationJob = scope.launch {
                            val started = SystemClock.elapsedRealtime()
                            val duration = 650L
                            var lastIconUpdate = 0L
                            while (isActive) {
                                val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtMost(duration)
                                val linear = elapsed.toFloat() / duration.toFloat()
                                val eased = linear * linear * (3f - 2f * linear)
                                val lat = from.latitude + (point.latitude - from.latitude) * eased
                                val lon = from.longitude + (point.longitude - from.longitude) * eased
                                userMarker?.position = LatLng(lat, lon)
                                displayedMarkerLocation = LatLng(lat, lon)
                                val smoothBearing = (fromBearing + bearingDelta * eased + 360f) % 360f
                                displayedArrowBearing = smoothBearing
                                if (elapsed - lastIconUpdate >= 55L || elapsed >= duration) {
                                    userMarker?.icon = createLocationArrowIcon(context, smoothBearing)
                                    lastIconUpdate = elapsed
                                }
                                if (elapsed >= duration) break
                                delay(16L)
                            }
                            displayedMarkerLocation = point
                            displayedArrowBearing = targetArrowBearing
                            lastArrowBearing = targetArrowBearing
                        }
                    }
                    lastArrowBearing = targetArrowBearing
                    if (pulseAnimationJob == null) {
                        pulseAnimationJob = scope.launch {
                            while (isActive) {
                                if (positionAnimationJob?.isActive != true && userMarker != null) {
                                    val pulse = ((kotlin.math.sin(SystemClock.elapsedRealtime() / 650.0).toFloat() + 1f) / 2f)
                                    userMarker?.icon = createLocationArrowIcon(context, displayedArrowBearing, pulse)
                                }
                                delay(120L)
                            }
                        }
                    }
                    val previousCameraLocation = lastCameraLocation
                    val shouldFollow = followUserCamera && (firstFix || (latestTrackingActive && (previousCameraLocation == null || previousCameraLocation.distanceTo(location) >= 1.5f)))
                    if (shouldFollow) {
                        firstFix = false
                        val bearing = if (location.hasBearing() && location.speed >= 1.2f) location.bearing.toDouble() else map.cameraPosition.bearing
                        map.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder().target(point)
                                    .zoom(16.5)
                                    .tilt(if (latestPerspective3D) 58.0 else 0.0).bearing(bearing).build()
                            ),
                            850
                        )
                        lastCameraLocation = Location(location)
                    }
                    if (searchActive && (searchRequestId > lastProcessedSearch || currentCluster == null)) {
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
                        monotonicRouteProgress = 0.0
                        lastProgressDrawn = -2.0
                        completedRoutePolyline?.let { map.removePolyline(it) }
                        completedRoutePolyline = null
                        routeSteps = emptyList()
                        spokenRouteStepIndex = 0
                        activeDestination = null
                        routeRequested = false
                        discoveryJob?.cancel()
                        discoveryJob = scope.launch {
                            var retry = 0
                            while (isActive && searchActive && requestId == searchRequestId) {
                                var foundEligibleCluster = false
                                // Re-read the newest fix on every retry; never keep querying from a stale cached coordinate.
                                val searchLocation = latestUserLocation?.let { Location(it) } ?: Location(location)
                                try {
                                    val clusters = RestaurantDiscoveryClient.findNearestClustersStreaming(
                                        context, searchLocation.latitude, searchLocation.longitude
                                    ) { updatedClusters ->
                                        if (!isActive || requestId != searchRequestId) return@findNearestClustersStreaming false
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
                                        val availableClusters = updatedClusters.filter { cluster ->
                                            val excludedUntil = latestExcludedClusters[clusterStableId(cluster)]
                                            excludedUntil == null || excludedUntil <= now
                                        }
                                        // Prefer a fresh/known productive cluster, but never stop discovery
                                        // merely because the repeat-visit preference rejects every candidate.
                                        val best = availableClusters.firstOrNull { cluster ->
                                            val id = clusterStableId(cluster)
                                            val lastVisit = latestVisitedClusterIds.indexOfLast { it == id }
                                            val otherVisits = if (lastVisit < 0) 4 else latestVisitedClusterIds.drop(lastVisit + 1).distinct().count { it != id }
                                            lastVisit < 0 || otherVisits >= 4 || id in latestOrderProducingClusterIds
                                        } ?: run {
                                            routeStatus = if (availableClusters.isEmpty())
                                                "التجمعات القريبة مستبعدة مؤقتاً • يجري توسيع البحث"
                                            else
                                                "تمت زيارة التجمعات القريبة • يجري البحث عن تجمع جديد وفق قاعدة عدم التكرار"
                                            return@findNearestClustersStreaming false
                                        }
                                        foundEligibleCluster = true
                                        if (currentCluster?.let { clusterStableId(it) } != clusterStableId(best)) {
                                            currentCluster = best
                                            clusterEnteredAt = if (clusterStableId(best) == latestSelectedClusterId) latestClusterWaitStartedAt else null
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
                                                        searchLocation.latitude, searchLocation.longitude,
                                                        nextDestination.latitude, nextDestination.longitude
                                                    )
                                                }
                                                if (route != null && route.points.size > 1 && requestId == searchRequestId) {
                                                    monotonicRouteProgress = 0.0
                                                    lastProgressDrawn = -2.0
                                                    routePoints = route.points
                                                    drawRouteProgress(route.points, 0.0)
                                                    routeSteps = route.steps
                                                    spokenRouteStepIndex = 0
                                                    routeStatus = "مسار سيارة متوازن • ${(route.distanceMeters / 1000.0).toInt()} كم • ${(route.durationSeconds / 60.0).toInt()} د • وقود تقديري ${String.format(Locale.US, "%.1f", route.estimatedFuelLiters)} ل • ${route.alternativeCount} بدائل • لا تتوفر بيانات ازدحام/إغلاق حية"
                                                } else if (requestId == searchRequestId) {
                                                    routeRequested = false
                                                    routeStatus = "تعذر حساب المسار حالياً • ستتم إعادة المحاولة"
                                                }
                                            }
                                        } else {
                                            routeStatus = "تم تحديث نتائج مصادر المطاعم • أفضل تجمع: ${best.restaurantCount} مطعم"
                                        }
                                        true
                                    }
                                    val nearbyPharmacies = withContext(Dispatchers.IO) {
                                        RiyadhPlacesDatabase.nearestPharmacies(
                                            context, searchLocation.latitude, searchLocation.longitude, radiusMeters = 5000, limit = 30
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
                                    if (foundEligibleCluster) break
                                    routeStatus = if (clusters.isNotEmpty())
                                        "لم يتوفر تجمع صالح بعد • مستمر في البحث عن تجمع آخر"
                                    else "لا توجد تجمعات قريبة في قاعدة الرياض حتى الآن • يجري توسيع نطاق البحث"
                                } catch (error: Exception) {
                                    android.util.Log.e("HamedSmartDelivery", "Riyadh cluster discovery failed at ${searchLocation.latitude},${searchLocation.longitude}", error)
                                    routeStatus = "تعذر قراءة قاعدة الرياض أو البحث: ${error.localizedMessage ?: error.javaClass.simpleName} • إعادة المحاولة"
                                }
                                retry += 1
                                delay((2000L * (1L shl retry.coerceAtMost(4))).coerceAtMost(30000L))
                            }
                        }
                    }
                    val pendingDestination = activeDestination
                    if (pendingDestination != null && !routeRequested) {
                        routeRequested = true
                        routeStatus = "جارٍ حساب المسار"
                        val routeRequestId = searchRequestId
                        routeJob = scope.launch {
                            val route = withContext(Dispatchers.IO) {
                                OsrmRouteClient.fetchRoute(
                                    location.latitude, location.longitude,
                                    pendingDestination.latitude, pendingDestination.longitude
                                )
                            }
                            if (isActive && routeRequestId == searchRequestId && activeDestination == pendingDestination &&
                                route != null && route.points.size > 1) {
                                displayedRoutePolyline?.let { map.removePolyline(it) }
                                displayedRoutePolyline = map.addPolyline(
                                    PolylineOptions().addAll(route.points)
                                        .color(AndroidColor.rgb(13, 71, 161))
                                        .width(7f)
                                )
                                routePoints = route.points
                                routeSteps = route.steps
                                spokenRouteStepIndex = 0
                                routeStatus = "تم رسم المسار • " + (route.distanceMeters / 1000.0).toInt() + " كم • " + (route.durationSeconds / 60.0).toInt() + " د"
                            } else if (isActive && routeRequestId == searchRequestId && activeDestination == pendingDestination) {
                                routeRequested = false
                                routeStatus = "تعذر حساب المسار حالياً • ستتم إعادة المحاولة"
                            }
                        }
                    }
                    val currentRoute = routePoints
                    if (searchActive && spokenRouteStepIndex < routeSteps.size) {
                        val nextStep = routeSteps[spokenRouteStepIndex]
                        val maneuverPoint = nextStep.location
                        val maneuverDistance = maneuverPoint?.let {
                            val distance = FloatArray(1)
                            Location.distanceBetween(location.latitude, location.longitude, it.latitude, it.longitude, distance)
                            distance[0]
                        } ?: 0f
                        if (maneuverDistance <= 250f) {
                            onVoiceInstruction(nextStep.instruction, nextStep.instructionEnglish)
                            spokenRouteStepIndex += 1
                        }
                    }

                    if (searchActive && activeDestination != null && currentRoute.size > 1) {
                        val now = System.currentTimeMillis()
                        val offRoute = routeMatch != null && routeMatch.crossTrackMeters > 55.0 && location.speed >= 2.0f
                        offRouteFixCount = if (offRoute) offRouteFixCount + 1 else 0
                        if (offRouteFixCount >= 2 && now - lastRerouteAt > 5000L) {
                            val rerouteDestination = activeDestination
                            if (rerouteDestination != null) {
                                lastRerouteAt = now
                                offRouteFixCount = 0
                                routeStatus = "تم تأكيد الخروج عن الطريق • جارٍ اختيار أفضل مسار سيارة بديل"
                                val rerouteRequestId = searchRequestId
                                routeJob = scope.launch {
                                    val updated = withContext(Dispatchers.IO) {
                                        OsrmRouteClient.fetchRoute(
                                            location.latitude, location.longitude,
                                            rerouteDestination.latitude, rerouteDestination.longitude
                                        )
                                    }
                                    if (isActive && rerouteRequestId == searchRequestId &&
                                        activeDestination == rerouteDestination && updated != null && updated.points.size > 1) {
                                        monotonicRouteProgress = 0.0
                                        lastProgressDrawn = -2.0
                                        routePoints = updated.points
                                        drawRouteProgress(updated.points, 0.0)
                                        routeSteps = updated.steps
                                        spokenRouteStepIndex = 0
                                        alertRouteChanged(context, latestSoundAlertsEnabled, latestVibrationAlertsEnabled)
                                        onRouteChanged()
                                        routeStatus = "تم تحديث مسار السيارة • ${(updated.distanceMeters / 1000.0).toInt()} كم • وقود تقديري ${String.format(Locale.US, "%.1f", updated.estimatedFuelLiters)} ل • لا تتوفر بيانات ازدحام/إغلاق حية"
                                    } else if (isActive && rerouteRequestId == searchRequestId &&
                                        activeDestination == rerouteDestination) {
                                        routeStatus = "تعذر تحديث المسار حالياً"
                                    }
                                }
                            }
                        }
                    }
                }
            }
            try {
                var requestedProvider = false
                for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    if (locationManager.allProviders.contains(provider) && locationManager.isProviderEnabled(provider)) {
                        try {
                            locationManager.requestLocationUpdates(provider, 1000L, 1f, listener, Looper.getMainLooper())
                            requestedProvider = true
                        } catch (_: SecurityException) {
                            // Continue to NETWORK_PROVIDER if GPS_PROVIDER is not permitted.
                        }
                    }
                }
                // Use the newest cached fix immediately, then keep receiving live GPS/network fixes.
                val cachedFix = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
                    .maxByOrNull { it.time }
                cachedFix?.takeIf {
                    it.time > 0L && System.currentTimeMillis() >= it.time &&
                        System.currentTimeMillis() - it.time <= 120_000L
                }?.let { listener.onLocationChanged(it) }
                if (!requestedProvider) routeStatus = "يرجى تفعيل خدمة الموقع GPS من إعدادات الهاتف"
            } catch (_: SecurityException) {
                // Permission may be revoked while the map is open.
            } catch (_: IllegalArgumentException) {
                // No location provider is available on this device.
            }
            onDispose {
                if (routeJob?.isActive == true) routeRequested = false
                discoveryJob?.cancel()
                routeJob?.cancel()
                markerAnimationJob?.cancel()
                positionAnimationJob?.cancel()
                pulseAnimationJob?.cancel()
                restaurantMarkers.values.forEach { map.removeMarker(it) }
                restaurantMarkers.clear()
                pharmacyMarkers.values.forEach { map.removeMarker(it) }
                pharmacyMarkers.clear()
                try { locationManager.removeUpdates(listener) } catch (_: Exception) { }
                displayedRoutePolyline?.let { map.removePolyline(it); displayedRoutePolyline = null }
                completedRoutePolyline?.let { map.removePolyline(it); completedRoutePolyline = null }
                userMarker?.let { map.removeMarker(it); userMarker = null }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { }
        )





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

private fun createLocationArrowIcon(context: Context, bearing: Float, pulse: Float = 0f): org.maplibre.android.annotations.Icon {
    val drawable = ContextCompat.getDrawable(context, R.drawable.ic_location_arrow)
        ?: error("Location arrow drawable is missing")
    val bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, 72, 72)
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb((110 * (1f - pulse)).toInt().coerceIn(0, 110), 94, 240, 194)
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    canvas.drawCircle(36f, 36f, 20f + pulse * 8f, glowPaint)
    canvas.save()
    canvas.rotate(bearing, 36f, 36f)
    drawable.draw(canvas)
    canvas.restore()
    return IconFactory.getInstance(context).fromBitmap(bitmap)
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
private fun Dashboard(snapshot: DriverSnapshot, english: Boolean, waitRemainingSeconds: Int, onEndWork: () -> Unit, onManualSwitch: () -> Unit, onResetExclusions: () -> Unit, onCommand: (DriverCommand) -> Unit) {
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
        if (mode == DriverMode.SEARCHING || mode == DriverMode.ORDER_RECEIVED) {
            OutlinedButton(
                onClick = onEndWork,
                modifier = Modifier.fillMaxWidth().height(37.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A80))
            ) {
                Text(if (english) "END WORK" else "انتهاء العمل", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
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
                Text(if (english) "Riyadh restaurant clusters are available" else "اكتشاف تجمعات المطاعم من قاعدة الرياض", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    if (english) "Restaurant and cafe clusters are read from the bundled Riyadh database and ranked by distance, density and manually confirmed order history. No live HungerStation order feed is connected, and no demand is fabricated."
                    else "يتم جلب تجمعات المطاعم والمقاهي من قاعدة الرياض المحلية وترتيبها حسب المسافة والكثافة وسجل الطلبات الذي تؤكده يدوياً. لا توجد تغذية طلبات مباشرة من هنقرستيشن حالياً، ولا يتم اختراع طلب أو ازدحام.",
                    color = Muted, fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ProfileScreen(english: Boolean, onToggleLanguage: () -> Unit, soundAlertsEnabled: Boolean, vibrationAlertsEnabled: Boolean, onSoundChange: (Boolean) -> Unit, onVibrationChange: (Boolean) -> Unit, announcementsEnabled: Boolean, onAnnouncementsChange: (Boolean) -> Unit, announcementVolume: Float, onAnnouncementVolumeChange: (Float) -> Unit, taskReminderIntervalMinutes: Int, onTaskReminderIntervalChange: (Int) -> Unit, onTestAnnouncement: () -> Unit) {
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
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (english) "Arabic voice announcements" else "التعليمات الصوتية العربية", color = Color.White)
                        Text(if (english) "Search, order and task events" else "إعلانات بدء البحث والطلب واكتمال المهمة وتغيير التجمع", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = announcementsEnabled, onCheckedChange = onAnnouncementsChange)
                }
                Text(if (english) "Announcement volume" else "مستوى صوت التعليمات", color = Color.White, fontSize = 12.sp)
                Slider(value = announcementVolume, onValueChange = onAnnouncementVolumeChange, enabled = announcementsEnabled)
                Text(if (english) "Task reminder interval: $taskReminderIntervalMinutes min" else "تذكير إكمال المهمة كل $taskReminderIntervalMinutes دقائق", color = Color.White, fontSize = 12.sp)
                Slider(value = taskReminderIntervalMinutes.toFloat(), onValueChange = { onTaskReminderIntervalChange(it.roundToInt()) }, valueRange = 1f..10f, steps = 8, enabled = announcementsEnabled)
                OutlinedButton(onClick = onTestAnnouncement, enabled = announcementsEnabled, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.VolumeUp, null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (english) "Test voice" else "اختبار الصوت")
                }
            }
        }
        Text("HAMED SMART DELIVERY • 0.4.3", color = Muted, fontSize = 10.sp)
    }
}

