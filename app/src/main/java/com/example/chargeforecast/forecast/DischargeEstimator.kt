package com.example.chargeforecast.forecast

import com.example.chargeforecast.battery.BatterySnapshot
import kotlin.math.pow

// Накопитель расхода — состояние, переживающее убийство процесса и ребут.
data class DischargeState(
    // Сглаженный средний ток разряда (мкА), null — опоры ещё нет.
    val smoothedCurrentUa: Float?,
    // Монотонная метка последнего замера (elapsedRealtime).
    val lastSampleMs: Long?,
    // Счётчик заряда на момент последнего замера — якорь интервала.
    val lastCounterUah: Long?,
    // Сколько времени фактически накоплено по пригодным интервалам.
    val trackedMs: Long
)

// Оценка оставшейся работы телефона. Приблизительная по природе:
// описывает продолжение недавнего расхода, а не обещание — расход
// зависит от будущего использования (design Non-Goals).
data class DischargeEstimate(
    // Секунд до разряда, null — показывать нельзя.
    val remainingSec: Long?,
    // true — данных недостаточно, показывать состояние сбора.
    val collecting: Boolean
) {
    companion object {
        // Опоры нет: накопитель пуст или мусорный — честный сбор данных.
        val COLLECTING = DischargeEstimate(remainingSec = null, collecting = true)
        // Зарядка: режим разряда неактуален, виджет показывает отсчёт.
        val NONE = DischargeEstimate(remainingSec = null, collecting = false)
    }
}

// Чистый оценщик разряда (design D9): накапливает расход ТОЛЬКО на
// интервалах !isPlugged и сглаживает средний ток EWMA с полуокном
// порядка суток — бурный вечер сдвигает оценку заметно, неделя тишины
// постепенно. Зарядка накопитель не трогает, но переставляет якоря:
// интервал после отключения начинается от него, а не от последнего
// замера во время зарядки. Android-зависимостей нет — тестируется на JVM.
class DischargeEstimator(
    private val halfLifeMs: Long = HALF_LIFE_MS,
    private val onPersist: (DischargeState) -> Unit = {}
) {

    private var smoothedCurrentUa: Float? = null
    private var lastSampleMs: Long? = null
    private var lastCounterUah: Long? = null
    private var trackedMs: Long = 0L

    // Восстановление после убийства процесса или перезагрузки. Счётчик
    // восстанавливаем тоже: он физическая величина и сравним между
    // запусками процесса; сравнение через ребут отсекается проверкой
    // dtMs <= 0 в update (elapsedRealtime обнуляется).
    fun restore(state: DischargeState?) {
        if (state == null) return
        smoothedCurrentUa = state.smoothedCurrentUa?.takeIf { it > 0f }
        lastSampleMs = state.lastSampleMs
        lastCounterUah = state.lastCounterUah?.takeIf { it > 0L }
        trackedMs = state.trackedMs.coerceAtLeast(0L)
    }

    fun state(): DischargeState =
        DischargeState(smoothedCurrentUa, lastSampleMs, lastCounterUah, trackedMs)

    fun update(snapshot: BatterySnapshot, nowMs: Long) {
        if (snapshot.isPlugged) {
            lastSampleMs = nowMs
            lastCounterUah = snapshot.chargeCounterUah
            return
        }
        val prevMs = lastSampleMs
        val prevUah = lastCounterUah
        lastSampleMs = nowMs
        lastCounterUah = snapshot.chargeCounterUah
        if (prevMs == null) return
        val dtMs = nowMs - prevMs
        // elapsedRealtime обнуляется при перезагрузке: интервал, начатый
        // до ребута, нечитаем — якорим заново без накопления.
        if (dtMs <= 0L) return
        val intervalUa = intervalCurrentUa(prevUah, snapshot, dtMs) ?: return
        trackedMs += dtMs
        val prev = smoothedCurrentUa
        smoothedCurrentUa = if (prev == null) {
            intervalUa
        } else {
            val alpha = alphaFor(dtMs)
            prev * (1f - alpha) + intervalUa * alpha
        }
        onPersist(state())
    }

    // Часы из остатка мкА·ч и среднего тока мкА. Без устойчивой опоры —
    // сбор данных, ноль часов и отрицательное время не выдаются.
    fun estimate(remainingUah: Long?): DischargeEstimate {
        val avgUa = smoothedCurrentUa ?: return DischargeEstimate.COLLECTING
        // Ниже порога — мусор датчика или застой, а не расход.
        if (avgUa < MIN_CREDIBLE_DISCHARGE_UA) return DischargeEstimate.COLLECTING
        if (trackedMs < MIN_TRACKED_MS) return DischargeEstimate.COLLECTING
        if (remainingUah == null || remainingUah <= 0L) return DischargeEstimate.COLLECTING
        val seconds = (remainingUah.toDouble() / avgUa * SECONDS_PER_HOUR).toLong()
        return DischargeEstimate(seconds.coerceAtLeast(0L), collecting = false)
    }

    // Средний ток интервала: счётчик заряда предпочтителен (непрерывный,
    // обход квантования уровня 1%), без него — модуль тока датчика.
    // Нулевой расход (счётчик не убывал) — честный ноль: выкидывать такой
    // интервал значило бы завышать оценку, считая только активные периоды.
    private fun intervalCurrentUa(
        prevUah: Long?,
        snapshot: BatterySnapshot,
        dtMs: Long
    ): Float? {
        val counter = snapshot.chargeCounterUah
        if (prevUah != null && counter != null) {
            val deltaUah = prevUah - counter
            // Счётчик вырос при разрядке — аномалия драйвера, интервал нечитаем.
            if (deltaUah < 0L) return null
            return (deltaUah.toDouble() * MILLIS_PER_HOUR / dtMs).toFloat()
        }
        return snapshot.dischargeCurrentUa?.takeIf { it > 0L }?.toFloat()
    }

    // Вес интервала в EWMA: доля устаревшего значения 0.5^(dt/полуокно).
    private fun alphaFor(dtMs: Long): Float =
        (1.0 - 0.5.pow(dtMs.toDouble() / halfLifeMs)).toFloat().coerceIn(0f, 1f)

    companion object {
        // Полуокно усреднения — порядка суток: единственный управляющий
        // параметр оценки, калибруется на устройстве автора.
        const val HALF_LIFE_MS = 24 * 60 * 60_000L
        // Ниже 1 мА — мусор датчика или застой, а не измеренный расход.
        const val MIN_CREDIBLE_DISCHARGE_UA = 1_000f
        // Опора из одного шумного замера не строится.
        const val MIN_TRACKED_MS = 15 * 60_000L

        private const val MILLIS_PER_HOUR = 3_600_000L
        private const val SECONDS_PER_HOUR = 3_600L
    }
}
