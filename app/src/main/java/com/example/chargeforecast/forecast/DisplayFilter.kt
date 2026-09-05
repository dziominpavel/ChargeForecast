package com.example.chargeforecast.forecast

import kotlin.math.exp
import kotlin.math.roundToLong

// Фильтр показа: EMA сырой оценки остатка (tau=10 сек — тренд) и
// округление до секунд. Каждый тик — новое значение, без корзин.
// Единственная защита — от одиночных мусорных всплесков вверх:
// рост более 25% принимается только если держится 3 тика подряд.
// Вниз — всегда свободно. Чистый класс — тестируем на JVM.
class DisplayFilter(
    private val emaTauMs: Long = EMA_TAU_MS
) {
    private var emaSec: Float? = null
    private var lastUpdateMs: Long? = null
    private var displayedSec: Float? = null
    private var displayedAtMs: Long? = null
    private var reviewTicks = 0

    // rawRemainingSec — сырая оценка остатка в секундах.
    // Возвращает показанное значение в секундах.
    fun update(rawRemainingSec: Float?, nowMs: Long): Long? {
        val dtMs = lastUpdateMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
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
        if (dtMs > 0L) lastUpdateMs = nowMs
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
            val elapsedSec = (nowMs - shownAt).toFloat() / 1000f
            when {
                // Вниз — свободно.
                candidate <= shown -> {
                    reviewTicks = 0
                    accept(candidate, nowMs)
                }
                // Вверх — только устойчивый пересмотр: кандидат держится
                // выше порога +25% три тика подряд (одиночные всплески
                // шума сбрасывают счётчик затуханием).
                candidate > shown * (1f + REVIEW_FRACTION) -> {
                    reviewTicks++
                    if (reviewTicks >= REVIEW_TICKS) {
                        accept(candidate, nowMs)
                    } else {
                        displayedSec = (shown - elapsedSec).coerceAtLeast(0f)
                        displayedAtMs = nowMs
                    }
                }
                else -> {
                    reviewTicks = 0
                    // Показанное убывает на прошедшее время.
                    displayedSec = (shown - elapsedSec).coerceAtLeast(0f)
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

    fun reset() {
        emaSec = null
        lastUpdateMs = null
        displayedSec = null
        displayedAtMs = null
        reviewTicks = 0
    }

    companion object {
        const val EMA_TAU_MS = 10_000L
        const val REVIEW_TICKS = 3
        const val REVIEW_FRACTION = 0.25f
    }
}
