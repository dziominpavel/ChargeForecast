package com.example.chargeforecast.forecast

// Честная точность прогноза: ярусы возраста сессии + согласованность
// спидометров. COLLECTING — ярус без опоры скорости (его строка на
// экране скрыта: без кабеля работает карточка разряда, на кабеле
// опорой служит история или приор типа подключения).
enum class ForecastAccuracy {
    COLLECTING,
    APPROXIMATE,
    REFINING,
    PRECISE
}

data class ChargeForecast(
    // Показываемая скорость — эффективная: остаток, делённый на время
    // (см. design D4). Мгновенная скорость остаётся входом base rate,
    // поэтому на экране скорость и остаток складываются в одно
    // уравнение: остаток ÷ скорость = время.
    val speedPctPerMin: Float?,
    // Null — показывать нельзя (сбор данных / нет зарядки / зарядка не
    // идёт). Секунды до 100%: выдаются каждую секунду, без округлений.
    val remainingSec: Long?,
    val accuracy: ForecastAccuracy,
    val isFull: Boolean,
    // Остаток в процентах ёмкости, из которого посчитано время: по нему
    // журнал и тесты сверяют «скорость × остаток = время». null — прогноза нет.
    val remainingPct: Float? = null
)

// Экстраполяция остатка по энергии. Остаток считается в мкА·ч до полного
// заряда (ёмкость − счётчик), а не смесью процентов и корзин; кривая
// доводки применяется к энергии (design D1–D3). Ноль на неполном заряде
// запрещён: минимальный остаток 0.5% и резерв по уровню заряда.
// «Точно» — только на согласованном входе. Чистый объект — JVM-тесты.
object ChargeForecaster {

    const val PRECISE_MIN_AGE_MS = 10 * 60_000L
    const val AGREE_TOLERANCE = 0.2f
    // Минимальный остаток — не ноль: 0.5% ёмкости (design D3).
    const val MIN_REMAINING_FRACTION = 0.005f
    // Граница доводки — TaperCurve.TAPER_START_LEVEL: ниже неё скорость
    // ниже порога — мусор рампы.

