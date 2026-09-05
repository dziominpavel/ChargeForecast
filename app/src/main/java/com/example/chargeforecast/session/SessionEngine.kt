package com.example.chargeforecast.session

import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.forecast.CapacityTracker
import com.example.chargeforecast.forecast.ChargeForecaster
import com.example.chargeforecast.forecast.ChargeForecast
import com.example.chargeforecast.forecast.ChargeSpeedCalculator
import com.example.chargeforecast.forecast.CounterSpeedometer
import com.example.chargeforecast.forecast.DisplayFilter
import com.example.chargeforecast.forecast.ForecastAccuracy
import com.example.chargeforecast.forecast.InstantSpeedEstimator
import com.example.chargeforecast.forecast.SpeedFusion
import com.example.chargeforecast.forecast.TaperCurve
import com.example.chargeforecast.forecast.TaperCurveLearner

// Чистый движок сессии (см. design фазы 1): все расчёты от переданной
// монотонной метки времени (elapsedRealtime — подстройка системных
// часов не влияет), три спидометра + fusion, остаток в мкА·ч,
// фильтр показа. Секунда ноль — от перехода isPlugged (кабель), а не
// от первого CHARGING: рампа зарядника не сдвигает старт сессии.
// Тестируем на JVM с любым clock.
internal class SessionEngine(
    private val cache: SessionCache? = null,
    private val sysfsCapacityUah: () -> Long? = { null }
) {
    private val calculator = ChargeSpeedCalculator()
    private val counterShort = CounterSpeedometer(
        windowMs = CounterSpeedometer.WINDOW_SHORT_MS
    )
    private val counterLong = CounterSpeedometer()
    private val capacityTracker = CapacityTracker()
    private val ccAccumulator = CcPhaseAccumulator()
    private val displayFilter = DisplayFilter()
    private val curveLearner = TaperCurveLearner()

    private var plugDetectedAtMs: Long? = null
    private var sessionStartLevelPct: Int? = null
    private var emaCurrentUa: Long? = null
    private var lastEmaSampleMs: Long? = null
    // Пик тока — факт сессии для сводки, не для оценки скорости.
    private var peakCurrentUa: Long? = null
    private var lastCapacityUah: Long? = null
    // Кривая доводки профиля (обученная из кэша или дефолт) + линейная
    // опора скорости для обучения.
    private var taperFactors: FloatArray = TaperCurve.DEFAULT_FACTORS
    private var linearBaseRatePctPerMin: Float? = null
    // Сглаженная базовая опора для интеграла: на границах корзин
    // измерение с лагом против ступенчатого фактора даёт выброс —
    // EMA гасит его (см. design D5).
    private var baseRateEmaPctPerMin: Float? = null

    fun update(snapshot: BatterySnapshot?, nowMs: Long): SessionState {
        if (snapshot == null) return idle()
        if (!snapshot.isPlugged) {
            if (plugDetectedAtMs != null) {
                // Сессия кончилась — сводка и ёмкость пишутся ОДИН раз,
                // именно здесь; во время сессии кэш прошлой не трогаем.
                lastCapacityUah?.let { cache?.saveCapacity(it) }
                buildSummary(snapshot, nowMs)?.let { cache?.saveSummary(it) }
                // Обученная кривая профиля: слияние с прежней, персист.
                curveLearner.learnedFactors()?.let { learned ->
                    cache?.saveCurve(
                        snapshot.chargeType, TaperCurve.merge(taperFactors, learned)
                    )
                }
            }
            resetSession()
            return idle()
        }
        if (plugDetectedAtMs == null) {
            // Секунда ноль: кабель подключён (переход isPlugged).
            plugDetectedAtMs = nowMs
            sessionStartLevelPct = snapshot.levelPct
            // Кривая профиля на всю сессию: обученная из кэша или дефолт.
            taperFactors = cache?.loadCurve(snapshot.chargeType)
                ?: TaperCurve.DEFAULT_FACTORS
            linearBaseRatePctPerMin = null
        }
        val start = plugDetectedAtMs ?: nowMs
        val age = (nowMs - start).coerceAtLeast(0L)

        // Замеры копятся с plugDetectedAtMs; рампу не выбрасываем —
        // EMA тока сама её сгладит (см. design D7).
        calculator.addSample(nowMs, snapshot.levelPct)
        snapshot.chargeCounterUah?.let { counterUah ->
            counterShort.addSample(nowMs, counterUah)
            counterLong.addSample(nowMs, counterUah)
        }
        val liveCapacity = capacityTracker.update(
            snapshot.chargeCounterUah, snapshot.levelPct
        )
        if (liveCapacity != null) {
            lastCapacityUah = liveCapacity
            cache?.saveCapacity(liveCapacity)
        }
        val capacityUah = liveCapacity ?: sysfsCapacityUah() ?: cache?.loadCapacity()

        val currentUa = snapshot.currentMa?.let { (it * 1000f).toLong() }
        if (currentUa != null && currentUa > 0) {
            peakCurrentUa = maxOf(peakCurrentUa ?: currentUa, currentUa)
        }
        val dtEma = lastEmaSampleMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        emaCurrentUa = InstantSpeedEstimator.updateEmaCurrentUa(emaCurrentUa, currentUa, dtEma)
        if (currentUa != null) lastEmaSampleMs = nowMs

        // Три независимых спидометра + fusion по ярусам возраста.
        val estimates = SpeedFusion.Estimates(
            counterShortPctPerMin = counterShort.speedPctPerMin(nowMs, capacityUah),
            counterLongPctPerMin = counterLong.speedPctPerMin(nowMs, capacityUah),
            currentPctPerMin = InstantSpeedEstimator.instantSpeedPctPerMin(
                emaCurrentUa, capacityUah
            ),
            levelPctPerMin = calculator.speedPctPerMin(nowMs)
        )
        val prior = cache?.loadLastSpeed()
        val fused = SpeedFusion.fuse(estimates, age, prior, snapshot.levelPct)
        // В сводку копим только CC-фазу (рампа/доводка отфильтрованы
        // накопителем) и только устойчивую скорость: сэмплы «уточняю»
        // (нестабильная оценка) приор портить не должны.
        if (!fused.refining) {
            ccAccumulator.add(age, snapshot.levelPct, fused.speedPctPerMin)
        }
        // Обучение кривой доводки: в линейной зоне копим опору скорости,
        // в корзинах 80-100 копим наблюдаемые множители (короткое окно —
        // меньше лага на смене корзин).
        val measuredRate = estimates.counterShortPctPerMin
            ?: estimates.counterLongPctPerMin
        if (snapshot.levelPct < TaperCurve.TAPER_START_LEVEL && measuredRate != null) {
            linearBaseRatePctPerMin = linearBaseRatePctPerMin?.let {
                it * 0.98f + measuredRate * 0.02f
            } ?: measuredRate
        }
        curveLearner.add(snapshot.levelPct, measuredRate, linearBaseRatePctPerMin)
        // Энергия до полного (мкА·ч) = ёмкость − счётчик (счётчик —
        // заряд В батарее): непрерывный остаток, обход квантования 1%.
        val remainingEnergyUah = capacityUah?.let { cap ->
            snapshot.chargeCounterUah?.let { counter -> cap - counter }
        }
        // Базовая опора для интеграла: измеренная скорость, нормализованная
        // к линейной зоне; EMA гасит выброс на границах корзин.
        val rawBaseRate = fused.speedPctPerMin?.let {
            it / TaperCurve.factor(snapshot.levelPct, taperFactors)
        }
        baseRateEmaPctPerMin = rawBaseRate?.let { rb ->
            baseRateEmaPctPerMin?.let { e -> e * 0.95f + rb * 0.05f } ?: rb
        }
        val raw = ChargeForecaster.forecast(
            levelPct = snapshot.levelPct,
            speedPctPerMin = fused.speedPctPerMin,
            sessionAgeMs = age,
            isFull = snapshot.isFull,
            isCharging = snapshot.isCharging,
            refining = fused.refining,
            estimatesAgreed = estimatesAgreed(estimates),
            remainingEnergyUah = remainingEnergyUah,
            capacityUah = capacityUah,
            taperFactors = taperFactors,
            baseRatePctPerMin = baseRateEmaPctPerMin
        )
        // Фильтр показа: EMA тренда + округление до секунд — каждый тик
        // новое значение, экстраполированное до 100%.
        val displayed = displayFilter.update(raw.remainingSec?.toFloat(), nowMs)
        val forecast = raw.copy(remainingSec = displayed)
        return SessionState(
            snapshot = snapshot,
            forecast = forecast,
            sessionAgeMs = age,
            error = null
        )
    }

    // «Точно» — когда два самых надёжных доступных спидометра сошлись
    // в ±20% (приор: S1(2м)↔S2, S1(30с)↔S2, затем пары с S3).
    private fun estimatesAgreed(estimates: SpeedFusion.Estimates): Boolean {
        val pairs = listOf(
            estimates.counterLongPctPerMin to estimates.currentPctPerMin,
            estimates.counterShortPctPerMin to estimates.currentPctPerMin,
            estimates.counterLongPctPerMin to estimates.levelPctPerMin,
            estimates.counterShortPctPerMin to estimates.levelPctPerMin
        )
        return pairs.any { (a, b) ->
            a != null && b != null &&
                SpeedFusion.divergence(a, b) <= ChargeForecaster.AGREE_TOLERANCE
        }
    }

    private fun idle(): SessionState = SessionState(
        snapshot = null,
        forecast = ChargeForecast(
            speedPctPerMin = null,
            remainingSec = null,
            accuracy = ForecastAccuracy.COLLECTING,
            isFull = false
        ),
        sessionAgeMs = 0L
    )

    private fun resetSession() {
        plugDetectedAtMs = null
        sessionStartLevelPct = null
        emaCurrentUa = null
        lastEmaSampleMs = null
        peakCurrentUa = null
        lastCapacityUah = null
        linearBaseRatePctPerMin = null
        baseRateEmaPctPerMin = null
        calculator.reset()
        counterShort.reset()
        counterLong.reset()
        capacityTracker.reset()
        ccAccumulator.reset()
        displayFilter.reset()
        curveLearner.reset()
    }

    // Сводка завершённой сессии: средняя CC-фаза (без рампы и доводки).
    // Без накопленных сэмплов сводки нет — приор прошлой не портится.
    // Короткие (<2% набора) и околонулевые сессии сводку не пишут:
    // мусорная тишина не должна становиться приором следующей.
    private fun buildSummary(snapshot: BatterySnapshot, nowMs: Long): SessionSummary? {
        val startMs = plugDetectedAtMs ?: return null
        val avg = ccAccumulator.averagePctPerMin() ?: return null
        if (avg < SUMMARY_MIN_AVG_PCT_PER_MIN) return null
        val startLevel = sessionStartLevelPct ?: snapshot.levelPct
        val gained = (snapshot.levelPct - startLevel).coerceAtLeast(0)
        if (gained < SUMMARY_MIN_GAINED_PCT) return null
        return SessionSummary(
            endedAtMs = nowMs,
            durationMs = (nowMs - startMs).coerceAtLeast(0L),
            gainedPct = gained,
            avgCcSpeedPctPerMin = avg,
            peakCurrentMa = peakCurrentUa?.let { it / 1000f },
            chargeType = snapshot.chargeType
        )
    }

    companion object {
        // Сводка пишется только для настоящих сессий: средний темп
        // CC-фазы не ниже порога и набрано не меньше 2%.
        const val SUMMARY_MIN_AVG_PCT_PER_MIN = 0.1f
        const val SUMMARY_MIN_GAINED_PCT = 2
    }
}
