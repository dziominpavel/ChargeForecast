package com.example.chargeforecast.battery

import java.io.File

// Best-effort чтение тока из sysfs: часть драйверов (в т.ч. HiSilicon
// на Huawei) не отдаёт BATTERY_PROPERTY_CURRENT_NOW через BatteryManager,
// но пишет файл. Срабатывает не везде (SELinux режет) — любая неудача
// молча даёт null, это не ошибка. Чистые проверки отдельно.
object SysfsCurrent {

    private val PATHS = listOf(
        "/sys/class/power_supply/battery/current_now",
        // current_avg — стандартные мкА, сглаженный ток: часто живее.
        "/sys/class/power_supply/battery/current_avg",
        "/sys/class/power_supply/bms/current_now",
        "/sys/class/power_supply/bms/current_avg"
        // vendor-узлы Huawei (batt_current_ua_now, chg_current_now,
        // hw_power/...) добавим ТОЧНО по дампу прошивки — семантика
        // единиц у них не гарантирована.
    )

    // Знак в разных драйверах разный (минус = зарядка или разряд).
    // Берём модуль: для скорости важна величина, фильтр «зарядка ли»
    // уже стоит в снимке (currentMa только при charging/full).
    fun normalizeUa(raw: Long): Long {
        val magnitude = if (raw < 0) -raw else raw
        // Эвристика единиц: зарядка 0.1–10 А. В мА это 100–10 000
        // (файлы HiSilicon пишут мА), в мкА — 100 000+. Всё, что
        // меньше 100 000, считаем мА и умножаем.
        return if (magnitude < 100_000L) magnitude * 1000L else magnitude
    }

    fun sanitizeUa(normalizedUa: Long): Long? =
        normalizedUa.takeIf { it in 100_000L..50_000_000L }

    fun parseCurrentUa(text: String): Long? {
        val raw = text.trim().toLongOrNull() ?: return null
        return sanitizeUa(normalizeUa(raw))
    }

    fun readCurrentNowUa(): Long? {
        for (path in PATHS) {
            try {
                val parsed = parseCurrentUa(File(path).readText())
                if (parsed != null) return parsed
            } catch (_: Exception) {
                // Нет файла или нет доступа — пробуем следующий путь.
            }
        }
        return null
    }
}
