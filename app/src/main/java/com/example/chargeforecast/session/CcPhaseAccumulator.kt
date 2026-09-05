package com.example.chargeforecast.session

import com.example.chargeforecast.forecast.TaperCurve

// Накопитель средней скорости CC-фазы текущей сессии: учитывает сэмплы
// измеренной скорости только после рампы (+2 мин от старта) и до
// доводки (уровень < TaperCurve.TAPER_START_LEVEL). Рампа занижает
// скорость, доводка тоже — обе портили бы среднее и приор следующей
// сессии.
// Чистый класс — полностью тестируем на JVM.
class CcPhaseAccumulator(
    private val minAgeMs: Long = MIN_AGE_MS,
    private val taperStartLevel: Int = TaperCurve.TAPER_START_LEVEL
) {
    private var speedSum = 0f
    private var sampleCount = 0

    fun add(ageMs: Long, levelPct: Int, measuredSpeedPctPerMin: Float?) {
        if (measuredSpeedPctPerMin == null || measuredSpeedPctPerMin <= 0f) return
        if (ageMs < minAgeMs) return
        if (levelPct >= taperStartLevel) return
        speedSum += measuredSpeedPctPerMin
        sampleCount++
    }

    fun averagePctPerMin(): Float? =
        if (sampleCount == 0) null else speedSum / sampleCount

    fun reset() {
        speedSum = 0f
        sampleCount = 0
    }

    companion object {
        const val MIN_AGE_MS = 2 * 60_000L
    }
}
