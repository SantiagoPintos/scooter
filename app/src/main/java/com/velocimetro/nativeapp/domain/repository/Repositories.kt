package com.velocimetro.nativeapp.domain.repository

import com.velocimetro.nativeapp.domain.model.AppSettings
import com.velocimetro.nativeapp.domain.model.DashboardStats
import com.velocimetro.nativeapp.domain.model.DashboardWidget
import com.velocimetro.nativeapp.domain.model.RoutePoint
import com.velocimetro.nativeapp.domain.model.RouteSummary
import com.velocimetro.nativeapp.domain.model.ThemePreference
import com.velocimetro.nativeapp.domain.model.TrackingSnapshot
import kotlinx.coroutines.flow.StateFlow

/** Durable route storage contract. Implementations may use SQLite, cloud sync, or another store. */
interface RouteRepository {
    fun startRoute(startedAt: Long): Long
    fun appendPoint(routeId: Long, point: RoutePoint)
    fun updateRoute(
        routeId: Long,
        distanceMeters: Double,
        maxSpeedMps: Float,
        averageSpeedMps: Float,
        endedAt: Long? = null,
    )

    fun recentRoutes(limit: Int = DEFAULT_HISTORY_LIMIT): List<RouteSummary>
    fun dashboardStats(now: Long = System.currentTimeMillis()): DashboardStats

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 30
    }
}

/** User-controlled presentation preferences, kept independent from the UI technology. */
interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setTheme(theme: ThemePreference)
    fun setWidgetEnabled(widget: DashboardWidget, enabled: Boolean)
}

/** In-process state produced by tracking infrastructure and consumed by presentation. */
interface TrackingStateRepository {
    val snapshot: StateFlow<TrackingSnapshot>

    fun publish(snapshot: TrackingSnapshot)
}

/** Platform boundary for tracking lifecycle actions. */
interface TrackingController {
    fun start()
    fun stop()
}
