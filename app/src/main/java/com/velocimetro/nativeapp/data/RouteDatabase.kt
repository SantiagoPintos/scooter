package com.velocimetro.nativeapp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import com.velocimetro.nativeapp.core.DashboardStats
import com.velocimetro.nativeapp.core.RouteSummary
import kotlin.math.ceil

/** SQLite directo: inserciones pequeñas y predecibles durante la ruta. */
class RouteDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
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
        writableDatabase.execSQL("INSERT INTO routes(started_at) VALUES(?)", arrayOf<Any?>(startedAt))
        return writableDatabase.rawQuery("SELECT last_insert_rowid()", null).use {
            it.moveToFirst()
            it.getLong(0)
        }
    }

    fun appendPoint(routeId: Long, location: Location) {
        writableDatabase.execSQL(
            """INSERT INTO route_points(route_id, recorded_at, latitude, longitude, speed_mps, accuracy_meters)
                VALUES(?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                routeId,
                location.time,
                location.latitude,
                location.longitude,
                location.speed,
                location.accuracy,
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
        val average = readableDatabase.rawQuery(
            "SELECT COALESCE(AVG(average_speed_mps), 0) FROM routes WHERE ended_at IS NOT NULL AND distance_meters > 0",
            null,
        ).use { cursor -> cursor.moveToFirst(); cursor.getFloat(0) }
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
