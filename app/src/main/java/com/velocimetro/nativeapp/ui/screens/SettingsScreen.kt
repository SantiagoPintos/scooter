package com.velocimetro.nativeapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.velocimetro.nativeapp.domain.model.AppSettings
import com.velocimetro.nativeapp.domain.model.DashboardWidget
import com.velocimetro.nativeapp.domain.model.ThemePreference

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onThemeSelected: (ThemePreference) -> Unit,
    onWidgetChanged: (DashboardWidget, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Personalización", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Elige la información que quieres ver sin afectar el registro de la ruta.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsSection("Tema") {
            ThemePreference.entries.forEach { preference ->
                FilterChip(
                    selected = settings.theme == preference,
                    onClick = { onThemeSelected(preference) },
                    label = { Text(preference.displayName) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        SettingsSection("Panel del medidor") {
            DashboardWidget.entries.forEach { widget ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(widget.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Mostrar durante una ruta",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            Text(
                "Las rutas se guardan sólo en SQLite local. El mapa usa MapLibre Native y un estilo de OpenFreeMap basado en OpenStreetMap; puedes sustituir la URL de estilo por tu propio proveedor antes de publicar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

private val ThemePreference.displayName: String
    get() = when (this) {
        ThemePreference.SYSTEM -> "Sistema"
        ThemePreference.LIGHT -> "Claro"
        ThemePreference.DARK -> "Oscuro"
    }
