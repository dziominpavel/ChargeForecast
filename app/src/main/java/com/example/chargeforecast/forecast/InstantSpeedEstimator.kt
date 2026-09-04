package com.example.chargeforecast.forecast

// Грубая мгновенная оценка скорости с первых секунд сессии:
// ёмкость выводится из счётчика остатка и уровня, скорость — из тока.
// Точность низкая (ток плавает, уровень грубый), но цифры есть сразу;
// по мере набора измеренной скорости вес instant-оценки падает до нуля.
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

    // Максимум тока за сессию: блок раскачивается первые минуты и ток
    // растёт (0.1 → 1.0 %/мин на глазах), плюс включённый экран отъедает
    // часть. Для instant-оценки берём пик — он ближе к установившемуся.
    fun updateSessionMax(previousMaxUa: Long?, currentUa: Long?): Long? {
        if (currentUa == null || currentUa <= 0) return previousMaxUa
        if (previousMaxUa == null || previousMaxUa <= 0) return currentUa
        return maxOf(previousMaxUa, currentUa)
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
