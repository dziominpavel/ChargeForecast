package com.example.chargeforecast.forecast

import kotlin.math.exp
import kotlin.math.roundToLong

// Фильтр показа: EMA сырой остаток (tau=10 с) и округление до секунд.
// Каждый тик — новое значение, без корзин. Два пути пересмотра вверх:
// 1. Рывок: рост более 25% принимается, если держится 3 тика подряд.
// 2. Тренд (D6): медленная EMA (tau=60 с) выросла относительно значения
//    минуту назад больше чем на TREND_NOISE_FLOOR_SEC и условие держится
//    TREND_TICKS тика подряд → кандидат принимается каждым тиком
//    (плавный пересмотр модели вверх идёт мелкими шагами, без пилы).
// Пока путь не открыт, кандидат выше показанного НЕ опускает показанное
// (держим значение) — спека «без ложных падений»: пауза в ожидании
// тренда лучше, чем падение вслед за растущей моделью.
// Вниз — всегда свободно; полный заряд (raw = 0) принимается мгновенно.
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
    private var displayedSec: Float? = null
    private var displayedAtMs: Long? = null
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
            accept(0L, nowMs)
            return 0L
        }
        emaSec = if (rawRemainingSec == null) {
            // Нет данных — держим EMA: показанное продолжает убывать.
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
            // свежие — старые для разности больше не нужны.
            while (trendHistory.size > 1 && trendHistory[1].first <= cutoff) {
                trendHistory.removeFirst()
            }
            val rising = refSec != null && slow - refSec > TREND_NOISE_FLOOR_SEC
            trendTicks = if (rising) trendTicks + 1 else 0
        } else {
            trendTicks = 0
        }
        val trendOpen = trendTicks >= TREND_TICKS
        val shown = displayedSec
        val shownAt = displayedAtMs
        if (shown == null || shownAt == null || shown <= 0f) {
            val ema = emaSec ?: return null
            accept(ema.roundToLong().coerceAtLeast(0L), nowMs)
        } else if (rawRemainingSec == null) {
            // Данных нет — показанное продолжает убывать по времени.
            val elapsedSec = (nowMs - shownAt).toFloat() / 1000f
            displayedSec = (shown - elapsedSec).coerceAtLeast(0f)
            displayedAtMs = nowMs
        } else {
            val ema = emaSec ?: return null
            val candidate = ema.roundToLong().coerceAtLeast(0L)
            when {
                // Вниз — свободно. Крупное падение (граница корзин,
                // обвал модели) обнуляет окно тренда: иначе разность
                // «60 с назад» отравлена высокими значениями, путь
                // тренда не откроется при росте, и рывок примет сразу
                // больше +25% — пила вернётся.
                candidate <= shown -> {
                    reviewTicks = 0
                    if (shown - candidate > TREND_RESET_DROP_SEC) {
                        resetTrendWindow(rawRemainingSec, nowMs)
                    }
                    accept(candidate, nowMs)
                }
                // Тренд подтверждён — плавный пересмотр вверх каждым тиком.
                trendOpen -> {
                    accept(candidate, nowMs)
                }
                // Вверх рывком — только устойчивый пересмотр: кандидат
                // держится выше порога +25% три тика подряд (одиночные
                // всплески шума сбрасывают счётчик затуханием).
                candidate > shown * (1f + REVIEW_FRACTION) -> {
                    reviewTicks++
                    if (reviewTicks >= REVIEW_TICKS) {
                        accept(candidate, nowMs)
                    } else {
                        // До подтверждения показанное не опускается:
                        // это ожидание пересмотра, а не новые данные.
                        displayedAtMs = nowMs
                    }
                }
                else -> {
                    reviewTicks = 0
                    // Кандидат выше показанного, но путь не открыт —
                    // держим показанное («без ложных падений»: падение
                    // вслед за растущей моделью запрещено спекой;
                    // убывание по времени остаётся для ветки без данных).
                    displayedAtMs = nowMs
                }
            }
        }
        val out = displayedSec ?: return null
        return out.roundToLong().coerceAtLeast(0L)
    }

    private fun accept(candidate: Long, nowMs: Long) {
        displayedSec = candidate.toFloat()
        displayedAtMs = nowMs
        reviewTicks = 0
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
        displayedSec = null
        displayedAtMs = null
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
    }
}
