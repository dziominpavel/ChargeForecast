package com.example.chargeforecast.forecast

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToLong

// Фильтр показа: EMA сырой остатка (tau=10 с) и якорный отсчёт.
// Показанный остаток — «якорь минус прошедшее время»: между коррекциями
// модели он убывает ровно на секунду за секунду, как настоящие часы,
// даже если сырая оценка застыла (квантование уровня, замерший счётчик).
// Коррекция якоря — скольжение, а не прыжок: расхождение с моделью
// разъезжается шагами `min(расхождение, max(SLEW_MIN_STEP_SEC,
// расхождение × SLEW_FRACTION))` в обе стороны — вниз так же плавно,
// как вверх (спека-дельта: прежнее «вниз свободно» заменено скольжением).
// Два пути пересмотра вверх (гейт направления — якорь вверх двигается
// только через подтверждённый пересмотр):
// 1. Рывок: рост более 25% принимается, если держится 3 тика подряд.
// 2. Тренд (D6): медленная EMA (tau=60 с) выросла относительно значения
//    минуту назад больше чем на TREND_NOISE_FLOOR_SEC и условие держится
//    TREND_TICKS тика подряд → кандидат принимается каждым тиком.
// Пока путь не открыт, кандидат выше показанного якорь не двигает
// («без ложных подъёмов»): показанный продолжает свой отсчёт по времени.
// Показ привязан к секундной сетке вызывающей стороны (ChargeSessionMonitor):
// каждый вызов update — один тик отсчёта.
// Чистый класс — тестируем на JVM.
class DisplayFilter(
    private val emaTauMs: Long = EMA_TAU_MS
) {
    private var emaSec: Float? = null
    // Медленная EMA сырого остатка + окно значений для разности 60 с.
    private var slowEmaSec: Float? = null
    private val trendHistory = ArrayDeque<Pair<Long, Float>>()
    private var trendTicks = 0
    private var lastUpdateMs: Long? = null
    // Якорь показа: значение остатка в секундах и метка, от которой оно
    // отсчитывается по времени. Показ = якорь − (now − anchorAtMs).
    private var anchorSec: Float? = null
    private var anchorAtMs: Long? = null
    // Отслеживание ревизий модели для догоняющего держания сошедшейся
    // модели (залипание показа ниже модели — downSlides/ratchet):
    // максимум кандидата с посева якоря, счётчик подряд одинаковых
    // кандидатов, защёлка подтверждённого пересмотра вверх.
    private var maxCandidate: Float? = null
    private var prevCandidate: Float? = null
    private var stableTicks = 0
    private var reviewLatched = false
    private var reviewTicks = 0

    // rawRemainingSec — сырая оценка остатка в секундах.
    // Возвращает показанное значение в секундах.
    fun update(rawRemainingSec: Float?, nowMs: Long): Long? {
        val dtMs = lastUpdateMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        // Метка времени двигается каждым тиком (иначе dtMs всегда 0 и
        // обе EMA не сглаживают — raw проходит в показ напрямую).
        lastUpdateMs = nowMs
        // Полная зарядка: ноль — мгновенно, EMA не должна «дотухать»
        // десятки секунд после isFull (остаток 0 — терминальный факт).
        if (rawRemainingSec != null && rawRemainingSec <= 0f) {
            emaSec = 0f
            slowEmaSec = 0f
            trendHistory.clear()
            trendHistory.addLast(nowMs to 0f)
            trendTicks = 0
            setAnchor(0f, nowMs)
            return 0L
        }
        emaSec = if (rawRemainingSec == null) {
            // Нет данных — держим EMA: показанный продолжает убывать.
            emaSec
        } else {
            val prev = emaSec
            when {
                prev == null || dtMs <= 0L -> rawRemainingSec
                else -> {
                    val alpha = 1f - exp(-dtMs.toFloat() / emaTauMs)
                    prev + alpha * (rawRemainingSec - prev)
                }
            }
        }
        slowEmaSec = if (rawRemainingSec == null) {
            slowEmaSec
        } else {
            val prev = slowEmaSec
            when {
                prev == null || dtMs <= 0L -> rawRemainingSec
                else -> {
                    val alpha = 1f - exp(-dtMs.toFloat() / SLOW_EMA_TAU_MS)
                    prev + alpha * (rawRemainingSec - prev)
                }
            }
        }
        // Тренд-путь: разность медленной EMA с окном TREND_WINDOW_MS.
        val slow = slowEmaSec
        if (slow != null) {
            trendHistory.addLast(nowMs to slow)
            val cutoff = nowMs - TREND_WINDOW_MS
            var refSec: Float? = null
            for ((ts, v) in trendHistory) {
                if (ts <= cutoff) refSec = v else break
            }
            // Держим только «самую свежую точку не новее cutoff» + более
            // свежие — старые для разности больше нужны.
            while (trendHistory.size > 1 && trendHistory[1].first <= cutoff) {
                trendHistory.removeFirst()
            }
            val rising = refSec != null && slow - refSec > TREND_NOISE_FLOOR_SEC
            trendTicks = if (rising) trendTicks + 1 else 0
        } else {
            trendTicks = 0
        }
        val shown = shownNow(nowMs)
        if (shown == null || shown <= 0f) {
            // Новая точка отсчёта — отслеживание ревизий начинается
            // заново: просадки и стабилизации прошлой эпохи якорю
            // больше не указ.
            // Первый показ или исчерпанный якорь при живой модели —
            // новая точка отсчёта от текущей EMA.
            maxCandidate = null
            prevCandidate = null
            stableTicks = 0
            reviewLatched = false
            val seed = emaSec ?: return null
            setAnchor(seed, nowMs)
            return out(nowMs)
        }
        if (rawRemainingSec == null) {
            // Данных нет — якорь не трогаем, отсчёт продолжает убывать.
            // Пропуск данных обнуляет счётчик стабильности (неизвестное
            // не считается стабилизацией), максимум для просадок живёт дальше.
            stableTicks = 0
            return out(nowMs)
        }
        val ema = emaSec ?: return null
        val candidate = ema.roundToLong().coerceAtLeast(0L).toFloat()
        // Ревизия модели: максимум кандидата с посева якоря (просадка
        // считается от него) и счётчик подряд одинаковых кандидатов
        // (стабилизация после пересмотра).
        maxCandidate = maxOf(maxCandidate ?: candidate, candidate)
        if (candidate == prevCandidate) {
            stableTicks++
        } else {
            stableTicks = 0
            prevCandidate = candidate
        }
        val trendOpen = trendTicks >= TREND_TICKS
        when {
            // Вниз — скольжением, всегда. Крупное падение (граница корзин,
            // обвал модели) обнуляет окно тренда: иначе разность
            // «60 с назад» отравлена высокими значениями, путь тренда
            // не откроется при росте, а рывок примет сразу больше +25% —
            // пила вернётся.
            candidate <= shown -> {
                reviewTicks = 0
                // Защёлка снимается только уходом модели строго вниз:
                // равенство на тиканье — ещё не опровержение ревизии.
                if (candidate < shown) {
                    reviewLatched = false
                }
                if (shown - candidate > TREND_RESET_DROP_SEC) {
                    resetTrendWindow(rawRemainingSec, nowMs)
                }
                glide(candidate, shown, nowMs)
            }
            // Тренд подтверждён — якорь плавно идёт за кандидатом.
            trendOpen -> glide(candidate, shown, nowMs)
            // Вверх рывком — только устойчивый пересмотр: кандидат
            // держится выше порога +25% три тика подряд (одиночные
            // всплески шума сбрасывают счётчик затуханием). До
            // подтверждения якорь не трогается — отсчёт продолжается.
            // Подтверждённый пересмотр вверх (+25% три тика подряд)
            // защёлкивается: дальше якорь догоняет модель каждый тик,
            // пока модель не уйдёт обратно вниз (там защёлка снимается).
            candidate > shown * (1f + REVIEW_FRACTION) || reviewLatched -> {
                reviewTicks++
                if (reviewTicks >= REVIEW_TICKS) {
                    reviewLatched = true
                    glide(candidate, shown, nowMs)
                }
            }
            else -> {
                reviewTicks = 0
                // Догоняющее держание сошедшейся модели: кандидат крупно
                // просел вниз (>DROP_ARM_FRACTION от максимума с посева)
                // и застыл (STABLE_TICKS подряд одинаковых), а показ залип
                // ниже него на тиканье — ведём якорь к модели скольжением
                // (шаги ограничены в glide), а не даём тикать мимо модели.
                // На ровном отсчёте (просадок не было) и на шуме (нет
                // стабильности) правило не срабатывает — тиканье сохраняется.
                val maxC = maxCandidate
                if (maxC != null && maxC > 0f &&
                    1f - candidate / maxC > DROP_ARM_FRACTION &&
                    stableTicks >= STABLE_TICKS
                ) {
                    glide(candidate, shown, nowMs)
                }
                // Кандидат выше показанного, но путь не открыт — якорь
                // держим: подъём вслед за неподтверждённой моделью
                // запрещён («без ложных подъёмов»).
            }
        }
        return out(nowMs)
    }

    // Скольжение якоря к цели: в пределах порога — всплыть к цели сразу
    // (шаг ≤ порога не заметен, а вечные микрокоррекции конфликтуют с
    // ритмом «минус секунда»); за порогом — экспоненциальное схождение.
    private fun glide(target: Float, shown: Float, nowMs: Long) {
        val diff = target - shown
        val magnitude = abs(diff)
        if (magnitude <= CORRECTION_ENTER_SEC) {
            setAnchor(target, nowMs)
            return
        }
        val step = min(magnitude, max(SLEW_MIN_STEP_SEC, magnitude * SLEW_FRACTION))
        setAnchor(if (diff > 0f) shown + step else shown - step, nowMs)
    }

    // Показанное значение в данный момент: якорь минус прошедшее время.
    private fun shownNow(nowMs: Long): Float? {
        val anchor = anchorSec ?: return null
        val at = anchorAtMs ?: return null
        return anchor - (nowMs - at).coerceAtLeast(0L) / 1000f
    }

    private fun out(nowMs: Long): Long? =
        shownNow(nowMs)?.roundToLong()?.coerceAtLeast(0L)

    private fun setAnchor(sec: Float, nowMs: Long) {
        anchorSec = sec
        anchorAtMs = nowMs
    }

    // Точка отсчёта тренда после крупного падения: медленная EMA и окно
    // начинаются от текущего (упавшего) значения.
    private fun resetTrendWindow(raw: Float, nowMs: Long) {
        slowEmaSec = raw
        trendHistory.clear()
        trendHistory.addLast(nowMs to raw)
        trendTicks = 0
    }

    fun reset() {
        emaSec = null
        slowEmaSec = null
        trendHistory.clear()
        trendTicks = 0
        lastUpdateMs = null
        anchorSec = null
        anchorAtMs = null
        maxCandidate = null
        prevCandidate = null
        stableTicks = 0
        reviewLatched = false
        reviewTicks = 0
    }

    companion object {
        const val EMA_TAU_MS = 10_000L
        // Медленная EMA тренд-пути (D6).
        const val SLOW_EMA_TAU_MS = 60_000L
        const val TREND_WINDOW_MS = 60_000L
        const val TREND_TICKS = 3
        // Порог шума тренда в секундах: шумовой рост разности за 60 с
        // (единицы-десятки секунд) не открывает путь, тренд смеси
        // (сотни секунд за окно) — открывает. Закреплён тестами
        // DisplayFilterTest (property + антипила) — калибровать парой.
        const val TREND_NOISE_FLOOR_SEC = 40f
        const val REVIEW_TICKS = 3
        const val REVIEW_FRACTION = 0.25f
        // Падение кандидата больше этой величины — не шум, а смена
        // модели: окно тренда начинается заново.
        const val TREND_RESET_DROP_SEC = 60f
        // Константы скольжения якоря — калибруются на устройстве автора
        // (задача 1.4 change smooth-anchored-countdown): форма поведения
        // (плавное схождение без прыжков) зафиксирована спекой, числа —
        // нет. Менять только парой с тестами DisplayFilterTest.
        // Порог входа коррекции: расхождение модели с показом в пределах
        // него принимается целиком — микрокоррекции не спорят с ритмом
        // «минус секунда».
        const val CORRECTION_ENTER_SEC = 3f
        // Доля расхождения, закрываемая за тик: крупный пересмотр
        // разъезжается за десятки секунд, крупных шагов нет ни в одном
        // кадре.
        const val SLEW_FRACTION = 0.2f
        // Шаг-пол: гарантирует движение к цели на малых расхождениях.
        const val SLEW_MIN_STEP_SEC = 2f
        // Доля просадки кандидата от максимума с посева, включающая
        // догоняющее держание: отделяет ревизии от шума ±5% с запасом.
        // Число калибруется на устройстве вместе с остальными константами
        // скольжения (менять только парой с DisplayFilterTest).
        const val DROP_ARM_FRACTION = 0.25f
        // Подряд одинаковых (после округления) кандидатов для признания
        // стабилизации модели после пересмотра.
        const val STABLE_TICKS = 5
    }
}
