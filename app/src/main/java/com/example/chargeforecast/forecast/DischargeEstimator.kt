package com.example.chargeforecast.forecast

import com.example.chargeforecast.battery.BatterySnapshot
import kotlin.math.max

// Один наблюдённый интервал разряда внутри окна. Интервал — это
// разность счётчика заряда между двумя замерами и время между ними.
data class DischargeSample(
    // Монотонная метка конца интервала (elapsedRealtime).
    val atMs: Long,
    // Сколько времени длился интервал (мс).
    val durationMs: Long,
    // Сколько заряда ушло за интервал (мкА·ч).
    val dischargedUah: Long
)

// Итог наблюдений внутри окна — то, что показывается пользователю
// рядом со временем и пишется в журнал.
data class DischargeSummary(
    // Наблюдаемого времени внутри окна (мс).
    val trackedMs: Long,
    // Сумма разряженного заряда внутри окна (мкА·ч).
    val dischargedUah: Long,
    // Средний ток за окно (мкА), null — наблюдений нет.
    val averageCurrentUa: Float?
)

// Состояние, переживающее убийство процесса и ребут.
data class DischargeState(
    // Интервалы внутри окна: кольцо, старые корзины обрезаются.
    val samples: List<DischargeSample>,
    // Монотонная метка последнего замера (elapsedRealtime).
    val lastSampleMs: Long?,
    // Счётчик заряда на момент последнего замера — якорь интервала.
    val lastCounterUah: Long?
)

// Почему замер не стал интервалом. Нужно журналу: без причины
// «нет счётчика» и «драйвер соврал» неразличимы, а чинятся по-разному.
enum class DischargeObservationReason {
    // Устройство заряжается — интервал не накапливается.
    CHARGING,
    // Ещё нет якоря: первый замер после старта или восстановления.
    NO_ANCHOR,
    // Метка не выросла (ребут обнулил elapsedRealtime) — интервал нечитаем.
    BAD_INTERVAL,
    // Счётчик заряда недоступен хотя бы на одном конце интервала.
    NO_COUNTER,
    // Счётчик вырос при разрядке — аномалия драйвера, интервал нечитаем.
    COUNTER_GREW,
    // Интервал накоплен в окно.
    INTERVAL
}

// Одна строка журнала наблюдений: что видел датчик и что вышло в окне.
data class DischargeObservation(
    // Человеческое время (wall clock) — для чтения журнала.
    val wallClockMs: Long,
    // Монотонная метка замера (elapsedRealtime).
    val monotonicMs: Long,
    val levelPct: Int,
    // Счётчик остатка на замере (мкА·ч), null — недоступен.
    val counterUah: Long?,
    // Длина интервала (мс), null — интервала нет.
    val intervalMs: Long?,
    // Сколько заряда ушло за интервал (мкА·ч), null — интервал не накоплен.
    val intervalUah: Long?,
    // Наблюдаемого времени в окне на момент замера (мс).
    val trackedMs: Long,
    // Средний ток за окно (мкА), null — данных мало.
    val averageCurrentUa: Float?,
    val reason: DischargeObservationReason
)

// Оценка оставшейся работы телефона. Приблизительная по природе:
// описывает продолжение среднего расхода за окно, а не обещание —
// расход зависит от будущего использования (design Non-Goals).
data class DischargeEstimate(
    // Секунд до разряда, null — показывать нельзя.
    val remainingSec: Long?,
    // true — данных недостаточно, показывать состояние сбора.
    val collecting: Boolean,
    // Величины, из которых получено время: средний ток за окно и
    // сколько времени внутри окна наблюдено. Без них число нечем
    // проверить (спека discharge-estimate, «Оценка объяснима»).
    val averageCurrentUa: Float? = null,
    val trackedMs: Long = 0L,
    val windowMs: Long = DischargeEstimator.WINDOW_MS
) {
    companion object {
        // Опоры нет: окно пустое или мусорное — честный сбор данных.
        val COLLECTING = DischargeEstimate(remainingSec = null, collecting = true)
        // Зарядка: режим разряда неактуален, виджет показывает отсчёт.
        val NONE = DischargeEstimate(remainingSec = null, collecting = false)
    }
}

