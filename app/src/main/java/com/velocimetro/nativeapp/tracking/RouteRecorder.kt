package com.velocimetro.nativeapp.tracking

import android.location.Location
import android.os.SystemClock
import com.velocimetro.nativeapp.domain.model.GeoPoint
import com.velocimetro.nativeapp.domain.model.RoutePoint
import com.velocimetro.nativeapp.domain.model.TrackingSnapshot
import com.velocimetro.nativeapp.domain.repository.RouteRepository
import com.velocimetro.nativeapp.domain.repository.TrackingStateRepository
import kotlin.math.max

/**
 * Filtra saltos GPS y sólo escribe una lectura útil. No realiza trabajo de red.
 */
class RouteRecorder(
    private val routeRepository: RouteRepository,
    private val trackingStateRepository: TrackingStateRepository,
) {
    private var routeId: Long? = null
    private var startedAt: Long = 0L
    private var startedElapsedRealtimeNanos: Long = 0L
    private var lastLocation: Location? = null
    private var distanceMeters = 0.0
    private var maxSpeedMps = 0f

    fun start() {
        if (routeId != null) return
        startedAt = System.currentTimeMillis()
        startedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        routeId = routeRepository.startRoute(startedAt)
        publish(0f, null)
    }

    fun record(location: Location) {
        val activeRouteId = routeId ?: return
        if (location.hasAccuracy() && location.accuracy > MAX_ACCEPTED_ACCURACY_METERS) return

        val previous = lastLocation
        val elapsedMillis = previous?.let { elapsedBetween(it, location) } ?: 0L
        val segment = previous?.distanceTo(location)?.toDouble() ?: 0.0
        if (previous != null && !isPlausible(previous, location, segment, elapsedMillis)) return

        val estimatedSpeed = when {
            location.hasSpeed() && (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= MAX_SPEED_ACCURACY_MPS) -> location.speed.coerceAtLeast(0f)
            elapsedMillis > 0 -> (segment / (elapsedMillis / 1_000.0)).toFloat()
            else -> 0f
        }
        if (estimatedSpeed > MAX_REASONABLE_SPEED_MPS) return
        distanceMeters += segment
        maxSpeedMps = max(maxSpeedMps, estimatedSpeed)
        lastLocation = Location(location)
        routeRepository.appendPoint(
            activeRouteId,
            RoutePoint(
                recordedAt = location.time,
                latitude = location.latitude,
                longitude = location.longitude,
                speedMps = location.speed,
                accuracyMeters = location.accuracy,
            ),
        )
        publish(estimatedSpeed, location)
    }

    fun stop() {
        val activeRouteId = routeId ?: return
        val endedAt = System.currentTimeMillis()
        val average = averageSpeed()
        routeRepository.updateRoute(activeRouteId, distanceMeters, maxSpeedMps, average, endedAt)
        trackingStateRepository.publish(
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
        val activeRouteId = checkNotNull(routeId)
        val average = averageSpeed()
        routeRepository.updateRoute(activeRouteId, distanceMeters, maxSpeedMps, average)
        trackingStateRepository.publish(
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

    private fun averageSpeed(): Float {
        val durationSeconds = (SystemClock.elapsedRealtimeNanos() - startedElapsedRealtimeNanos) / 1_000_000_000.0
        return if (durationSeconds > 0) (distanceMeters / durationSeconds).toFloat() else 0f
    }

    private fun elapsedBetween(previous: Location, current: Location): Long {
        val elapsedNanos = current.elapsedRealtimeNanos - previous.elapsedRealtimeNanos
        return if (elapsedNanos > 0) elapsedNanos / 1_000_000 else current.time - previous.time
    }

    private fun isPlausible(
        previous: Location,
        current: Location,
        segmentMeters: Double,
        elapsedMillis: Long,
    ): Boolean {
        if (elapsedMillis <= 0) return false
        val accuracyMargin = (previous.accuracy + current.accuracy) / 2.0
        if (segmentMeters < max(MIN_SIGNIFICANT_SEGMENT_METERS, accuracyMargin)) return false
        // No se usa la velocidad reportada para validar el salto: un pico GPS no debe autorizarse a sí mismo.
        val maxPlausibleMeters = max(
            MAX_GPS_JUMP_TOLERANCE_METERS,
            MAX_REASONABLE_SPEED_MPS * (elapsedMillis / 1_000.0) + MAX_GPS_JUMP_TOLERANCE_METERS,
        )
        return segmentMeters <= maxPlausibleMeters
    }

    private companion object {
        const val MAX_ACCEPTED_ACCURACY_METERS = 30f
        const val MAX_SPEED_ACCURACY_MPS = 3.5f
        const val MAX_REASONABLE_SPEED_MPS = 70f
        const val MAX_GPS_JUMP_TOLERANCE_METERS = 25.0
        const val MIN_SIGNIFICANT_SEGMENT_METERS = 2.5
    }
}
