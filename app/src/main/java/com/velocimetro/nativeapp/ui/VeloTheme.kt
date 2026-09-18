package com.velocimetro.nativeapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import com.velocimetro.nativeapp.core.ThemePreference

private val NightColors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    secondary = Color(0xFFA5F3FC),
    tertiary = Color(0xFF86EFAC),
    background = Color(0xFF0B1220),
    surface = Color(0xFF111C2E),
    surfaceVariant = Color(0xFF1C2B40),
)

private val DayColors = lightColorScheme(
    primary = Color(0xFF0369A1),
    secondary = Color(0xFF0E7490),
    tertiary = Color(0xFF15803D),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE2E8F0),
)

@Composable
fun VeloTheme(preference: ThemePreference, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = remember(preference, systemDark) {
        when (preference) {
            ThemePreference.SYSTEM -> systemDark
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
        }
    }
    MaterialTheme(colorScheme = if (dark) NightColors else DayColors, content = content)
}
