package com.velocimetro.nativeapp.core

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

fun Float.toKmh(): Float = this * 3.6f

fun Double.formatKm(): String = String.format(Locale.getDefault(), "%.2f km", this / 1_000.0)

fun Float.formatKmh(): String = "${toKmh().roundToInt()} km/h"

fun Float.formatKmhDecimal(): String = String.format(Locale.getDefault(), "%.1f km/h", toKmh())

fun Long.formatDuration(): String {
    val totalSeconds = (this / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

fun Long.formatDateTime(): String = DateFormat.getDateTimeInstance(
    DateFormat.MEDIUM,
    DateFormat.SHORT,
    Locale.getDefault(),
).format(Date(this))
