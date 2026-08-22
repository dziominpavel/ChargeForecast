package com.example.chargeforecast.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val VoltDarkScheme = darkColorScheme(
    primary = Volt,
    onPrimary = Obsidian,
    secondary = Volt,
    onSecondary = Obsidian,
    background = Obsidian,
    onBackground = OnSurfaceLight,
    surface = Carbon,
    onSurface = OnSurfaceLight,
    surfaceVariant = CarbonElevated,
    onSurfaceVariant = OnSurfaceMuted
)

private val VoltLightScheme = lightColorScheme(
    primary = Volt,
    onPrimary = Obsidian,
    secondary = Volt,
    onSecondary = Obsidian,
    background = Obsidian,
    onBackground = OnSurfaceLight,
    surface = Carbon,
    onSurface = OnSurfaceLight,
    surfaceVariant = CarbonElevated,
    onSurfaceVariant = OnSurfaceMuted
)

/**
 * Тема приложения. По задумке интерфейс всегда тёмный (палитра Volt),
 * но [darkTheme] оставлен для будущей настройки.
 */
@Composable
fun ChargeForecastTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) VoltDarkScheme else VoltLightScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
