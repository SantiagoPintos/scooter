package com.velocimetro.nativeapp.domain.usecase

import com.velocimetro.nativeapp.domain.model.AppSettings
import com.velocimetro.nativeapp.domain.model.DashboardStats
import com.velocimetro.nativeapp.domain.model.DashboardWidget
import com.velocimetro.nativeapp.domain.model.RouteSummary
import com.velocimetro.nativeapp.domain.model.ThemePreference
import com.velocimetro.nativeapp.domain.model.TrackingSnapshot
import com.velocimetro.nativeapp.domain.repository.RouteRepository
import com.velocimetro.nativeapp.domain.repository.SettingsRepository
import com.velocimetro.nativeapp.domain.repository.TrackingController
import com.velocimetro.nativeapp.domain.repository.TrackingStateRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class DashboardHistory(
    val routes: List<RouteSummary>,
    val stats: DashboardStats,
)

/** Loads all persistent dashboard information together on a worker dispatcher. */
class LoadDashboardHistoryUseCase(
    private val routeRepository: RouteRepository,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend operator fun invoke(): DashboardHistory = withContext(workerDispatcher) {
        DashboardHistory(
            routes = routeRepository.recentRoutes(),
            stats = routeRepository.dashboardStats(),
        )
    }
}

class ObserveTrackingUseCase(private val trackingStateRepository: TrackingStateRepository) {
    operator fun invoke(): StateFlow<TrackingSnapshot> = trackingStateRepository.snapshot
}

class ObserveSettingsUseCase(private val settingsRepository: SettingsRepository) {
    operator fun invoke(): StateFlow<AppSettings> = settingsRepository.settings
}

class ChangeThemeUseCase(private val settingsRepository: SettingsRepository) {
    operator fun invoke(theme: ThemePreference) = settingsRepository.setTheme(theme)
}

class SetDashboardWidgetVisibilityUseCase(private val settingsRepository: SettingsRepository) {
    operator fun invoke(widget: DashboardWidget, enabled: Boolean) =
        settingsRepository.setWidgetEnabled(widget, enabled)
}

class StartTrackingUseCase(private val trackingController: TrackingController) {
    operator fun invoke() = trackingController.start()
}

class StopTrackingUseCase(private val trackingController: TrackingController) {
    operator fun invoke() = trackingController.stop()
}
