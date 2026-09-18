package com.velocimetro.nativeapp.data

import android.content.Context
import com.velocimetro.nativeapp.domain.model.AppSettings
import com.velocimetro.nativeapp.domain.model.DashboardWidget
import com.velocimetro.nativeapp.domain.model.ThemePreference
import com.velocimetro.nativeapp.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** SharedPreferences-backed implementation of the domain settings contract. */
class SharedPreferencesSettingsRepository(context: Context) : SettingsRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())
    override val settings: StateFlow<AppSettings> = mutableSettings

    override fun setTheme(theme: ThemePreference) = save(mutableSettings.value.copy(theme = theme))

    override fun setWidgetEnabled(widget: DashboardWidget, enabled: Boolean) {
        val widgets = mutableSettings.value.widgets.toMutableSet().apply {
            if (enabled) add(widget) else remove(widget)
        }
        save(mutableSettings.value.copy(widgets = widgets))
    }

    private fun read(): AppSettings {
        val savedWidgets = preferences.getStringSet(KEY_WIDGETS, null)
            ?.mapNotNull { value -> DashboardWidget.entries.find { it.name == value } }
            ?.toSet()
            ?: DashboardWidget.entries.toSet()
        val theme = preferences.getString(KEY_THEME, ThemePreference.SYSTEM.name)
            ?.let { value -> ThemePreference.entries.find { it.name == value } }
            ?: ThemePreference.SYSTEM
        return AppSettings(theme, savedWidgets)
    }

    private fun save(settings: AppSettings) {
        preferences.edit()
            .putString(KEY_THEME, settings.theme.name)
            .putStringSet(KEY_WIDGETS, settings.widgets.map { it.name }.toSet())
            .apply()
        mutableSettings.value = settings
    }

    private companion object {
        const val PREFERENCES_NAME = "velo_settings"
        const val KEY_THEME = "theme"
        const val KEY_WIDGETS = "widgets"
    }
}
