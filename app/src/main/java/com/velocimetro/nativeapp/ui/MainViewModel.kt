package com.velocimetro.nativeapp.ui

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.velocimetro.nativeapp.core.DashboardStats
import com.velocimetro.nativeapp.core.DashboardWidget
import com.velocimetro.nativeapp.core.RouteSummary
import com.velocimetro.nativeapp.core.ThemePreference
import com.velocimetro.nativeapp.data.RouteDatabase
import com.velocimetro.nativeapp.data.SettingsRepository
import com.velocimetro.nativeapp.tracking.LocationTrackingService
import com.velocimetro.nativeapp.tracking.TrackingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = RouteDatabase(application)
    private val settingsRepository = SettingsRepository(application)

    val tracking = TrackingStore.snapshot
    val settings = settingsRepository.settings

    private val mutableRoutes = MutableStateFlow<List<RouteSummary>>(emptyList())
    val routes: StateFlow<List<RouteSummary>> = mutableRoutes

    private val mutableStats = MutableStateFlow(DashboardStats())
    val stats: StateFlow<DashboardStats> = mutableStats

    init {
        refreshHistory()
        viewModelScope.launch {
            tracking.drop(1).collect { snapshot ->
                if (!snapshot.isTracking && snapshot.routeId != null) refreshHistory()
            }
        }
    }

    fun startTracking() {
        ContextCompat.startForegroundService(
            getApplication(),
            LocationTrackingService.startIntent(getApplication()),
        )
    }

    fun stopTracking() {
        getApplication<Application>().startService(LocationTrackingService.stopIntent(getApplication()))
    }

    fun refreshHistory() {
        viewModelScope.launch {
            mutableRoutes.value = database.recentRoutes()
            mutableStats.value = database.dashboardStats()
        }
    }

    fun setTheme(theme: ThemePreference) = settingsRepository.setTheme(theme)

    fun toggleWidget(widget: DashboardWidget, enabled: Boolean) =
        settingsRepository.toggleWidget(widget, enabled)
}
