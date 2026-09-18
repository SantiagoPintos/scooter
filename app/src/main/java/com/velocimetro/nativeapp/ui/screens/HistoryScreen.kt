package com.velocimetro.nativeapp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.velocimetro.nativeapp.domain.model.DashboardStats
import com.velocimetro.nativeapp.domain.model.RouteSummary
import com.velocimetro.nativeapp.ui.formatDateTime
import com.velocimetro.nativeapp.ui.formatDuration
import com.velocimetro.nativeapp.ui.formatKm
import com.velocimetro.nativeapp.ui.formatKmh
import com.velocimetro.nativeapp.ui.formatKmhDecimal
import com.velocimetro.nativeapp.ui.components.HistoricalSummary

@Composable
fun HistoryScreen(routes: List<RouteSummary>, stats: DashboardStats, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Rutas guardadas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Todos los datos se guardan en este dispositivo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            HistoricalSummary(stats)
        }
        if (routes.isEmpty()) {
            item { EmptyRoutes() }
        } else {
            items(routes, key = { it.id }) { route -> RouteCard(route) }
        }
    }
}

@Composable
private fun EmptyRoutes() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(
            "Aún no hay rutas finalizadas. Inicia una desde el medidor para ver sus estadísticas aquí.",
            modifier = Modifier.padding(18.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RouteCard(route: RouteSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(route.startedAt.formatDateTime(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            ) {
                RouteValue("Distancia", route.distanceMeters.formatKm())
                RouteValue("Máxima", route.maxSpeedMps.formatKmh())
                RouteValue("Promedio", route.averageSpeedMps.formatKmhDecimal())
            }
            route.endedAt?.let { endedAt ->
                Text(
                    "Duración ${ (endedAt - route.startedAt).formatDuration() }",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RouteValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}
