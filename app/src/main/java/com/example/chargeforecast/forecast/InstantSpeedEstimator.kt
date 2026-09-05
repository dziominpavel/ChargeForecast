package com.example.chargeforecast.forecast

import kotlin.math.exp
import kotlin.math.roundToLong

// Грубая мгновенная оценка скорости с первых секунд сессии:
// ёмкость выводится из счётчика остатка и уровня, скорость — из тока.
// Точность низкая (ток плавает, уровень грубый), но цифры есть сразу;
// в fusion её вес доминирует только в первые секунды, дальше ведут
// измеренные спидометры (S1/S3).
// Чистый объект — полностью тестируем на JVM.
object InstantSpeedEstimator {

    // Полная ёмкость (мкА·ч) из остатка и уровня. Null — данных нет.
    fun estimateCapacityUah(chargeCounterUah: Long?, levelPct: Int): Long? {
        if (chargeCounterUah == null || chargeCounterUah <= 0) return null
        if (levelPct <= 0 || levelPct > 100) return null
        return chargeCounterUah * 100L / levelPct
    }

    // Мгновенная скорость (%/мин) из тока и ёмкости. Null — данных нет.
    fun instantSpeedPctPerMin(currentUa: Long?, capacityUah: Long?): Float? {
        if (currentUa == null || currentUa <= 0) return null
        if (capacityUah == null || capacityUah <= 0) return null
        return currentUa.toFloat() / capacityUah.toFloat() * 100f / 60f
    }

    // Ток для instant-оценки: EMA с постоянной времени ~15 сек вместо
    // пика сессии. Пик запоминал рампу и выбросы драйвера навсегда
    // (завышенный старт «25 мин»); EMA гасит и рампу, и выбросы.
    // Мусор вместо тока (null/0/отрицательный) предыдущую EMA не портит.
    const val EMA_TAU_MS = 15_000L

    fun updateEmaCurrentUa(previousEmaUa: Long?, currentUa: Long?, dtMs: Long): Long? {
        if (currentUa == null || currentUa <= 0L) return previousEmaUa
        val prev = previousEmaUa
        if (prev == null || prev <= 0L) return currentUa
        val alpha = 1f - exp(-dtMs.coerceAtLeast(0L).toFloat() / EMA_TAU_MS)
        return (prev + alpha * (currentUa - prev)).roundToLong()
    }

    // Ниже — мусор рампы, а не зарядка (даёт «1000 часов» остатка).
    const val MIN_CREDIBLE_SPEED_PCT_PER_MIN = 0.05f

    // Выбор опоры мгновенной скорости. Мусор рампы (ниже порога) НЕ должен
    // затенять приор из кэша — на разгоне берём максимум из двух опор:
    // рампа занижает, кэш прошлой сессии ближе к правде с секунды 0.
    fun selectInstantSpeed(instantLive: Float?, prior: Float?): Float? {
        val credible = instantLive?.takeIf { it >= MIN_CREDIBLE_SPEED_PCT_PER_MIN }
        return when {
            credible != null && prior != null -> maxOf(credible, prior)
            credible != null -> credible
            else -> prior
        }
    }
}
