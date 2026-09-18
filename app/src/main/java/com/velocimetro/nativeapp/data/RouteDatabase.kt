package com.velocimetro.nativeapp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import com.velocimetro.nativeapp.domain.model.DashboardStats
import com.velocimetro.nativeapp.domain.model.RoutePoint
import com.velocimetro.nativeapp.domain.model.RouteSummary
import kotlin.math.ceil

/** SQLite data source. Only repository implementations should reference this class. */
internal class RouteDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE routes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                distance_meters REAL NOT NULL DEFAULT 0,
                max_speed_mps REAL NOT NULL DEFAULT 0,
                average_speed_mps REAL NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE route_points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                route_id INTEGER NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
                recorded_at INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                speed_mps REAL NOT NULL,
                accuracy_meters REAL NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_points_route_time ON route_points(route_id, recorded_at)")
        db.execSQL("CREATE INDEX index_routes_started ON routes(started_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun startRoute(startedAt: Long): Long {
        return requireNotNull(
            writableDatabase.insertOrThrow(
                "routes",
                null,
                ContentValues(1).apply { put("started_at", startedAt) },
            ),
        ) { "SQLite did not return an id for the new route" }
    }

    fun appendPoint(routeId: Long, point: RoutePoint) {
        writableDatabase.execSQL(
            """INSERT INTO route_points(route_id, recorded_at, latitude, longitude, speed_mps, accuracy_meters)
                VALUES(?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                routeId,
                point.recordedAt,
                point.latitude,
                point.longitude,
                point.speedMps,
                point.accuracyMeters,
            ),
        )
    }

    fun updateRoute(
        routeId: Long,
        distanceMeters: Double,
        maxSpeedMps: Float,
        averageSpeedMps: Float,
        endedAt: Long? = null,
    ) {
        if (endedAt == null) {
            writableDatabase.execSQL(
                "UPDATE routes SET distance_meters=?, max_speed_mps=?, average_speed_mps=? WHERE id=?",
                arrayOf<Any?>(distanceMeters, maxSpeedMps, averageSpeedMps, routeId),
            )
        } else {
            writableDatabase.execSQL(
                """UPDATE routes SET distance_meters=?, max_speed_mps=?, average_speed_mps=?, ended_at=?
                    WHERE id=?""",
                arrayOf<Any?>(distanceMeters, maxSpeedMps, averageSpeedMps, endedAt, routeId),
            )
        }
    }

    /**
     * A foreground service can be killed without receiving an orderly stop. The last point and
     * route totals are checkpointed on every accepted sample, so closing this orphaned session
     * makes the durable portion visible again instead of silently losing it from the history.
     */
    fun closeInterruptedRoutes() {
        writableDatabase.execSQL(
            """UPDATE routes
                SET ended_at = COALESCE(
                    (SELECT MAX(recorded_at) FROM route_points WHERE route_id = routes.id),
                    started_at
                )
                WHERE ended_at IS NULL""",
        )
    }

    fun recentRoutes(limit: Int = 30): List<RouteSummary> = readableDatabase.rawQuery(
        """SELECT id, started_at, ended_at, distance_meters, max_speed_mps, average_speed_mps
            FROM routes WHERE ended_at IS NOT NULL ORDER BY started_at DESC LIMIT ?""",
        arrayOf(limit.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    RouteSummary(
                        id = cursor.getLong(0),
                        startedAt = cursor.getLong(1),
                        endedAt = cursor.getLong(2),
                        distanceMeters = cursor.getDouble(3),
                        maxSpeedMps = cursor.getFloat(4),
                        averageSpeedMps = cursor.getFloat(5),
                    ),
                )
            }
        }
    }

    fun dashboardStats(now: Long = System.currentTimeMillis()): DashboardStats {
        val (completedDistance, completedDurationMillis) = readableDatabase.rawQuery(
            """SELECT COALESCE(SUM(distance_meters), 0), COALESCE(SUM(ended_at - started_at), 0)
                FROM routes
                WHERE ended_at IS NOT NULL AND ended_at > started_at AND distance_meters > 0""",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getDouble(0) to cursor.getLong(1)
        }
        // Aggregate distance / aggregate time keeps long routes from being underweighted.
        val average = if (completedDurationMillis > 0) {
            (completedDistance / (completedDurationMillis / 1_000.0)).toFloat()
        } else {
            0f
        }
        val (totalDistance, firstRouteAt) = readableDatabase.rawQuery(
            """SELECT COALESCE(SUM(distance_meters), 0), MIN(started_at)
                FROM routes WHERE ended_at IS NOT NULL""",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getDouble(0) to if (cursor.isNull(1)) null else cursor.getLong(1)
        }
        val weeks = firstRouteAt?.let { first -> ceil((now - first).toDouble() / SEVEN_DAYS_MILLIS).coerceAtLeast(1.0) } ?: 1.0
        return DashboardStats(average, totalDistance / weeks)
    }

    companion object {
        private const val DATABASE_NAME = "velocimetro.db"
        private const val DATABASE_VERSION = 1
        private const val SEVEN_DAYS_MILLIS = 7 * 24 * 60 * 60 * 1_000L
    }
}
