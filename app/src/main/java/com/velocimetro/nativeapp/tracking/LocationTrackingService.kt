package com.velocimetro.nativeapp.tracking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.velocimetro.nativeapp.R
import com.velocimetro.nativeapp.core.formatKmh
import com.velocimetro.nativeapp.data.RouteDatabase

class LocationTrackingService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var recorder: RouteRecorder
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        recorder = RouteRecorder(RouteDatabase(applicationContext))
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
        }
        return START_NOT_STICKY
    }

    private fun startTracking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID, notification())
        recorder.start()
        // API 30: esta sobrecarga evita una dependencia de APIs más nuevas para un intervalo de 1 s / 1 m.
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 1f, this)
    }

    private fun stopTracking() {
        locationManager.removeUpdates(this)
        recorder.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onLocationChanged(location: Location) {
        recorder.record(location)
        if (location.time - lastNotificationAt >= NOTIFICATION_REFRESH_MILLIS) {
            lastNotificationAt = location.time
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle(getString(R.string.tracking_notification_title))
        .setContentText(TrackingStore.snapshot.value.currentSpeedMps.formatKmh())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.tracking_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.velocimetro.nativeapp.START_TRACKING"
        const val ACTION_STOP = "com.velocimetro.nativeapp.STOP_TRACKING"
        private const val CHANNEL_ID = "route_tracking"
        private const val NOTIFICATION_ID = 41
        private const val NOTIFICATION_REFRESH_MILLIS = 5_000L

        fun startIntent(context: Context): Intent = Intent(context, LocationTrackingService::class.java).setAction(ACTION_START)
        fun stopIntent(context: Context): Intent = Intent(context, LocationTrackingService::class.java).setAction(ACTION_STOP)
    }
}
