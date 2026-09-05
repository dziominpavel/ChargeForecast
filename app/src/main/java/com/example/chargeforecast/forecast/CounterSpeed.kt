package com.example.chargeforecast.forecast

// Спидометр S1: скорость по дельте счётчика остатка (мкА·ч).
// МНК (least squares) по окну вместо двух концевых точек — шум гасится
// примерно в пять раз лучше. Разрешение счётчика на порядки лучше шага
// уровня 1%: скорость видна за секунды, а не за минуты.
// Два окна — 30 сек (отклик) и 2 мин (стабильность); движок держит
// два инстанса с разным окном.
// Чистый класс — полностью тестируем на JVM.
class CounterSpeedometer(
    private val windowMs: Long = WINDOW_MS,
    private val minSpanMs: Long = MIN_SPAN_MS
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun addSample(timeMs: Long, counterUah: Long) {
        val last = samples.lastOrNull()
        // Дубли счётчика подряд не несут информации — храним свежий.
        if (last != null && last.second == counterUah) {
            samples.removeLast()
        }
        samples.addLast(timeMs to counterUah)
        evictOlderThan(timeMs)
    }

    fun reset() {
        samples.clear()
    }

    // МНК-наклон счётчика по времени → %/мин. Null — мало точек,
    // короткое окно, счётчик стоит или идёт назад.
    fun speedPctPerMin(nowMs: Long, capacityUah: Long?): Float? {
        if (capacityUah == null || capacityUah <= 0) return null
        evictOlderThan(nowMs)
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        if (last.first - first.first < minSpanMs) return null
        // МНК: наклон = Σ(dx·dc) / Σ(dx²), dx/dc — отклонения от средних.
        var tSum = 0.0
        var cSum = 0.0
        samples.forEach { (t, c) -> tSum += t; cSum += c }
        val tBar = tSum / samples.size
        val cBar = cSum / samples.size
        var sxx = 0.0
        var sxy = 0.0
        samples.forEach { (t, c) ->
            val dx = t - tBar
            val dc = c - cBar
            sxx += dx * dx
            sxy += dx * dc
        }
        if (sxx <= 0.0) return null
        val slopeUahPerMs = sxy / sxx
        if (slopeUahPerMs <= 0.0) return null
        val uahPerMin = slopeUahPerMs * 60_000.0
        return (uahPerMin / capacityUah * 100.0).toFloat().takeIf { it > 0f }
    }

    private fun evictOlderThan(nowMs: Long) {
        while (samples.size > 1 && nowMs - samples.first().first > windowMs) {
            samples.removeFirst()
        }
    }

    companion object {
        // Два окна (фаза 1): короткое — отклик, длинное — стабильность.
        const val WINDOW_MS = 2 * 60_000L
        const val WINDOW_SHORT_MS = 30_000L
        // Физический минимум: второй тик опроса на 1 Гц (2 сек) — цифры
        // с первых секунд, шум ранних точек гасится весом и fusion.
        const val MIN_SPAN_MS = 2_000L
    }
}

// Живое уточнение ёмкости: стартовая оценка (счётчик/уровень),
// затем рефайн по Δсчётчик/Δуровень при наборе ≥2%. Чистый класс.
class CapacityTracker {

    private var anchorCounterUah: Long? = null
    private var anchorLevel: Int? = null
    private var estimateUah: Long? = null

    fun update(counterUah: Long?, levelPct: Int): Long? {
        if (counterUah == null || counterUah <= 0) return estimateUah
        if (levelPct <= 0 || levelPct > 100) return estimateUah
        val anchorCounter = anchorCounterUah
        val anchorLevel = anchorLevel
        if (estimateUah == null || anchorCounter == null || anchorLevel == null) {
            estimateUah = InstantSpeedEstimator.estimateCapacityUah(counterUah, levelPct)
            anchorCounterUah = counterUah
            this.anchorLevel = levelPct
            return estimateUah
        }
        val deltaLevel = levelPct - anchorLevel
        val deltaCounter = counterUah - anchorCounter
        if (deltaLevel >= REFINEMENT_MIN_DELTA_LEVEL && deltaCounter > 0) {
            val refined = deltaCounter * 100L / deltaLevel
            // Квантование уровня (целые %) и мусор драйвера не должны
            // ломать оценку: рефайн принимаем только в разумном коридоре
            // вокруг текущей оценки (±20%) — живой дрейф ёмкости он
            // отслеживает, выбросы (в разы за один шаг) — нет.
            val current = estimateUah
            val inCorridor = current == null ||
                refined * 100L >= current * REFINEMENT_MIN_RATIO &&
                refined * 100L <= current * REFINEMENT_MAX_RATIO
            if (refined in MIN_SANE_UAH..MAX_SANE_UAH && inCorridor) {
                estimateUah = refined
                anchorCounterUah = counterUah
                this.anchorLevel = levelPct
            }
        }
        return estimateUah
    }

    fun reset() {
        anchorCounterUah = null
        anchorLevel = null
        estimateUah = null
    }

    companion object {
        const val REFINEMENT_MIN_DELTA_LEVEL = 2
        const val REFINEMENT_MIN_RATIO = 80L
        const val REFINEMENT_MAX_RATIO = 120L
        const val MIN_SANE_UAH = 500_000L
        const val MAX_SANE_UAH = 30_000_000L
    }
}
