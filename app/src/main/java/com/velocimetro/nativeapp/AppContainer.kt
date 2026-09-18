package com.velocimetro.nativeapp

import android.content.Context
import com.velocimetro.nativeapp.data.SharedPreferencesSettingsRepository
import com.velocimetro.nativeapp.data.SqliteRouteRepository
import com.velocimetro.nativeapp.domain.repository.RouteRepository
import com.velocimetro.nativeapp.domain.repository.SettingsRepository
import com.velocimetro.nativeapp.domain.repository.TrackingController
import com.velocimetro.nativeapp.domain.repository.TrackingStateRepository
import com.velocimetro.nativeapp.domain.usecase.ChangeThemeUseCase
import com.velocimetro.nativeapp.domain.usecase.LoadDashboardHistoryUseCase
import com.velocimetro.nativeapp.domain.usecase.ObserveSettingsUseCase
import com.velocimetro.nativeapp.domain.usecase.ObserveTrackingUseCase
import com.velocimetro.nativeapp.domain.usecase.SetDashboardWidgetVisibilityUseCase
import com.velocimetro.nativeapp.domain.usecase.StartTrackingUseCase
import com.velocimetro.nativeapp.domain.usecase.StopTrackingUseCase
import com.velocimetro.nativeapp.tracking.AndroidTrackingController
import com.velocimetro.nativeapp.tracking.TrackingStore

/**
 * Small manual composition root. It keeps concrete Android implementations at the app edge
 * without adding a dependency-injection framework, and provides one SQLite helper per process.
 */
class AppContainer private constructor(context: Context) {
    private val appContext = context.applicationContext

    private val routeRepository: RouteRepository = SqliteRouteRepository(appContext)
    private val settingsRepository: SettingsRepository = SharedPreferencesSettingsRepository(appContext)
    private val trackingStateRepository: TrackingStateRepository = TrackingStore
    private val trackingController: TrackingController = AndroidTrackingController(appContext)

    val loadDashboardHistory = LoadDashboardHistoryUseCase(routeRepository)
    val observeTracking = ObserveTrackingUseCase(trackingStateRepository)
    val observeSettings = ObserveSettingsUseCase(settingsRepository)
    val changeTheme = ChangeThemeUseCase(settingsRepository)
    val setDashboardWidgetVisibility = SetDashboardWidgetVisibilityUseCase(settingsRepository)
    val startTracking = StartTrackingUseCase(trackingController)
    val stopTracking = StopTrackingUseCase(trackingController)

    /** Exposed only to infrastructure that records locations. */
    fun routeRepository(): RouteRepository = routeRepository

    /** Exposed only to infrastructure that publishes live measurements. */
    fun trackingStateRepository(): TrackingStateRepository = trackingStateRepository

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun from(context: Context): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer(context).also { instance = it }
        }
    }
}
