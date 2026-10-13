package com.example.chargeforecast

import com.example.chargeforecast.settings.SettingsCache
import org.junit.Assert.assertEquals
import org.junit.Test

// Настройки на фейковом SharedPreferences: дефолт, запись/чтение, границы.
class SettingsCacheTest {

    @Test
    fun opacity_defaultIsHundred() {
        // Виджет без настройки выглядит как прежде — 100% непрозрачности.
        val cache = SettingsCache(FakeSharedPreferences())
        assertEquals(100, cache.loadWidgetOpacityPct())
    }

    @Test
    fun opacity_roundTrip() {
        val cache = SettingsCache(FakeSharedPreferences())
        cache.saveWidgetOpacityPct(60)
        assertEquals(60, cache.loadWidgetOpacityPct())
    }

    @Test
    fun opacity_zeroIsValid() {
        // 0% — фона нет вовсе, это корректное значение, а не «не задано».
        val cache = SettingsCache(FakeSharedPreferences())
        cache.saveWidgetOpacityPct(0)
        assertEquals(0, cache.loadWidgetOpacityPct())
    }

    @Test
    fun opacity_outOfRangeClamped() {
        // Значение за диапазоном нормализуется, а не тащится в виджет.
        val cache = SettingsCache(FakeSharedPreferences())
        cache.saveWidgetOpacityPct(140)
        assertEquals(SettingsCache.OPACITY_MAX_PCT, cache.loadWidgetOpacityPct())
        cache.saveWidgetOpacityPct(-20)
        assertEquals(SettingsCache.OPACITY_MIN_PCT, cache.loadWidgetOpacityPct())
    }
}