// Оценщик оставшейся работы (design D1–D3): окно скользящих 24 часов,
// среднее = ΣdU ÷ Σdt. Вклад интервала определяется его длительностью,
// поэтому короткий бурный период (включение экрана, навигация) не может
// перевернуть оценку — он занимает малую долю окна. Накапливается
// расход ТОЛЬКО на интервалах !isPlugged и только по дельте счётчика
// заряда: мгновенный ток датчика в среднее не попадает, ведь оценка
// и так требует счётчика для остатка — датчик добавлял бы выбросы.
// Android-зависимостей нет — тестируется на JVM.
class DischargeEstimator(
    private val windowMs: Long = WINDOW_MS,
    private val onPersist: (DischargeState) -> Unit = {},
    // Журнал наблюдений: одна строка на каждый замер, включая
    // пропущенные с причиной (design D5).
    private val onObservation: (DischargeObservation) -> Unit = {},
    private val wallClockMs: () -> Long = { System.currentTimeMillis() }
) {

    private val samples = ArrayDeque<DischargeSample>()
    private var lastSampleMs: Long? = null
    private var lastCounterUah: Long? = null

    // Восстановление после убийства процесса или перезагрузки. Счётчик
    // восстанавливаем тоже: он физическая величина и сравним между
    // запусками процесса; сравнение через ребут отсекается проверкой
    // dtMs <= 0 в update (elapsedRealtime обнуляется). Обрезка окна
    // выполняется по свежему времени в summary()/estimate().
    fun restore(state: DischargeState?) {
        if (state == null) return
        samples.clear()
        state.samples
            .filter { it.durationMs > 0L && it.dischargedUah >= 0L }
            .sortedBy { it.atMs }
            .forEach(::addSample)
        lastSampleMs = state.lastSampleMs
        lastCounterUah = state.lastCounterUah?.takeIf { it > 0L }
    }

    fun state(): DischargeState =
        DischargeState(samples.toList(), lastSampleMs, lastCounterUah)

    fun update(snapshot: BatterySnapshot, nowMs: Long) {
        if (snapshot.isPlugged) {
            // Зарядка накопитель не трогает, но переставляет якоря:
            // первый разрядный интервал начнётся от отключения.
            lastSampleMs = nowMs
            lastCounterUah = snapshot.chargeCounterUah
            observe(snapshot, nowMs, null, null, DischargeObservationReason.CHARGING)
            return
        }
        val prevMs = lastSampleMs
        val prevUah = lastCounterUah
        lastSampleMs = nowMs
        lastCounterUah = snapshot.chargeCounterUah
        if (prevMs == null) {
            observe(snapshot, nowMs, null, null, DischargeObservationReason.NO_ANCHOR)
            return
        }
        val dtMs = nowMs - prevMs
        // elapsedRealtime обнуляется при перезагрузке: интервал, начатый
        // до ребута, нечитаем — якорим заново без накопления.
        if (dtMs <= 0L) {
            observe(snapshot, nowMs, null, null, DischargeObservationReason.BAD_INTERVAL)
            return
        }
        val counter = snapshot.chargeCounterUah
        if (prevUah == null || counter == null) {
            // Без счётчика интервал не накапливается: мгновенный ток в
            // среднее не подмешивается (design D3).
            observe(snapshot, nowMs, dtMs, null, DischargeObservationReason.NO_COUNTER)
            return
        }
        val deltaUah = prevUah - counter
        // Счётчик вырос при разрядке — аномалия драйвера, интервал нечитаем.
        if (deltaUah < 0L) {
            observe(snapshot, nowMs, dtMs, null, DischargeObservationReason.COUNTER_GREW)
            return
        }
        addSample(DischargeSample(atMs = nowMs, durationMs = dtMs, dischargedUah = deltaUah))
        prune(nowMs)
        onPersist(state())
        observe(snapshot, nowMs, dtMs, deltaUah, DischargeObservationReason.INTERVAL)
    }

    // Итог окна на заданный момент: время, энергия и средний ток.
    fun summary(nowMs: Long): DischargeSummary {
        prune(nowMs)
        var tracked = 0L
        var energy = 0L
        for (sample in samples) {
            tracked += sample.durationMs
            energy += sample.dischargedUah
        }
        val average = if (tracked > 0L) {
            (energy.toDouble() * MILLIS_PER_HOUR / tracked).toFloat()
        } else {
            null
        }
        return DischargeSummary(tracked, energy, average)
    }

    // Часы из остатка мкА·ч и среднего тока мкА за окно. Без устойчивой
    // опоры — сбор данных, ноль часов и отрицательное время не выдаются.
    fun estimate(remainingUah: Long?, nowMs: Long): DischargeEstimate {
        val summary = summary(nowMs)
        val average = summary.averageCurrentUa
        fun collecting() = DischargeEstimate(
            remainingSec = null,
            collecting = true,
            averageCurrentUa = average,
            trackedMs = summary.trackedMs,
            windowMs = windowMs
        )
        // Ниже порога — мусор датчика или застой, а не расход.
        if (average == null || average < MIN_CREDIBLE_DISCHARGE_UA) return collecting()
        // Опора строится только из окна: короткий участок не даёт числа.
        if (summary.trackedMs < MIN_TRACKED_IN_WINDOW_MS) return collecting()
        if (remainingUah == null || remainingUah <= 0L) return collecting()
        val seconds = (remainingUah.toDouble() / average * SECONDS_PER_HOUR).toLong()
        return DischargeEstimate(
            remainingSec = seconds.coerceAtLeast(0L),
            collecting = false,
            averageCurrentUa = average,
            trackedMs = summary.trackedMs,
            windowMs = windowMs
        )
    }

    // Строки журнала для пропущенных и накопленных замеров.
    private fun observe(
        snapshot: BatterySnapshot,
        nowMs: Long,
        intervalMs: Long?,
        intervalUah: Long?,
        reason: DischargeObservationReason
    ) {
        // Сводка окна на момент замера — пропущенные интервалы тоже
        // попадают в журнал с актуальными цифрами, иначе причина
        // пропуска не различима с состоянием окна.
        val summary = summary(nowMs)
        onObservation(
            DischargeObservation(
                wallClockMs = wallClockMs(),
                monotonicMs = nowMs,
                levelPct = snapshot.levelPct,
                counterUah = snapshot.chargeCounterUah,
                intervalMs = intervalMs,
                intervalUah = intervalUah,
                trackedMs = summary.trackedMs,
                averageCurrentUa = summary.averageCurrentUa,
                reason = reason
            )
        )
    }

    // Интервал складывается в 5-минутную корзину: спайк длиной минуту
    // весит минуту, а не сутки, и при этом кольцо остаётся коротким.
    private fun addSample(sample: DischargeSample) {
        val bucket = bucketOf(sample.atMs)
        val tail = samples.lastOrNull()
        if (tail != null && bucketOf(tail.atMs) == bucket) {
            samples.removeLast()
            samples.addLast(
                DischargeSample(
                    atMs = max(tail.atMs, sample.atMs),
                    durationMs = tail.durationMs + sample.durationMs,
                    dischargedUah = tail.dischargedUah + sample.dischargedUah
                )
            )
            return
        }
        samples.addLast(sample)
    }

    // Обрезка окна: корзины, конец которых старше суток, выкидываются.
    // Жёсткий лимит страхует от аномальных данных.
    private fun prune(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (samples.isNotEmpty() && samples.first().atMs < cutoff) {
            samples.removeFirst()
        }
        while (samples.size > MAX_BUCKETS) {
            samples.removeFirst()
        }
    }

    private fun bucketOf(atMs: Long): Long = atMs / BUCKET_MS

    companion object {
        // Скользящее окно усреднения — сутки: единственный управляющий
        // параметр оценки (спека discharge-estimate).
        const val WINDOW_MS = 24 * 60 * 60_000L
        // Внутри окна интервалы складываются в корзины по 5 минут.
        const val BUCKET_MS = 5 * 60_000L
        // Жёсткий предел корзин в окне: 288 * 5 мин = 24 ч.
        const val MAX_BUCKETS = 288
        // Ниже 1 мА — мусор датчика или застой, а не измеренный расход.
        const val MIN_CREDIBLE_DISCHARGE_UA = 1_000f
        // Порог входа: меньше наблюдённого времени внутри окна — сбор данных.
        const val MIN_TRACKED_IN_WINDOW_MS = 2 * 60 * 60_000L

        private const val MILLIS_PER_HOUR = 3_600_000L
        private const val SECONDS_PER_HOUR = 3_600L
    }
}
