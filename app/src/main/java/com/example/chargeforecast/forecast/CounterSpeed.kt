package com.example.chargeforecast.forecast

// Второй спидометр: скорость по дельте счётчика остатка (мкА·ч).
// Разрешение на порядки лучше шага уровня 1%: скорость видна за
// десятки секунд, а не за минуты ожидания второго процента.
// Чистый класс — полностью тестируем на JVM.
class CounterSpeedometer(
    private val windowMs: Long = WINDOW_MS,
    private val minSpanMs: Long = MIN_SPAN_MS
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun addSample(timeMs: Long, counterUah: Long) {
        val last = samples.lastOrNull()
        if (last != null && last.second == counterUah) {
            samples.removeLast()
        }
        samples.addLast(timeMs to counterUah)
        evictOlderThan(timeMs)
    }

    fun reset() {
        samples.clear()
    }

    // Null — мало точек, счётчик стоит или идёт назад (не зарядка).
    fun speedPctPerMin(nowMs: Long, capacityUah: Long?): Float? {
        if (capacityUah == null || capacityUah <= 0) return null
        evictOlderThan(nowMs)
        if (samples.size < 2) return null
        val (firstTime, firstCounter) = samples.first()
        val (lastTime, lastCounter) = samples.last()
        if (lastTime - firstTime < minSpanMs) return null
        val deltaCounter = lastCounter - firstCounter
        if (deltaCounter <= 0) return null
        val spanMin = (lastTime - firstTime) / 60_000f
        return (deltaCounter / spanMin / capacityUah.toFloat() * 100f)
            .takeIf { it > 0f }
    }

    private fun evictOlderThan(nowMs: Long) {
        while (samples.size > 1 && nowMs - samples.first().first > windowMs) {
            samples.removeFirst()
        }
    }

    companion object {
        const val WINDOW_MS = 2 * 60_000L
        // Физический минимум: второй тик опроса (10 сек). Две точки —
        // уже скорость; шум ранних цифр гасится весом по возрасту.
        const val MIN_SPAN_MS = 10_000L
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
            // Мусор драйвера не должен ломать оценку: только sane-значения.
            if (refined in MIN_SANE_UAH..MAX_SANE_UAH) {
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
        const val MIN_SANE_UAH = 500_000L
        const val MAX_SANE_UAH = 30_000_000L
    }
}
