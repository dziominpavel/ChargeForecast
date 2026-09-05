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

    // Интеграл оставшегося времени: каждый процент — со своим множителем
    // корзины. baseRatePctPerMin — скорость, нормализованная к линейной
    // зоне (измеренная / factor(текущий уровень)). Интеграл непрерывен
    // через 80% — в отличие от прежней ступеньки ×2.
    fun remainingMinutes(
        remainingPct: Float,
        baseRatePctPerMin: Float,
        levelPct: Int,
        factors: FloatArray = DEFAULT_FACTORS
    ): Float {
        if (baseRatePctPerMin <= 0f || remainingPct <= 0f) return 0f
        var total = 0f
        var rem = remainingPct
        var l = levelPct
        while (rem > 0f && l < 100) {
            val step = minOf(rem, 1f)
            total += step / (baseRatePctPerMin * factor(l, factors))
            rem -= step
            l++
        }
        return total
    }

    // Слияние выученных множителей с текущей кривой (EWMA): корзины без
    // достаточной статистики (NaN) остаются прежними.
    fun merge(old: FloatArray, learned: FloatArray, weight: Float = 0.5f): FloatArray =
        FloatArray(old.size) { i ->
            val l = learned.getOrElse(i) { Float.NaN }
            if (l.isNaN() || l <= 0f) old[i] else old[i] * (1f - weight) + l * weight
        }
}
