package com.velocimetro.nativeapp.data

import android.content.Context
import com.velocimetro.nativeapp.domain.model.DashboardStats
import com.velocimetro.nativeapp.domain.model.RoutePoint
import com.velocimetro.nativeapp.domain.model.RouteSummary
import com.velocimetro.nativeapp.domain.repository.RouteRepository

/**
 * Data-layer adapter for the local SQLite source.
 * Keeping mapping here prevents SQLite and Android database APIs from leaking into domain clients.
 */
class SqliteRouteRepository(context: Context) : RouteRepository {
    private val database = RouteDatabase(context.applicationContext)
    private val databaseLock = Any()

    init {
        synchronized(databaseLock) { database.closeInterruptedRoutes() }
    }

    override fun startRoute(startedAt: Long): Long = synchronized(databaseLock) {
        database.startRoute(startedAt)
    }

    override fun appendPoint(routeId: Long, point: RoutePoint) = synchronized(databaseLock) {
        database.appendPoint(routeId, point)
    }

    override fun updateRoute(
        routeId: Long,
        distanceMeters: Double,
        maxSpeedMps: Float,
        averageSpeedMps: Float,
        endedAt: Long?,
    ) = synchronized(databaseLock) {
        database.updateRoute(routeId, distanceMeters, maxSpeedMps, averageSpeedMps, endedAt)
    }

    override fun recentRoutes(limit: Int): List<RouteSummary> = synchronized(databaseLock) {
        database.recentRoutes(limit)
    }

    override fun dashboardStats(now: Long): DashboardStats = synchronized(databaseLock) {
        database.dashboardStats(now)
    }
}
