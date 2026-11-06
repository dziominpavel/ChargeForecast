package com.example.chargeforecast.forecast

import kotlin.math.abs

// Режим счётчика заряда: грубый — шаг квантования сопоставим с 1%
// уровня (на MNA-LX9 шаг ровно ёмкость/100, счётчик — второй уровень
// с фазовым отставанием); точный — настоящий кулонометр.
// Грубый — безопасный режим по умолчанию: остаток по уровню не зависит
// от ёмкости и не может стать нулём из-за инверсии счётчика.
enum class CounterMode {
    COARSE,
    FINE
}

// Профиль квантования счётчика по живой сессии (design D2).
// Шаг — НОД положительных разностей счётчика: на грубом счётчике он
// равен 1% ёмкости, на точном — микроампер-часам. Отставание —
// медиана «уровень − счётчик×100/ёмкость»: грубый счётчик отстаёт
// от уровня на 1–3%, точный — не отстаёт. Пока профиль не подтверждён,
// действует грубый режим.
// Чистый класс — тестируется на JVM без Android.
class CounterProfile {

    // Смена режима или подтверждение профиля: было / стало / причина.
    // Сбрасывается на каждом observe; null — ничего не произошло.
    data class Change(val was: CounterMode, val now: CounterMode, val reason: String)

    private var previousCounterUah: Long? = null
    private var stepGcdUah: Long? = null
    private var observedChanges = 0
    private var firstObservedAtMs: Long? = null
    private val lagSamples = ArrayDeque<Float>()
    private var mode: CounterMode = CounterMode.COARSE
    private var confirmed = false

    @Volatile
    var lastChange: Change? = null
        private set

    val currentMode: CounterMode get() = mode
    val isConfirmed: Boolean get() = confirmed
    val stepUah: Long? get() = stepGcdUah
    val lagPct: Float? get() = medianLag()

    fun reset(initialMode: CounterMode = CounterMode.COARSE) {
        previousCounterUah = null
        stepGcdUah = null
        observedChanges = 0
        firstObservedAtMs = null
        lagSamples.clear()
        mode = initialMode
        confirmed = false
        lastChange = null
    }

    // Возвращает действующий режим. counterUah/levelPct — текущий замер,
    // capacityUah — оценка ёмкости для перевода счётчика в проценты.
    fun observe(
        counterUah: Long?,
        levelPct: Int,
        capacityUah: Long?,
        nowMs: Long
    ): CounterMode {
        lastChange = null
        if (counterUah == null || counterUah <= 0L) return mode
        if (capacityUah == null || capacityUah <= 0L) return mode
        if (levelPct <= 0 || levelPct > 100) return mode
        if (firstObservedAtMs == null) firstObservedAtMs = nowMs

        val lag = levelPct - counterUah.toFloat() * 100f / capacityUah
        if (lag.isFinite()) {
            lagSamples.addLast(lag)
            while (lagSamples.size > MAX_LAG_SAMPLES) lagSamples.removeFirst()
        }

        val previous = previousCounterUah
        if (previous != null && counterUah != previous) {
            val diff = abs(counterUah - previous)
            stepGcdUah = stepGcdUah?.let { gcd(it, diff) } ?: diff
            observedChanges++
        }
        previousCounterUah = counterUah

        val elapsedMs = nowMs - (firstObservedAtMs ?: nowMs)
        if (!confirmed &&
            (observedChanges >= MIN_CHANGES || (observedChanges >= 1 && elapsedMs >= CONFIRM_MS))
        ) {
            confirmed = true
            val observed = classify(capacityUah)
            val was = mode
            mode = observed
            lastChange = Change(was, observed, "confirmed")
        }
        return mode
    }

    private fun classify(capacityUah: Long): CounterMode {
        val step = stepGcdUah ?: return CounterMode.COARSE
        val ratio = step.toDouble() / capacityUah.toDouble()
        if (ratio >= COARSE_STEP_RATIO) return CounterMode.COARSE
        val lag = medianLag() ?: return CounterMode.COARSE
        return if (abs(lag) < FINE_LAG_PCT) CounterMode.FINE else CounterMode.COARSE
    }

    private fun medianLag(): Float? {
        if (lagSamples.isEmpty()) return null
        val sorted = lagSamples.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    companion object {
        // Шаг счётчика не меньше 0.5% ёмкости — грубый счётчик.
        const val COARSE_STEP_RATIO = 0.005
        // Медиана отставания меньше 1% — точный счётчик не отстаёт.
        const val FINE_LAG_PCT = 1.0f
        // Подтверждение: пять изменений счётчика или две минуты наблюдений.
        const val MIN_CHANGES = 5
        const val CONFIRM_MS = 120_000L
        const val MAX_LAG_SAMPLES = 600
    }
}
