package com.hamed.smartdelivery

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/** User-started foreground GPS session. Coordinates are not persisted by this service. */
class LocationTrackingService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setTrackingState(false)
            stopSelf()
            return START_NOT_STICKY
        }
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            setTrackingState(false)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for location"))
        try {
            var requested = false
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (provider in providers) {
                if (locationManager.allProviders.contains(provider) && locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 2500L, 3f, this, Looper.getMainLooper())
                    requested = true
                }
            }
            if (!requested) {
                setTrackingState(false)
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification("Location is off. Enable GPS to start tracking."))
                stopSelf()
                return START_NOT_STICKY
            }
            setTrackingState(true)
        } catch (_: SecurityException) {
            setTrackingState(false)
            stopSelf()
            return START_NOT_STICKY
        } catch (_: IllegalArgumentException) {
            setTrackingState(false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        val accuracy = if (location.hasAccuracy()) String.format(Locale.US, "%.0f m", location.accuracy) else "—"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification("Location active • ±$accuracy"))
        sendBroadcast(
            Intent(ACTION_LOCATION_UPDATE).setPackage(packageName)
                .putExtra(EXTRA_LATITUDE, location.latitude)
                .putExtra(EXTRA_LONGITUDE, location.longitude)
                .putExtra(EXTRA_ACCURACY, location.accuracy)
                .putExtra(EXTRA_HAS_ACCURACY, location.hasAccuracy())
                .putExtra(EXTRA_BEARING, location.bearing)
                .putExtra(EXTRA_HAS_BEARING, location.hasBearing())
                .putExtra(EXTRA_TIME, location.time)
                .putExtra(EXTRA_PROVIDER, location.provider ?: "unknown")
        )
    }

    override fun onDestroy() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) { }
        setTrackingState(false)
        super.onDestroy()
    }

    private fun setTrackingState(active: Boolean) {
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, active).apply()
        sendBroadcast(
            Intent(ACTION_TRACKING_STATE).setPackage(packageName)
                .putExtra(EXTRA_TRACKING_ACTIVE, active)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Driver location", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shows when Hamed Smart Delivery is actively tracking location"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Hamed Smart Delivery")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        const val ACTION_START = "com.hamed.smartdelivery.action.START_LOCATION"
        const val ACTION_STOP = "com.hamed.smartdelivery.action.STOP_LOCATION"
        const val ACTION_LOCATION_UPDATE = "com.hamed.smartdelivery.action.LOCATION_UPDATE"
        const val ACTION_TRACKING_STATE = "com.hamed.smartdelivery.action.TRACKING_STATE"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_ACCURACY = "accuracy"
        const val EXTRA_HAS_ACCURACY = "has_accuracy"
        const val EXTRA_BEARING = "bearing"
        const val EXTRA_HAS_BEARING = "has_bearing"
        const val EXTRA_TIME = "time"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_TRACKING_ACTIVE = "tracking_active"
        const val PREFERENCES_NAME = "gps_tracking"
        const val KEY_ACTIVE = "active"
        private const val CHANNEL_ID = "driver_location"
        private const val NOTIFICATION_ID = 4102
    }
}
