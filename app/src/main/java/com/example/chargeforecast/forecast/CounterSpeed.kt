package com.example.chargeforecast.forecast

import kotlin.math.abs

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

// Живое уточнение ёмкости — один источник истины для остатка. Три
// эшелона защиты (design D2 change forecast-integrity):
//   1. коридор паспортной ёмкости ±15%, когда она известна;
//   2. инвариант «заряд в батарее меньше ёмкости» с запасом 2%;
//   3. шаг уточнения не больше 12% текущей оценки.
// Три отклонённых подряд шага — сигнал, что оценка уехала: сброс к
// счётчик × 100 / уровень. Изменения и сбросы пишутся в журнал событием
// с причиной (было / стало / почему). Чистый класс — тестируем на JVM.
class CapacityTracker {

    // Последнее изменение оценки — для журнала событий: было / стало /
    // причина. Сбрасывается на каждом update: null — оценки не менялись
    // и уточнения не было.
    data class Change(val was: Long?, val now: Long, val reason: String)

    private var anchorCounterUah: Long? = null
    private var anchorLevel: Int? = null
    private var estimateUah: Long? = null
    // Отклонённые уточнения подряд: три — сброс оценки к текущему замеру.
    private var rejectedSteps = 0

    @Volatile
    var lastChange: Change? = null
        private set

    // designCapacityUah — паспортная ёмкость устройства (nullable: на
    // Huawei P20 Pro sysfs закрыт SELinux). Когда она известна, оценка
    // живёт в коридоре ±15% от неё.
    fun update(
        counterUah: Long?,
        levelPct: Int,
        designCapacityUah: Long? = null
    ): Long? {
        lastChange = null
        if (counterUah == null || counterUah <= 0) return estimateUah
        if (levelPct <= 0 || levelPct > 100) return estimateUah
        // На 100% уточнять нечего: счётчик равен заряду, а остаток не
        // считается — оценка остаётся последней валидной.
        if (levelPct == 100) return estimateUah
        // Счётчик за пределами мыслимой ёмкости — мусор драйвера: замер
        // не должен ломать оценку (и поднимать её до мусорного заряда).
        if (counterUah > MAX_SANE_UAH) return estimateUah
        val design = designCapacityUah?.takeIf { it in MIN_SANE_UAH..MAX_SANE_UAH }
        val current = estimateUah
        if (current == null) {
            val initial = sanitize(
                InstantSpeedEstimator.estimateCapacityUah(counterUah, levelPct),
                counterUah, design
            ) ?: return null
            estimateUah = initial
            anchorCounterUah = counterUah
            anchorLevel = levelPct
            rejectedSteps = 0
            lastChange = Change(null, initial, "init")
            return initial
        }
        // Инверсия: заряд в батарее догнал оценку — вход недостоверен.
        // Оценка восстанавливается из текущего замера (спека
        // «Достоверность входа прогноза»), а не остаётся испорченной.
        if (counterUah >= current) {
            return restore(counterUah, levelPct, design, "reset-inverted", current)
        }
        val anchorCounter = anchorCounterUah
        val anchorLevel = anchorLevel
        if (anchorCounter == null || anchorLevel == null) {
            anchorCounterUah = counterUah
            this.anchorLevel = levelPct
            return current
        }
        val deltaLevel = levelPct - anchorLevel
        val deltaCounter = counterUah - anchorCounter
        if (deltaLevel < REFINEMENT_MIN_DELTA_LEVEL || deltaCounter <= 0L) return current
        val candidate = deltaCounter * 100L / deltaLevel
        val rejection = rejectionReason(candidate, counterUah, current, design)
        if (rejection != null) {
            rejectedSteps++
            // Оценка не менялась, но отказ — тоже событие: по нему видно,
            // откуда взялся сброс через три шага.
            lastChange = Change(current, current, rejection)
            if (rejectedSteps >= MAX_REJECTED_STEPS) {
                return restore(counterUah, levelPct, design, "reset-rejected", current)
            }
            return current
        }
        estimateUah = candidate
        anchorCounterUah = counterUah
        this.anchorLevel = levelPct
        rejectedSteps = 0
        lastChange = Change(current, candidate, "refined")
        return candidate
    }

    fun reset() {
        anchorCounterUah = null
        anchorLevel = null
        estimateUah = null
        rejectedSteps = 0
        lastChange = null
    }

    // Восстановление оценки из текущих уровня и счётчика. Событие
    // пишется только при реальной смене значения: на полном заряде
    // счётчик равен ёмкости и сброс повторялся бы каждый тик.
    private fun restore(
        counterUah: Long,
        levelPct: Int,
        design: Long?,
        reason: String,
        was: Long
    ): Long {
        val restored = sanitize(
            InstantSpeedEstimator.estimateCapacityUah(counterUah, levelPct),
            counterUah, design
        ) ?: return was
        estimateUah = restored
        anchorCounterUah = counterUah
        anchorLevel = levelPct
        rejectedSteps = 0
        if (restored != was) lastChange = Change(was, restored, reason)
        return restored
    }

    // Почему уточнение отклонено. Инвариант проверяется первым: именно он
    // и есть источник «остатка 0 с».
    private fun rejectionReason(
        candidate: Long,
        counterUah: Long,
        current: Long,
        design: Long?
    ): String? = when {
        candidate < minCapacityFor(counterUah) -> "rejected-invariant"
        candidate !in MIN_SANE_UAH..MAX_SANE_UAH -> "rejected-sane"
        design != null &&
            (candidate * 100L < design * (100L - DESIGN_CORRIDOR_PERCENT) ||
                candidate * 100L > design * (100L + DESIGN_CORRIDOR_PERCENT)) ->
            "rejected-passport"
        abs(candidate - current) * 100L > current * MAX_STEP_PERCENT -> "rejected-step"
        else -> null
    }

    // Оценка, проходящая эшелоны 1 и 2: коридор паспортной ёмкости и
    // «заряд в батарее меньше ёмкости». Коридор прижимает значение,
    // инвариант важнее и при конфликте поднимает оценку выше него.
    private fun sanitize(candidate: Long?, counterUah: Long, design: Long?): Long? {
        if (candidate == null || candidate <= 0L) return null
        var value = candidate.coerceIn(MIN_SANE_UAH, MAX_SANE_UAH)
        if (design != null) {
            val low = design * (100L - DESIGN_CORRIDOR_PERCENT) / 100L
            val high = design * (100L + DESIGN_CORRIDOR_PERCENT) / 100L
            value = value.coerceIn(low, high)
        }
        val minimal = minCapacityFor(counterUah)
        if (value < minimal) value = minimal
        return value
    }

    // Эшелон 2: ёмкость выше заряда в батарее с запасом 2%.
    private fun minCapacityFor(counterUah: Long): Long =
        (counterUah.toDouble() * COUNTER_MARGIN).toLong()
            .coerceIn(MIN_SANE_UAH, MAX_SANE_UAH)

    companion object {
        const val REFINEMENT_MIN_DELTA_LEVEL = 2
        // Шаг уточнения: не больше 12% текущей оценки за одно уточнение.
        const val MAX_STEP_PERCENT = 12L
        const val MAX_REJECTED_STEPS = 3
        // Коридор паспортной ёмкости ±15%.
        const val DESIGN_CORRIDOR_PERCENT = 15L
        // Запас инварианта «счётчик меньше ёмкости».
        const val COUNTER_MARGIN = 1.02
        const val MIN_SANE_UAH = 500_000L
        const val MAX_SANE_UAH = 30_000_000L
    }
}
