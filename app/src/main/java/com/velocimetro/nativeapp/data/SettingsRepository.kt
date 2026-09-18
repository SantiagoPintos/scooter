package com.velocimetro.nativeapp.data

import android.content.Context
import com.velocimetro.nativeapp.core.AppSettings
import com.velocimetro.nativeapp.core.DashboardWidget
import com.velocimetro.nativeapp.core.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = mutableSettings

    fun setTheme(theme: ThemePreference) = save(mutableSettings.value.copy(theme = theme))

    fun toggleWidget(widget: DashboardWidget, enabled: Boolean) {
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
