package com.example.chargeforecast.forecast

// Честная точность прогноза: физический предел первых минут.
enum class ForecastAccuracy {
    COLLECTING,
    APPROXIMATE,
    PRECISE
}

data class ChargeForecast(
    val speedPctPerMin: Float?,
    // Null — показывать нельзя (сбор данных / нет зарядки).
    val remainingMin: Long?,
    val accuracy: ForecastAccuracy,
    val isFull: Boolean
)

// Экстраполяция (100 − уровень) / скорость с поправкой на медленную
// доводку после 80%. Чистый объект — полностью тестируем на JVM.
object ChargeForecaster {

    // Калибровки spike-замера — см. задачу 2.3 в tasks.md.
    const val PRECISE_MIN_AGE_MS = 10 * 60_000L
    const val EARLY_PRECISE_MIN_AGE_MS = 3 * 60_000L
    const val AGREE_TOLERANCE = 0.2f
    const val TAPER_START_LEVEL = 80
    // Скорость доводки относительно линейной (0.5 = в два раза медленнее).
    const val TAPER_FACTOR = 0.5f

    fun forecast(
        levelPct: Int,
        measuredSpeedPctPerMin: Float?,
        instantSpeedPctPerMin: Float?,
        sessionAgeMs: Long,
        isFull: Boolean,
        isCharging: Boolean
    ): ChargeForecast {
        if (isFull || levelPct >= 100) {
            return ChargeForecast(
                speedPctPerMin = null,
                remainingMin = 0L,
                accuracy = ForecastAccuracy.PRECISE,
                isFull = true
            )
        }
        // Бленд: сначала вес у мгновенной оценки по току, затем она
        // плавно вытесняется измеренной скоростью по дельте уровня.
        // К 10-й минуте остаётся только замер — точность выросла.
        val measuredWeight = (sessionAgeMs.toFloat() / PRECISE_MIN_AGE_MS).coerceIn(0f, 1f)
        val speed = when {
            measuredSpeedPctPerMin != null && instantSpeedPctPerMin != null ->
                instantSpeedPctPerMin * (1f - measuredWeight) +
                    measuredSpeedPctPerMin * measuredWeight
            measuredSpeedPctPerMin != null -> measuredSpeedPctPerMin
            instantSpeedPctPerMin != null -> instantSpeedPctPerMin
            else -> null
        }
        // Ни тока со счётчиком, ни двух точек уровня — ждём данные.
        // Плюс порог доверия: скорость ниже мусорной рампы вне доводки —
        // цифры прячем (защита от «1000 часов»). Доводка исключена:
        // там медленно по-настоящему.
        if (!isCharging || speed == null || speed <= 0f ||
            (levelPct < TAPER_START_LEVEL &&
                speed < InstantSpeedEstimator.MIN_CREDIBLE_SPEED_PCT_PER_MIN)
        ) {
            return ChargeForecast(
                speedPctPerMin = null,
                remainingMin = null,
                accuracy = ForecastAccuracy.COLLECTING,
                isFull = false
            )
        }
        val accuracy = when {
            measuredSpeedPctPerMin != null && measuredWeight >= 1f -> ForecastAccuracy.PRECISE
            // Ранняя точность по схождению: независимые оценки сошлись —
            // это объективно замер, хоть и не прошло 10 минут.
            measuredSpeedPctPerMin != null &&
                instantSpeedPctPerMin != null &&
                measuredSpeedPctPerMin > 0f &&
                sessionAgeMs >= EARLY_PRECISE_MIN_AGE_MS &&
                kotlin.math.abs(instantSpeedPctPerMin - measuredSpeedPctPerMin) /
                measuredSpeedPctPerMin <= AGREE_TOLERANCE ->
                ForecastAccuracy.PRECISE
            else -> ForecastAccuracy.APPROXIMATE
        }
        var base = (100 - levelPct) / speed
        if (levelPct >= TAPER_START_LEVEL) {
            base /= TAPER_FACTOR
        }
        val remaining = base.toLong().coerceAtLeast(1L)
        // Прогноз не занижаем: минимум 1 минута.
        return ChargeForecast(
            speedPctPerMin = speed,
            remainingMin = remaining,
            accuracy = accuracy,
            isFull = false
        )
    }
}
