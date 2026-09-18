package com.velocimetro.nativeapp.core

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

data class TrackingSnapshot(
    val isTracking: Boolean = false,
    val routeId: Long? = null,
    val currentSpeedMps: Float = 0f,
    val maxSpeedMps: Float = 0f,
    val averageSpeedMps: Float = 0f,
    val distanceMeters: Double = 0.0,
    val startedAt: Long? = null,
    val lastPoint: GeoPoint? = null,
)

data class RouteSummary(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val distanceMeters: Double,
    val maxSpeedMps: Float,
    val averageSpeedMps: Float,
)

data class DashboardStats(
    val historicalAverageSpeedMps: Float = 0f,
    val weeklyAverageMeters: Double = 0.0,
)

enum class DashboardWidget(val label: String) {
    DISTANCE("Distancia"),
    MAX_SPEED("Máxima"),
    AVERAGE_SPEED("Promedio"),
    DURATION("Tiempo"),
}

enum class ThemePreference { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val widgets: Set<DashboardWidget> = DashboardWidget.entries.toSet(),
)
