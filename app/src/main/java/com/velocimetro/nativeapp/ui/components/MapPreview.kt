package com.velocimetro.nativeapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.velocimetro.nativeapp.domain.model.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private val DEFAULT_MAP_POINT = GeoPoint(-34.9011, -56.1645)

@Composable
fun MapPreview(point: GeoPoint?) {
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
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    val target = point ?: DEFAULT_MAP_POINT
                    view.getMapAsync { map ->
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(target.latitude, target.longitude),
                                if (point == null) 11.0 else 16.0,
                            ),
                        )
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
