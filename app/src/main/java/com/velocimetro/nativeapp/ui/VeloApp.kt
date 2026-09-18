package com.velocimetro.nativeapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.velocimetro.nativeapp.ui.components.AppHeader
import com.velocimetro.nativeapp.ui.screens.HistoryScreen
import com.velocimetro.nativeapp.ui.screens.MeterScreen
import com.velocimetro.nativeapp.ui.screens.SettingsScreen

internal enum class VeloTab(val label: String) {
    METER("Medidor"),
    HISTORY("Rutas"),
    SETTINGS("Ajustes"),
}

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
            AppHeader(selected = selectedTab, onSelected = { selectedTab = it })
            when (VeloTab.entries[selectedTab]) {
                VeloTab.METER -> MeterScreen(
                    tracking = tracking,
                    stats = stats,
                    settings = settings,
                    onStart = requestTrackingPermission,
                    onStop = viewModel::stopTracking,
                )

                VeloTab.HISTORY -> HistoryScreen(
                    routes = routes,
                    stats = stats,
                    onRefresh = viewModel::refreshHistory,
                )

                VeloTab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onThemeSelected = viewModel::setTheme,
                    onWidgetChanged = viewModel::toggleWidget,
                )
            }
        }
    }
}
