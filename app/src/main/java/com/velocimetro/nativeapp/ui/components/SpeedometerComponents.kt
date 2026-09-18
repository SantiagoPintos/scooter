package com.velocimetro.nativeapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.velocimetro.nativeapp.domain.model.DashboardStats
import com.velocimetro.nativeapp.domain.model.DashboardWidget
import com.velocimetro.nativeapp.domain.model.TrackingSnapshot
import com.velocimetro.nativeapp.ui.formatDuration
import com.velocimetro.nativeapp.ui.formatKm
import com.velocimetro.nativeapp.ui.formatKmh
import com.velocimetro.nativeapp.ui.formatKmhDecimal
import com.velocimetro.nativeapp.ui.toKmh
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun StatusPill(isTracking: Boolean) {
    val color = if (isTracking) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, color, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(if (isTracking) "EN RUTA" else "EN PAUSA", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun Speedometer(speedMps: Float) {
    val maxKmh = 180f
    val shownKmh = speedMps.toKmh().coerceIn(0f, maxKmh)
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.surfaceVariant
    val text = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.22f)
            .padding(top = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height * .58f)
            val radius = min(size.width, size.height) * .39f
            val arcStart = 150f
            val arcSweep = 240f
            drawArc(
                muted,
                arcStart,
                arcSweep,
                false,
                Offset(center.x - radius, center.y - radius),
                Size(radius * 2, radius * 2),
                style = Stroke(15.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                primary,
                arcStart,
                arcSweep * (shownKmh / maxKmh),
                false,
                Offset(center.x - radius, center.y - radius),
                Size(radius * 2, radius * 2),
                style = Stroke(15.dp.toPx(), cap = StrokeCap.Round),
            )
            for (index in 0..12) {
                val degrees = arcStart + (arcSweep / 12f * index)
                val radians = (degrees * PI / 180).toFloat()
                val outer = Offset(center.x + cos(radians) * radius, center.y + sin(radians) * radius)
                val innerRadius = radius - if (index % 2 == 0) 20.dp.toPx() else 12.dp.toPx()
                val inner = Offset(center.x + cos(radians) * innerRadius, center.y + sin(radians) * innerRadius)
                drawLine(text.copy(alpha = .65f), outer, inner, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            val angle = arcStart + arcSweep * (shownKmh / maxKmh)
            val radians = (angle * PI / 180).toFloat()
            val needle = Offset(
                center.x + cos(radians) * (radius - 35.dp.toPx()),
                center.y + sin(radians) * (radius - 35.dp.toPx()),
            )
            drawLine(primary, center, needle, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(primary, radius = 9.dp.toPx(), center = center)
            drawCircle(Color.White.copy(alpha = .9f), radius = 3.dp.toPx(), center = center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 40.dp)) {
            Text(shownKmh.toInt().toString(), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black)
            Text("km/h", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MetricGrid(tracking: TrackingSnapshot, elapsed: Long, visible: Set<DashboardWidget>) {
    val metrics = buildList {
        if (DashboardWidget.DISTANCE in visible) add(DashboardWidget.DISTANCE to tracking.distanceMeters.formatKm())
        if (DashboardWidget.MAX_SPEED in visible) add(DashboardWidget.MAX_SPEED to tracking.maxSpeedMps.formatKmh())
        if (DashboardWidget.AVERAGE_SPEED in visible) add(DashboardWidget.AVERAGE_SPEED to tracking.averageSpeedMps.formatKmhDecimal())
        if (DashboardWidget.DURATION in visible) add(DashboardWidget.DURATION to elapsed.formatDuration())
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, value) -> MetricCard(label.label, value, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(5.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
fun HistoricalSummary(stats: DashboardStats) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Promedio histórico", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stats.historicalAverageSpeedMps.formatKmhDecimal(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text("Promedio semanal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stats.weeklyAverageMeters.formatKm(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}
