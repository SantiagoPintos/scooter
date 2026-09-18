package com.velocimetro.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.velocimetro.nativeapp.core.AppSettings
import com.velocimetro.nativeapp.core.DashboardStats
import com.velocimetro.nativeapp.core.DashboardWidget
import com.velocimetro.nativeapp.core.GeoPoint
import com.velocimetro.nativeapp.core.RouteSummary
import com.velocimetro.nativeapp.core.ThemePreference
import com.velocimetro.nativeapp.core.TrackingSnapshot
import com.velocimetro.nativeapp.core.formatDateTime
import com.velocimetro.nativeapp.core.formatDuration
import com.velocimetro.nativeapp.core.formatKm
import com.velocimetro.nativeapp.core.formatKmh
import com.velocimetro.nativeapp.core.formatKmhDecimal
import com.velocimetro.nativeapp.core.toKmh
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private enum class VeloTab(val label: String) { METER("Medidor"), HISTORY("Rutas"), SETTINGS("Ajustes") }

@Composable
fun VeloApp(viewModel: MainViewModel, requestTrackingPermission: () -> Unit) {
    val tracking by viewModel.tracking.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val routes by viewModel.routes.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Header(selected = selectedTab, onSelected = { selectedTab = it })
            when (VeloTab.entries[selectedTab]) {
                VeloTab.METER -> MeterScreen(
                    tracking = tracking,
                    stats = stats,
                    settings = settings,
                    onStart = requestTrackingPermission,
                    onStop = viewModel::stopTracking,
                )
                VeloTab.HISTORY -> HistoryScreen(routes = routes, stats = stats, onRefresh = viewModel::refreshHistory)
                VeloTab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onThemeSelected = viewModel::setTheme,
                    onWidgetChanged = viewModel::toggleWidget,
                )
            }
        }
    }
}

@Composable
private fun Header(selected: Int, onSelected: (Int) -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("V", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Velocímetro", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("GPS local · Android nativo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VeloTab.entries.forEachIndexed { index, tab ->
                    FilterChip(
                        selected = selected == index,
                        onClick = { onSelected(index) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MeterScreen(
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
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (tracking.isTracking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(if (tracking.isTracking) "Finalizar ruta" else "Iniciar ruta", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
private fun StatusPill(isTracking: Boolean) {
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
private fun Speedometer(speedMps: Float) {
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
            drawArc(muted, arcStart, arcSweep, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = Stroke(15.dp.toPx(), cap = StrokeCap.Round))
            drawArc(primary, arcStart, arcSweep * (shownKmh / maxKmh), false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = Stroke(15.dp.toPx(), cap = StrokeCap.Round))
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
            val needle = Offset(center.x + cos(radians) * (radius - 35.dp.toPx()), center.y + sin(radians) * (radius - 35.dp.toPx()))
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
private fun MetricGrid(tracking: TrackingSnapshot, elapsed: Long, visible: Set<DashboardWidget>) {
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
private fun MapPreview(point: GeoPoint?) {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            getMapAsync { map -> map.setStyle(MAP_STYLE_URL) }
        }
    }
    DisposableEffect(mapView) {
        mapView.onStart()
        onDispose {
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    Card(Modifier.fillMaxWidth().height(210.dp), shape = RoundedCornerShape(18.dp)) {
        Box(Modifier.fillMaxSize()) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    val target = point ?: GeoPoint(-34.9011, -56.1645)
                    view.getMapAsync { map ->
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(target.latitude, target.longitude), if (point == null) 11.0 else 16.0))
                    }
                },
            )
            Text(
                "© OpenStreetMap contributors · OpenFreeMap",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .86f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun HistoricalSummary(stats: DashboardStats) {
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

@Composable
private fun HistoryScreen(routes: List<RouteSummary>, stats: DashboardStats, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Rutas guardadas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Todos los datos se guardan en este dispositivo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            HistoricalSummary(stats)
        }
        if (routes.isEmpty()) {
            item {
                EmptyRoutes()
            }
        } else {
            items(routes, key = { it.id }) { route -> RouteCard(route) }
        }
    }
}

@Composable
private fun EmptyRoutes() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    onThemeSelected: (ThemePreference) -> Unit,
    onWidgetChanged: (DashboardWidget, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Personalización", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Elige la información que quieres ver sin afectar el registro de la ruta.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsSection("Tema") {
            ThemePreference.entries.forEach { preference ->
                FilterChip(
                    selected = settings.theme == preference,
                    onClick = { onThemeSelected(preference) },
                    label = { Text(themeName(preference)) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        SettingsSection("Panel del medidor") {
            DashboardWidget.entries.forEach { widget ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(widget.label, style = MaterialTheme.typography.titleSmall)
                        Text("Mostrar durante una ruta", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = widget in settings.widgets,
                        onCheckedChange = { onWidgetChanged(widget, it) },
                    )
                }
                if (widget != DashboardWidget.entries.last()) HorizontalDivider()
            }
        }
        SettingsSection("Privacidad y mapas") {
            Text("Las rutas se guardan sólo en SQLite local. El mapa usa MapLibre Native y un estilo de OpenFreeMap basado en OpenStreetMap; puedes sustituir la URL de estilo por tu propio proveedor antes de publicar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
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

private fun themeName(theme: ThemePreference) = when (theme) {
    ThemePreference.SYSTEM -> "Sistema"
    ThemePreference.LIGHT -> "Claro"
    ThemePreference.DARK -> "Oscuro"
}

private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
