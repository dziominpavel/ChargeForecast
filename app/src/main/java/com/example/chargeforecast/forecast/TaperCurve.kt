package com.example.chargeforecast.forecast

// Кривая доводки: множители скорости по 5%-корзинам 80-100.
// Дефолт — стартовая модель (калибруется фазой 3); кривая обучается
// по сессиям одного типа подключения и персистится в SessionCache
// (см. design D5). Чистый объект — тестируем на JVM.
object TaperCurve {
    const val TAPER_START_LEVEL = 80
    const val BIN_SIZE_PCT = 5
    const val BIN_COUNT = 4

    val DEFAULT_FACTORS = floatArrayOf(0.55f, 0.40f, 0.30f, 0.20f)

    // Виртуальная ёмкость в процентах: 1% = 10 тыс. мкА·ч.
    private const val PERCENT_VIRTUAL_UAH = 10_000L

    fun binIndex(levelPct: Int): Int? {
        if (levelPct < TAPER_START_LEVEL || levelPct >= 100) return null
        return (levelPct - TAPER_START_LEVEL) / BIN_SIZE_PCT
    }

    // Множитель скорости на уровне: 1.0 в линейной зоне, иначе корзина.
    fun factor(levelPct: Int, factors: FloatArray = DEFAULT_FACTORS): Float {
        val bin = binIndex(levelPct) ?: return 1f
        val f = factors.getOrElse(bin) { 1f }
        return if (f > 0f) f else 1f
    }

    // Интеграл оставшегося времени по энергии: шаг — 1% ёмкости
    // (cap/100), множитель берётся по уровню, на котором лежит очередная
    // порция энергии. Единицы не смешиваются: остаток приходит в мкА·ч,
    // а форма кривой — по уровню. baseRatePctPerMin — скорость,
    // нормализованная к линейной зоне (измеренная / factor(текущий
    // уровень)). Интеграл непрерывен через 80% — в отличие от прежней
    // ступеньки ×2.
    fun remainingMinutesForEnergy(
        remainingUah: Long,
        capacityUah: Long,
        levelPct: Int,
        baseRatePctPerMin: Float,
        factors: FloatArray = DEFAULT_FACTORS
    ): Float {
        if (baseRatePctPerMin <= 0f || remainingUah <= 0L || capacityUah <= 0L) return 0f
        val stepUah = capacityUah / 100.0
        var rem = remainingUah.toDouble()
        var level = levelPct.coerceIn(0, 100)
        var total = 0.0
        while (rem > 0.0 && level < 100) {
            val portion = if (rem < stepUah) rem else stepUah
            // Доля одного процента ёмкости и время на неё при множителе
            // уровня, на котором эта доля лежит.
            total += (portion / stepUah) / (baseRatePctPerMin * factor(level, factors))
            rem -= portion
            level++
        }
        return total.toFloat()
    }

    // Тот же интеграл в процентах: виртуальная ёмкость 1 млн мкА·ч, шаг
    // 10 тыс. мкА·ч — ровно 1%. Оставлено для вызовов, где остаток
    // известен только в процентах (резерв по уровню заряда).
    fun remainingMinutes(
        remainingPct: Float,
        baseRatePctPerMin: Float,
        levelPct: Int,
        factors: FloatArray = DEFAULT_FACTORS
    ): Float = remainingMinutesForEnergy(
        remainingUah = (remainingPct * PERCENT_VIRTUAL_UAH).toLong(),
        capacityUah = 100L * PERCENT_VIRTUAL_UAH,
        levelPct = levelPct,
        baseRatePctPerMin = baseRatePctPerMin,
        factors = factors
    )

    // Слияние выученных множителей с текущей кривой (EWMA): корзины без
    // достаточной статистики (NaN) остаются прежними.
    fun merge(old: FloatArray, learned: FloatArray, weight: Float = 0.5f): FloatArray =
        FloatArray(old.size) { i ->
            val l = learned.getOrElse(i) { Float.NaN }
            if (l.isNaN() || l <= 0f) old[i] else old[i] * (1f - weight) + l * weight
        }
}
