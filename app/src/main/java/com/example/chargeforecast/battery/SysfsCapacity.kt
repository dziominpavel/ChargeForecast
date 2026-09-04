package com.example.chargeforecast.battery

import java.io.File

// Best-effort чтение проектной ёмкости из sysfs для первой сессии,
// когда счётчик ещё недоступен. Срабатывает не везде (SELinux режет) —
// любая неудача молча даёт null, это не ошибка. Чистые проверки отдельно.
object SysfsCapacity {

    private val PATHS = listOf(
        "/sys/class/power_supply/battery/charge_full_design",
        "/sys/class/power_supply/battery/charge_full",
        "/sys/class/power_supply/bms/charge_full_design",
        "/sys/class/power_supply/bms/charge_full"
    )

    // Значения в этих файлах — мкА·ч. Если драйвер отдал мА·ч (тысячи),
    // санитайзер отбросит: лучше null, чем ошибка в 1000 раз.
    fun sanitizeCapacityUah(raw: Long?): Long? =
        raw?.takeIf { it in 500_000L..30_000_000L }

    fun readDesignCapacityUah(): Long? {
        for (path in PATHS) {
            try {
                val raw = File(path).readText().trim().toLongOrNull()
                val sane = sanitizeCapacityUah(raw)
                if (sane != null) return sane
            } catch (_: Exception) {
                // Нет файла или нет доступа — пробуем следующий путь.
            }
        }
        return null
    }
}