    fun forecast(
        levelPct: Int,
        speedPctPerMin: Float?,
        sessionAgeMs: Long,
        isFull: Boolean,
        // Семантика «сессия активна» (design D7): сессия уже идёт (её
        // старт — переход isPlugged), поэтому определяет выдачу цифр
        // только наличие опоры, а не запоздавший статус CHARGING.
        sessionActive: Boolean,
        // Расхождение S1/S2 больше 40% — «уточняю» до схождения.
        refining: Boolean = false,
        // Два независимых спидометра сошлись в ±20% — условие «точно».
        estimatesAgreed: Boolean = false,
        // Энергия до полного (мкА·ч) и оценённая ёмкость — основной путь
        // остатка. Счётчик восстанавливается из разницы.
        remainingEnergyUah: Long? = null,
        capacityUah: Long? = null,
        // Кривая доводки (обученная или дефолт).
        taperFactors: FloatArray? = null,
        // Сглаженная базовая опора (линейная зона) от движка; если null —
        // выводится из измеренной скорости и фактора текущего уровня.
        baseRatePctPerMin: Float? = null,
        // Режим счётчика: грубый — остаток по уровню, точный — по энергии.
        // По умолчанию точный, чтобы чистый объект сохранял прежний
        // энергетический контракт; движок всегда передаёт режим.
        counterMode: CounterMode = CounterMode.FINE
    ): ChargeForecast {
        if (isFull || levelPct >= 100) {
            return ChargeForecast(
                speedPctPerMin = null,
                remainingSec = 0L,
                accuracy = ForecastAccuracy.PRECISE,
                isFull = true,
                remainingPct = 0f
            )
        }
        // Ни опоры (скорость null/мусорная) — ждём. Порог доверия:
        // скорость ниже мусорной рампы вне доводки — цифры прячем
        // («1000 часов»). Статус зарядки на это не влияет (D7).
        if (!sessionActive || speedPctPerMin == null || speedPctPerMin <= 0f ||
            (levelPct < TaperCurve.TAPER_START_LEVEL &&
                speedPctPerMin < InstantSpeedEstimator.MIN_CREDIBLE_SPEED_PCT_PER_MIN)
        ) {
            return collecting()
        }
        val curve = taperFactors ?: TaperCurve.DEFAULT_FACTORS
        val baseRate = baseRatePctPerMin
            ?: speedPctPerMin / TaperCurve.factor(levelPct, curve)
        if (baseRate <= 0f) return collecting()

        var reserveUsed = false
        var remainingPct: Float
        var minutes: Float
        if (counterMode == CounterMode.COARSE) {
            // Грубый счётчик (шаг 1% ёмкости): остаток по уровню, ёмкость
            // не в критическом пути — инверсия счётчика и ёмкости больше
            // не может превратить остаток в ноль (design D3).
            remainingPct = (100 - levelPct).coerceIn(0, 100).toFloat()
            minutes = TaperCurve.remainingMinutes(remainingPct, baseRate, levelPct, curve)
        } else {
            // Точный счётчик: энергия до полного заряда. Счётчик обязан
            // быть меньше ёмкости; иначе — резерв по уровню (спека
            // «Достоверность входа»).
            val capacity = capacityUah?.takeIf { it > 0L }
            val counter = if (capacity != null && remainingEnergyUah != null) {
                capacity - remainingEnergyUah
            } else {
                null
            }
            val energyReliable = capacity != null && remainingEnergyUah != null &&
                counter != null && counter >= 0L && counter < capacity
            reserveUsed = capacity != null && remainingEnergyUah != null && !energyReliable
            if (energyReliable) {
                // Минимальный остаток 0.5% ёмкости: не ноль и не отрицательное.
                val floorUah = (capacity!! * MIN_REMAINING_FRACTION).toLong().coerceAtLeast(1L)
                val energyUah = remainingEnergyUah!!.coerceAtLeast(floorUah)
                remainingPct = (energyUah * 100f / capacity).coerceIn(0f, 100f)
                minutes = TaperCurve.remainingMinutesForEnergy(
                    remainingUah = energyUah,
                    capacityUah = capacity,
                    levelPct = levelPct,
                    baseRatePctPerMin = baseRate,
                    factors = curve
                )
            } else {
                // Резерв по уровню заряда: счётчика нет или он противоречит
                // ёмкости. Для обычного режима «счётчика нет» это штатный
                // путь, а не резерв недостоверного входа.
                remainingPct = (100 - levelPct).coerceIn(0, 100).toFloat()
                minutes = TaperCurve.remainingMinutes(remainingPct, baseRate, levelPct, curve)
            }
        }

        var seconds = (minutes * 60f).toLong()
        if (seconds <= 0L && levelPct < 100) {
            // Запрет нуля: на неполном заряде «0 с» заменяется остатком по
            // уровню с честным ярусом «Примерно» (спека, «Остаток не равен
            // нулю при неполном заряде»).
            remainingPct = (100 - levelPct).toFloat()
            val reserveMinutes = TaperCurve.remainingMinutes(remainingPct, baseRate, levelPct, curve)
            seconds = (reserveMinutes * 60f).toLong().coerceAtLeast(1L)
            reserveUsed = true
        }
        if (seconds <= 0L) seconds = 1L

        // Показываемая скорость — эффективная: остаток ÷ время. Иначе
        // мгновенная скорость и интеграл с доводкой противоречили бы.
        val effectiveSpeed = if (seconds > 0L) {
            remainingPct / (seconds / 60f)
        } else {
            null
        }
        // Ярусы точности: «точно» — только после 10 минут, при согласии
        // спидометров, согласованном входе и без резервного остатка.
        val accuracy = when {
            refining -> ForecastAccuracy.REFINING
            sessionAgeMs >= PRECISE_MIN_AGE_MS && estimatesAgreed && !reserveUsed ->
                ForecastAccuracy.PRECISE
            else -> ForecastAccuracy.APPROXIMATE
        }
        return ChargeForecast(
            speedPctPerMin = effectiveSpeed,
            remainingSec = seconds,
            accuracy = accuracy,
            isFull = false,
            remainingPct = remainingPct
        )
    }

    private fun collecting() = ChargeForecast(
        speedPctPerMin = null,
        remainingSec = null,
        accuracy = ForecastAccuracy.COLLECTING,
        isFull = false
    )
}
