package com.velocimetro.nativeapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.velocimetro.nativeapp.AppContainer
import com.velocimetro.nativeapp.domain.model.DashboardStats
import com.velocimetro.nativeapp.domain.model.DashboardWidget
import com.velocimetro.nativeapp.domain.model.RouteSummary
import com.velocimetro.nativeapp.domain.model.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val dependencies = AppContainer.from(application)

    val tracking = dependencies.observeTracking()
    val settings = dependencies.observeSettings()

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
        dependencies.startTracking()
    }

    fun stopTracking() {
        dependencies.stopTracking()
    }

    fun refreshHistory() {
        viewModelScope.launch {
            val history = dependencies.loadDashboardHistory()
            mutableRoutes.value = history.routes
            mutableStats.value = history.stats
        }
    }

    fun setTheme(theme: ThemePreference) = dependencies.changeTheme(theme)

    fun toggleWidget(widget: DashboardWidget, enabled: Boolean) =
        dependencies.setDashboardWidgetVisibility(widget, enabled)
}
