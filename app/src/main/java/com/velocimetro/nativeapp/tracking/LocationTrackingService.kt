package com.velocimetro.nativeapp.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.velocimetro.nativeapp.AppContainer
import com.velocimetro.nativeapp.MainActivity
import com.velocimetro.nativeapp.R
import kotlin.math.roundToInt

class LocationTrackingService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var recorder: RouteRecorder
    private lateinit var locationThread: HandlerThread
    private lateinit var locationHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var trackingRequested = false
    @Volatile private var updatesRegistered = false
    private var lastNotificationAtElapsed = 0L

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        locationThread = HandlerThread("velo-location-recorder").apply { start() }
        locationHandler = Handler(locationThread.looper)
        AppContainer.from(applicationContext).let { container ->
            recorder = RouteRecorder(
                routeRepository = container.routeRepository(),
                trackingStateRepository = container.trackingStateRepository(),
            )
        }
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
        if (trackingRequested) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            stopSelf()
            return
        }
        trackingRequested = true
        startForeground(NOTIFICATION_ID, notification())
        locationHandler.post {
            try {
                recorder.start()
                // El callback y las escrituras SQLite se ejecutan en un hilo dedicado, nunca en Main.
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1_000L,
                    1f,
                    this,
                    locationThread.looper,
                )
                updatesRegistered = true
            } catch (_: SecurityException) {
                stopFromWorker()
            } catch (_: IllegalArgumentException) {
                stopFromWorker()
            }
        }
    }

    private fun stopTracking() {
        if (!trackingRequested) {
            finishService()
            return
        }
        trackingRequested = false
        locationHandler.post {
            if (updatesRegistered) locationManager.removeUpdates(this)
            updatesRegistered = false
            recorder.stop()
            mainHandler.post(::finishService)
        }
    }

    private fun stopFromWorker() {
        trackingRequested = false
        if (updatesRegistered) locationManager.removeUpdates(this)
        updatesRegistered = false
        recorder.stop()
        mainHandler.post(::finishService)
    }

    private fun finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onLocationChanged(location: Location) {
        recorder.record(location)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastNotificationAtElapsed >= NOTIFICATION_REFRESH_MILLIS) {
            lastNotificationAtElapsed = nowElapsed
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) mainHandler.post(::stopTracking)
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP_TRACKING,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(TrackingStore.snapshot.value.currentSpeedMps.formatSpeedForNotification())
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.tracking_notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.tracking_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        locationHandler.removeCallbacksAndMessages(null)
        locationThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.velocimetro.nativeapp.START_TRACKING"
        const val ACTION_STOP = "com.velocimetro.nativeapp.STOP_TRACKING"
        private const val CHANNEL_ID = "route_tracking"
        private const val NOTIFICATION_ID = 41
        private const val NOTIFICATION_REFRESH_MILLIS = 5_000L
        private const val REQUEST_OPEN_APP = 501
        private const val REQUEST_STOP_TRACKING = 502

        fun startIntent(context: Context): Intent = Intent(context, LocationTrackingService::class.java).setAction(ACTION_START)
        fun stopIntent(context: Context): Intent = Intent(context, LocationTrackingService::class.java).setAction(ACTION_STOP)
    }
}

private fun Float.formatSpeedForNotification(): String = "${(this * 3.6f).roundToInt()} km/h"
