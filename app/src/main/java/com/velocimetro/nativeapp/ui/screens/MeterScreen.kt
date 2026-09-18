package com.velocimetro.nativeapp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocimetro.nativeapp.domain.model.AppSettings
import com.velocimetro.nativeapp.domain.model.DashboardStats
import com.velocimetro.nativeapp.domain.model.TrackingSnapshot
import com.velocimetro.nativeapp.ui.components.HistoricalSummary
import com.velocimetro.nativeapp.ui.components.MapPreview
import com.velocimetro.nativeapp.ui.components.MetricGrid
import com.velocimetro.nativeapp.ui.components.Speedometer
import com.velocimetro.nativeapp.ui.components.StatusPill
import kotlinx.coroutines.delay

@Composable
fun MeterScreen(
    tracking: TrackingSnapshot,
    stats: DashboardStats,
    settings: AppSettings,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val elapsed = trackingElapsed(tracking)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        StatusPill(tracking.isTracking)
        Speedometer(speedMps = tracking.currentSpeedMps)
        Text(
            if (tracking.isTracking) "GPS registrando en segundo plano" else "Listo para medir",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = if (tracking.isTracking) onStop else onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (tracking.isTracking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(
                if (tracking.isTracking) "Finalizar ruta" else "Iniciar ruta",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(16.dp))
        MetricGrid(tracking = tracking, elapsed = elapsed, visible = settings.widgets)
        Spacer(Modifier.height(16.dp))
        MapPreview(point = tracking.lastPoint)
        Spacer(Modifier.height(16.dp))
        HistoricalSummary(stats)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun trackingElapsed(tracking: TrackingSnapshot): Long {
    var now by remember(tracking.isTracking, tracking.startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tracking.isTracking, tracking.startedAt) {
        while (tracking.isTracking) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return tracking.startedAt?.let { now - it } ?: 0L
}
