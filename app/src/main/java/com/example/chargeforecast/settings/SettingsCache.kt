package com.example.chargeforecast.settings

import android.content.Context
import android.content.SharedPreferences

// Пользовательские настройки приложения в SharedPreferences (по образцу
// SessionCache): отдельный файл настроек, чтобы кэш сессии и настройки
// не мешали друг другу. Первая настройка — непрозрачность фона виджета
// в процентах (design D2): целые числа, шаг 10 задаёт UI, хранилище
// хранит и нормализует значение.
class SettingsCache(private val prefs: SharedPreferences) {

    fun saveWidgetOpacityPct(pct: Int) {
        prefs.edit()
            .putInt(KEY_WIDGET_OPACITY_PCT, pct.coerceIn(OPACITY_MIN_PCT, OPACITY_MAX_PCT))
            .apply()
    }

    fun loadWidgetOpacityPct(): Int =
        prefs.getInt(KEY_WIDGET_OPACITY_PCT, DEFAULT_WIDGET_OPACITY_PCT)
            .coerceIn(OPACITY_MIN_PCT, OPACITY_MAX_PCT)

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_WIDGET_OPACITY_PCT = "widget_opacity_pct"

        // Шаг настройки — 10%: 0 (фона нет) … 100 (прежний вид).
        const val OPACITY_MIN_PCT = 0
        const val OPACITY_MAX_PCT = 100

        // Виджет без настройки выглядит как прежде — полностью непрозрачный.
        const val DEFAULT_WIDGET_OPACITY_PCT = 100

        fun from(context: Context): SettingsCache =
            SettingsCache(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
