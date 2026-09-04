package com.example.chargeforecast.battery

import android.os.BatteryManager

// Тип подключения зарядного оборудования. Телефон не различает
// конкретные блоки и кабели — только интерфейс (USB/AC/wireless).
enum class ChargeType {
    AC,
    USB,
    WIRELESS,
    NONE,
    UNKNOWN
}

// Снимок состояния батареи в один момент времени.
// Ток и напряжение nullable: доступны не на всех устройствах,
// их отсутствие не должно ломать расчёт (fallback на дельту уровня).
data class BatterySnapshot(
    val levelPct: Int,
    val isPlugged: Boolean,
    val isCharging: Boolean,
    val isFull: Boolean,
    val chargeType: ChargeType,
    val temperatureC: Float?,
    val voltageV: Float?,
    // Положительный — зарядка (мА), null — датчик недоступен.
    val currentMa: Float?,
    // Остаток заряда (мкА·ч), null — счётчик недоступен.
    // Вместе с уровнем даёт оценку полной ёмкости с первого замера.
    val chargeCounterUah: Long?,
    val timestampMs: Long
)

// Сырые extras из ACTION_BATTERY_CHANGED без зависимости от Intent,
// чтобы маппинг тестировался на JVM без Robolectric.
data class BatteryExtras(
    val level: Int,
    val scale: Int,
    val status: Int,
    val plugged: Int,
    // Десятые градуса, null если extra отсутствует.
    val temperatureTenthsC: Int?,
    // Милливольты, null если extra отсутствует.
    val voltageMv: Int?,
    // Микроамперы, null если датчик недоступен (см. sanitizeCurrentUa).
    val currentNowUa: Long?,
    // Микроампер-часы остатка, null если счётчик недоступен.
    val chargeCounterUah: Long?
)

// Сырое значение BATTERY_PROPERTY_CURRENT_NOW в nullable микроамперы.
// Драйверы отдают Long.MIN_VALUE при ошибке и 0 при отсутствии датчика.
fun sanitizeCurrentUa(rawUa: Long): Long? = when (rawUa) {
    Long.MIN_VALUE -> null
    0L -> null
    else -> rawUa
}

fun BatteryExtras.toSnapshot(nowMs: Long): BatterySnapshot {
    val pct = if (scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
    val full = status == BatteryManager.BATTERY_STATUS_FULL
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
    val pluggedMask = plugged
    val isPlugged = pluggedMask != 0
    val type = when {
        !isPlugged -> ChargeType.NONE
        pluggedMask and BatteryManager.BATTERY_PLUGGED_AC != 0 -> ChargeType.AC
        pluggedMask and BatteryManager.BATTERY_PLUGGED_USB != 0 -> ChargeType.USB
        pluggedMask and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> ChargeType.WIRELESS
        else -> ChargeType.UNKNOWN
    }
    return BatterySnapshot(
        levelPct = pct,
        isPlugged = isPlugged,
        isCharging = charging,
        isFull = full,
        chargeType = type,
        temperatureC = temperatureTenthsC?.let { it / 10f },
        voltageV = voltageMv?.takeIf { it > 0 }?.let { it / 1000f },
        // Ток при разрядке отрицательный — для скорости не используем,
        // показываем модуль только во время зарядки.
        currentMa = currentNowUa
            ?.takeIf { it > 0 && (charging || full) }
            ?.let { it / 1000f },
        chargeCounterUah = chargeCounterUah?.takeIf { it > 0 },
        timestampMs = nowMs
    )
}
