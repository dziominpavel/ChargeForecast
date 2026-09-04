package com.example.chargeforecast.forecast

// Одна точка уровня заряда.
data class LevelSample(val timeMs: Long, val levelPct: Int)

// Скорость зарядки (%/мин) по скользящему окну.
// Последние минуты весят больше, выбросы отбрасываются.
// Чистый класс без Android-зависимостей — полностью тестируем на JVM.
class ChargeSpeedCalculator(
    private val windowMs: Long = SPEED_WINDOW_MS,
    private val minSpanMs: Long = MIN_SPAN_MS
) {
    private val samples = ArrayDeque<LevelSample>()

    fun addSample(timeMs: Long, levelPct: Int) {
        val last = samples.lastOrNull()
        // Дубли уровня подряд не несут информации — храним только свежий.
        if (last != null && last.levelPct == levelPct) {
            samples.removeLast()
        }
        samples.addLast(LevelSample(timeMs, levelPct))
        evictOlderThan(timeMs)
    }

    fun reset() {
        samples.clear()
    }

    fun sampleCount(): Int = samples.size

    // Null — данных мало или заряд не идёт (уровень стоит/падает).
    fun speedPctPerMin(nowMs: Long): Float? {
        evictOlderThan(nowMs)
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        val spanMin = (last.timeMs - first.timeMs) / 60_000f
        if (spanMin * 60_000f < minSpanMs) return null
        if (last.levelPct - first.levelPct < 1) return null

        // Пошаговые скорости, отрицательные шаги (шум/разряд) отбрасываем.
        val rates = samples.zipWithNext()
            .mapNotNull { (a, b) ->
                val dtMin = (b.timeMs - a.timeMs) / 60_000f
                if (dtMin <= 0f) return@mapNotNull null
                val rate = (b.levelPct - a.levelPct) / dtMin
                rate.takeIf { it > 0f }
            }
        if (rates.isEmpty()) return null

        // Выбросы: быстрее тройной медианы — артефакт грубого шага 1%.
        val median = rates.sorted().let {
            if (it.size % 2 == 1) it[it.size / 2]
            else (it[it.size / 2 - 1] + it[it.size / 2]) / 2f
        }
        val sane = rates.filter { it <= median * OUTLIER_FACTOR }
        if (sane.isEmpty()) return null

        // Взвешенное среднее: свежие шаги важнее.
        var weighted = 0f
        var weights = 0f
        sane.forEachIndexed { index, rate ->
            val w = (index + 1).toFloat()
            weighted += rate * w
            weights += w
        }
        return (weighted / weights).takeIf { it > 0f }
    }

    private fun evictOlderThan(nowMs: Long) {
        while (samples.size > 1 && nowMs - samples.first().timeMs > windowMs) {
            samples.removeFirst()
        }
    }

    companion object {
        // Окно и калибровки spike-замера — см. задачу 2.3 в tasks.md.
        const val SPEED_WINDOW_MS = 10 * 60_000L
        // Физический минимум: второй тик с новым уровнем (10 сек).
        // Ранний шум не страшен: вес замера растёт с возрастом сессии.
        const val MIN_SPAN_MS = 10_000L
        const val OUTLIER_FACTOR = 3f
    }
}
