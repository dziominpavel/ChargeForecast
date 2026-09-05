package com.example.chargeforecast.forecast

// Честная точность прогноза: ярусы возраста сессии + согласованность
// спидометров. «Измеряю…» — презентация COLLECTING с живым счётчиком.
enum class ForecastAccuracy {
    COLLECTING,
    APPROXIMATE,
    REFINING,
    PRECISE
}

data class ChargeForecast(
    val speedPctPerMin: Float?,
    // Null — показывать нельзя (сбор данных / нет зарядки).
    // Секунды до 100%: выдаются каждую секунду, без округлений-корзин.
    val remainingSec: Long?,
    val accuracy: ForecastAccuracy,
    val isFull: Boolean
)

// Экстраполяция остатка по скорости. Где доступен счётчик — остаток
// в мкА·ч (непрерывный, обход квантования уровня 1%): CHARGE_COUNTER
// по семантике BatteryManager — именно ОСТАТОК заряда (это уже
// использует estimateCapacityUah = counter·100/level). Доводка после
// 80% — интеграл по 5%-корзинам TaperCurve (скорость уже отражает
// замедление, модельный множитель не применяется повторно).
// Ярусы точности — по возрасту сессии и согласованности спидометров.
// Чистый объект — полностью тестируем на JVM.
object ChargeForecaster {

    const val PRECISE_MIN_AGE_MS = 10 * 60_000L
    const val AGREE_TOLERANCE = 0.2f
    // Граница доводки — TaperCurve.TAPER_START_LEVEL: ниже неё скорость
    // ниже порога — мусор рампы. Ярус «Измеряю…»: первые секунды цифр
    // нет — честный предел данных.
    const val MEASURING_MAX_MS = 20_000L

    fun forecast(
        levelPct: Int,
        speedPctPerMin: Float?,
        sessionAgeMs: Long,
        isFull: Boolean,
        isCharging: Boolean,
        // Расхождение S1/S2 больше 40% — «уточняю» до схождения.
        refining: Boolean = false,
        // Два независимых спидометра сошлись в ±20% — условие «точно».
        estimatesAgreed: Boolean = false,
        // Счётчик остатка (мкА·ч) и ёмкость: непрерывный остаток.
        remainingEnergyUah: Long? = null,
        capacityUah: Long? = null,
        // Кривая доводки (обученная или дефолт).
        taperFactors: FloatArray? = null,
        // Сглаженная базовая опора (линейная зона) от движка; если null —
        // выводится из измеренной скорости и фактора текущего уровня.
        baseRatePctPerMin: Float? = null
    ): ChargeForecast {
        if (isFull || levelPct >= 100) {
            return ChargeForecast(
                speedPctPerMin = null,
                remainingSec = 0L,
                accuracy = ForecastAccuracy.PRECISE,
                isFull = true
            )
        }
        // Ни скорости, ни зарядки — ждём. Порог доверия: скорость ниже
        // мусорной рампы вне доводки — цифры прячем («1000 часов»).
        if (!isCharging || speedPctPerMin == null || speedPctPerMin <= 0f ||
            (levelPct < TaperCurve.TAPER_START_LEVEL &&
                speedPctPerMin < InstantSpeedEstimator.MIN_CREDIBLE_SPEED_PCT_PER_MIN)
        ) {
            return ChargeForecast(
                speedPctPerMin = null,
                remainingSec = null,
                accuracy = ForecastAccuracy.COLLECTING,
                isFull = false
            )
        }
        val curve = taperFactors ?: TaperCurve.DEFAULT_FACTORS
        val baseRate = baseRatePctPerMin
            ?: speedPctPerMin / TaperCurve.factor(levelPct, curve)
        // Остаток: непрерывный по счётчику (ёмкость − заряд в батарее),
        // иначе по уровню (fallback).
        val remainingPct: Float = when {
            remainingEnergyUah != null && capacityUah != null && capacityUah > 0 ->
                (remainingEnergyUah * 100f / capacityUah)
                    .coerceIn(0f, 100f)
            else -> (100 - levelPct).toFloat()
        }
        val remainingF = TaperCurve.remainingMinutes(
            remainingPct, baseRate, levelPct, curve
        )
        // Секунды до 100% — каждый тик новое значение, без корзин.
        val remaining = (remainingF * 60f).toLong().coerceAtLeast(0L)
        // Ярусы точности: «точно» — только после 10 минут при согласии
        // спидометров; расхождение — «уточняю».
        val accuracy = when {
            refining -> ForecastAccuracy.REFINING
            sessionAgeMs >= PRECISE_MIN_AGE_MS && estimatesAgreed -> ForecastAccuracy.PRECISE
            else -> ForecastAccuracy.APPROXIMATE
        }
        return ChargeForecast(
            speedPctPerMin = speedPctPerMin,
            remainingSec = remaining,
            accuracy = accuracy,
            isFull = false
        )
    }
}
