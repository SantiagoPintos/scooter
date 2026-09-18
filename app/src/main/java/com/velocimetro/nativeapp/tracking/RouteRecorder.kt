package com.velocimetro.nativeapp.tracking

import android.location.Location
import com.velocimetro.nativeapp.core.GeoPoint
import com.velocimetro.nativeapp.core.TrackingSnapshot
import com.velocimetro.nativeapp.data.RouteDatabase
import kotlin.math.max

/**
 * Filtra saltos GPS y sólo escribe una lectura útil. No realiza trabajo de red.
 */
class RouteRecorder(private val database: RouteDatabase) {
    private var routeId: Long? = null
    private var startedAt: Long = 0L
    private var lastLocation: Location? = null
    private var distanceMeters = 0.0
    private var maxSpeedMps = 0f

    fun start() {
        if (routeId != null) return
        startedAt = System.currentTimeMillis()
        routeId = database.startRoute(startedAt)
        publish(0f, null)
    }

    fun record(location: Location) {
        val activeRouteId = routeId ?: return
        if (location.hasAccuracy() && location.accuracy > MAX_ACCEPTED_ACCURACY_METERS) return

        val previous = lastLocation
        val elapsedMillis = previous?.let { location.time - it.time } ?: 0L
        val segment = previous?.distanceTo(location)?.toDouble() ?: 0.0
        if (previous != null && !isPlausible(segment, elapsedMillis, location.speed)) return

        val estimatedSpeed = when {
            location.hasSpeed() -> location.speed.coerceAtLeast(0f)
            elapsedMillis > 0 -> (segment / (elapsedMillis / 1_000.0)).toFloat()
            else -> 0f
        }
        distanceMeters += segment
        maxSpeedMps = max(maxSpeedMps, estimatedSpeed)
        lastLocation = Location(location)
        database.appendPoint(activeRouteId, location)
        publish(estimatedSpeed, location)
    }

    fun stop() {
        val activeRouteId = routeId ?: return
        val now = System.currentTimeMillis()
        val average = averageSpeed(now)
        database.updateRoute(activeRouteId, distanceMeters, maxSpeedMps, average, now)
        TrackingStore.publish(
            TrackingSnapshot(
                isTracking = false,
                routeId = activeRouteId,
                currentSpeedMps = 0f,
                maxSpeedMps = maxSpeedMps,
                averageSpeedMps = average,
                distanceMeters = distanceMeters,
                startedAt = startedAt,
                lastPoint = lastLocation?.let { GeoPoint(it.latitude, it.longitude) },
            ),
        )
        routeId = null
        lastLocation = null
        distanceMeters = 0.0
        maxSpeedMps = 0f
    }

    private fun publish(speedMps: Float, location: Location?) {
        val now = System.currentTimeMillis()
        val activeRouteId = checkNotNull(routeId)
        val average = averageSpeed(now)
        database.updateRoute(activeRouteId, distanceMeters, maxSpeedMps, average)
        TrackingStore.publish(
            TrackingSnapshot(
                isTracking = true,
                routeId = activeRouteId,
                currentSpeedMps = speedMps,
                maxSpeedMps = maxSpeedMps,
                averageSpeedMps = average,
                distanceMeters = distanceMeters,
                startedAt = startedAt,
                lastPoint = location?.let { GeoPoint(it.latitude, it.longitude) },
            ),
        )
    }

    private fun averageSpeed(now: Long): Float {
        val durationSeconds = (now - startedAt) / 1_000.0
        return if (durationSeconds > 0) (distanceMeters / durationSeconds).toFloat() else 0f
    }

    private fun isPlausible(segmentMeters: Double, elapsedMillis: Long, reportedSpeed: Float): Boolean {
        if (elapsedMillis <= 0 || segmentMeters < MIN_SEGMENT_METERS) return true
        // El margen absorbe una aceleración real breve, pero elimina teletransportes del GPS.
        val maxPlausibleMeters = max(80.0, reportedSpeed * (elapsedMillis / 1_000.0) * 3 + 30)
        return segmentMeters <= maxPlausibleMeters
    }

    private companion object {
        const val MAX_ACCEPTED_ACCURACY_METERS = 40f
        const val MIN_SEGMENT_METERS = 0.3
    }
}
