package com.example.chargeforecast

import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.ChargeForecaster
import com.example.chargeforecast.forecast.ChargeSpeedCalculator
import com.example.chargeforecast.forecast.ForecastAccuracy
import com.example.chargeforecast.session.SessionCache
import com.example.chargeforecast.session.SessionEngine
import com.example.chargeforecast.session.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChargeForecastTest {

    private val min = 60_000L

    // Ровная сессия: +1 %/мин в течение 10 минут.
    private fun steady(calc: ChargeSpeedCalculator, startLevel: Int = 40) {
        for (i in 0..10) {
            calc.addSample(i * min, startLevel + i)
        }
    }

    @Test
    fun speed_steadySession_returnsRate() {
        val calc = ChargeSpeedCalculator()
        steady(calc)
        val speed = calc.speedPctPerMin(10 * min)!!
        assertTrue("ожидали ~1.0, получили $speed", abs(speed - 1f) < 0.05f)
    }

    @Test
    fun speed_outlierSpike_rejected() {
        val calc = ChargeSpeedCalculator()
        steady(calc)
        // Артефакт: +5% за 10 секунд в конце ровной сессии.
        calc.addSample(10 * min + 10_000L, 55)
        val speed = calc.speedPctPerMin(10 * min + 10_000L)!!
        assertTrue("выброс должен отбрасываться, получили $speed", speed < 1.5f)
    }

    @Test
    fun speed_tooEarly_returnsNull() {
        val calc = ChargeSpeedCalculator()
        calc.addSample(0L, 40)
        assertNull(calc.speedPctPerMin(5_000L))

        // Две точки с шагом меньше тика опроса — тоже мало.
        calc.addSample(5_000L, 41)
        assertNull(calc.speedPctPerMin(5_000L))
    }

    @Test
    fun speed_twoPointsOverTenSeconds_returnsRate() {
        val calc = ChargeSpeedCalculator()
        calc.addSample(0L, 40)
        calc.addSample(15_000L, 41)
        val speed = calc.speedPctPerMin(15_000L)!!
        assertTrue("ожидали ~4.0, получили $speed", abs(speed - 4f) < 0.05f)
    }

    @Test
    fun speed_levelFalling_returnsNull() {
        val calc = ChargeSpeedCalculator()
        for (i in 0..5) {
            calc.addSample(i * min, 60 - i)
        }
        assertNull(calc.speedPctPerMin(5 * min))
    }

    @Test
    fun speed_oldSamples_evictedByWindow() {
        val calc = ChargeSpeedCalculator()
        steady(calc, startLevel = 10)
        // Через 30 минут от старых точек не должно остаться следа.
        calc.addSample(30 * min, 45)
        calc.addSample(31 * min, 46)
        val speed = calc.speedPctPerMin(31 * min)!!
        assertTrue("окно должно отсекать старое, получили $speed", abs(speed - 1f) < 0.05f)
    }

    @Test
    fun forecast_collecting_whileNoSpeed() {
        val f = ChargeForecaster.forecast(
            levelPct = 50,
            speedPctPerMin = null,
            sessionAgeMs = 5 * min,
            isFull = false,
            isCharging = true
        )
        assertEquals(ForecastAccuracy.COLLECTING, f.accuracy)
        assertNull(f.remainingSec)
        assertNull(f.speedPctPerMin)
    }

    @Test
    fun forecast_firstSeconds_showRoughForecast() {
        // Цифры с первой валидной опоры (обычно 1-3 сек): грубое
        // округление сглаживает раннюю неточность.
        val f = ChargeForecaster.forecast(
            levelPct = 50,
            speedPctPerMin = 2f,
            sessionAgeMs = 5_000L,
            isFull = false,
            isCharging = true
        )
        assertEquals(ForecastAccuracy.APPROXIMATE, f.accuracy)
        assertEquals(2797L, f.remainingSec)
        assertEquals(2f, f.speedPctPerMin!!, 1e-6f)
    }

    @Test
    fun forecast_afterMeasuringTier_showsApproximate() {
        val f = ChargeForecaster.forecast(
            levelPct = 50,
            speedPctPerMin = 2f,
            sessionAgeMs = 25_000L,
            isFull = false,
            isCharging = true
        )
        assertEquals(ForecastAccuracy.APPROXIMATE, f.accuracy)
        // Интеграл: 25%/2 линейных + будущие корзины 80-100 = 46.6 мин.
        assertEquals(2797L, f.remainingSec)
    }

    @Test
    fun forecast_linearSection_extrapolates() {
        val f = ChargeForecaster.forecast(
            levelPct = 50,
            speedPctPerMin = 2f,
            sessionAgeMs = 5 * min,
            isFull = false,
            isCharging = true
        )
        assertEquals(ForecastAccuracy.APPROXIMATE, f.accuracy)
        // Интеграл: линейные 25%/2 + будущие корзины 80-100 = 46.6 мин —
        // прогноз честно учитывает замедление впереди.
        assertEquals(2797L, f.remainingSec)
    }

    @Test
    fun forecast_taperSection_slowsDown() {
        // На доводке измеренная скорость замедлена физикой (счётчик/ток):
        // 0.5 %/мин на 90% = линейная опора 1.667 × корзина 0.3.
        // Интеграл по корзинам: 5/0.5 + 5/0.333 = 25 мин = 1500 с.
        val f = ChargeForecaster.forecast(
            levelPct = 90,
            speedPctPerMin = 0.5f,
            sessionAgeMs = 12 * min,
            isFull = false,
            isCharging = true,
            estimatesAgreed = true
        )
        assertEquals(ForecastAccuracy.PRECISE, f.accuracy)
        assertEquals(1500L, f.remainingSec)
    }

    @Test
    fun forecast_matureWithoutAgreement_staysApproximate() {
        // «Точно» только при согласии спидометров: старше 10 минут,
        // но спидометры разъехались — остаёмся «примерно».
        val f = ChargeForecaster.forecast(
            levelPct = 50,
            speedPctPerMin = 1f,
            sessionAgeMs = 12 * min,
            isFull = false,
            isCharging = true,
            estimatesAgreed = false
        )
        assertEquals(ForecastAccuracy.APPROXIMATE, f.accuracy)
    }

    @Test
    fun forecast_divergence_refining() {
        // Расхождение S1/S2 больше 40% — «уточняю» до схождения.
        val f = ChargeForecaster.forecast(
            levelPct = 50,
            speedPctPerMin = 1f,
            sessionAgeMs = 5 * min,
            isFull = false,
            isCharging = true,
            refining = true
        )
        assertEquals(ForecastAccuracy.REFINING, f.accuracy)
        // Интеграл: 30 линейных + 63.3 корзин = 93.3 мин = 5595 с.
        assertEquals(5595L, f.remainingSec)
    }

    @Test
    fun forecast_counterRemaining_ignoresLevelStall() {
        // Счётчик — непрерывный остаток: уровень стоит на 48% (шаг 1%),
        // счётчик говорит 50% до полного — цифры идут, не ждут уровня.
        // Счётчик-путь: 32 линейных + 18% по корзинам (9.1+12.5+16.7+15)
        // = 85.3 мин = 5115 с; уровень-путь (52%) дал бы 95.3 — пути различимы.
        val f = ChargeForecaster.forecast(
            levelPct = 48,
            speedPctPerMin = 1f,
            sessionAgeMs = 5 * min,
            isFull = false,
            isCharging = true,
            remainingEnergyUah = 2_000_000L,
            capacityUah = 4_000_000L
        )
        assertEquals(5115L, f.remainingSec)
    }

    @Test
    fun forecast_taperInterpolation_smoothAcrossEighty() {
        // Реальное поведение: измеренная скорость падает по корзинам
        // (та же кривая, что в интеграле) — остаток меняется плавно
        // через 80%, без ступеньки ×2.
        val levels = listOf(78, 79, 80, 81, 83, 85, 88, 90, 95)
        var prev: Long? = null
        levels.forEach { level ->
            val measuredSpeed = when {
                level < 80 -> 1f
                level < 85 -> 0.55f
                level < 90 -> 0.40f
                level < 95 -> 0.30f
                else -> 0.20f
            }
            val f = ChargeForecaster.forecast(
                levelPct = level,
                speedPctPerMin = measuredSpeed,
                sessionAgeMs = 12 * min,
                isFull = false,
                isCharging = true,
                estimatesAgreed = true
            )
            val remaining = f.remainingSec!!
            if (prev != null) {
                assertTrue(
                    "скачок остатка $prev → $remaining на уровне $level",
                    remaining <= prev!! + 60L
                )
            }
            prev = remaining
        }
        // На 80% скорость ещё линейная опора 1.0, измеренная — 0.55
        // (первая корзина): интеграл = 63.26 мин = 3795 с.
        val at80 = ChargeForecaster.forecast(
            levelPct = 80,
            speedPctPerMin = 0.55f,
            sessionAgeMs = 12 * min,
            isFull = false,
            isCharging = true,
            estimatesAgreed = true
        )
        assertEquals(3795L, at80.remainingSec)
    }

    @Test
    fun forecast_rampGarbage_hiddenBelowEighty() {
        // 0.01 %/мин на 60% — мусор рампы («1000 часов»), цифр нет.
        val f = ChargeForecaster.forecast(
            levelPct = 60,
            speedPctPerMin = 0.01f,
            sessionAgeMs = 12 * min,
            isFull = false,
            isCharging = true
        )
        assertEquals(ForecastAccuracy.COLLECTING, f.accuracy)
        assertNull(f.remainingSec)
    }

    @Test
    fun forecast_slowTrickle_shownNearFull() {
        // Та же скорость на 90% — настоящая доводка, показываем:
        // базовая опора 0.033, корзины 90-95 и 95-100 → 500 + 750 = 1250 мин.
        val f = ChargeForecaster.forecast(
            levelPct = 90,
            speedPctPerMin = 0.01f,
            sessionAgeMs = 12 * min,
            isFull = false,
            isCharging = true
        )
        assertEquals(75000L, f.remainingSec)
    }

    @Test
    fun forecast_full_showsCharged() {
        val f = ChargeForecaster.forecast(
            levelPct = 100,
            speedPctPerMin = null,
            sessionAgeMs = 30 * min,
            isFull = true,
            isCharging = false
        )
        assertTrue(f.isFull)
        assertEquals(0L, f.remainingSec)
    }

    @Test
    fun forecast_notCharging_noRemaining() {
        val f = ChargeForecaster.forecast(
            levelPct = 50,
            speedPctPerMin = 1f,
            sessionAgeMs = 15 * min,
            isFull = false,
            isCharging = false
        )
        assertNull(f.remainingSec)
    }

    // ---------- Регрессия «25 мин → 1 ч 25 мин» через движок ----------

    private fun snapshot(
        tMs: Long,
        level: Int,
        currentMa: Float?,
        counterUah: Long?,
        charging: Boolean = true,
        plugged: Boolean = true,
        full: Boolean = false
    ) = BatterySnapshot(
        levelPct = level,
        isPlugged = plugged,
        isCharging = charging,
        isFull = full,
        chargeType = ChargeType.AC,
        temperatureC = null,
        voltageV = null,
        currentMa = currentMa,
        chargeCounterUah = counterUah,
        timestampMs = tMs
    )

    @Test
    fun regression_rampCurrentAndPrior_displayedNeverExplodes() {
        // Синтетика симптома «25 мин → 1 ч 25 мин»: приор прошлой сессии
        // 2.0 %/мин, ток с рампой 0.2→2.4 А, базовая скорость ровная
        // 1 %/мин. Физика согласованная: в доводке (80%+) ток и уровень
        // замедляются тем же множителем, что применяет прогноз.
        val capacity = 4_000_000L
        val cache = SessionCache(FakeSharedPreferences())
        cache.saveSummary(
            SessionSummary(0L, 0L, 0, avgCcSpeedPctPerMin = 2.0f, peakCurrentMa = null, chargeType = ChargeType.AC)
        )
        val engine = SessionEngine(cache) { capacity }
        val stepMs = 1_000L
        var remPct = 60.0 // остаток до 100%, процентов
        var prev: Long? = null
        var t = 0L
        while (t <= 50 * 60_000L && remPct > 1.0) {
            val level = (100.0 - remPct).toInt().coerceIn(0, 99)
            // Физика доводки — та же корзинная кривая, что в TaperCurve.
            val taper = when {
                level < 80 -> 1f
                level < 85 -> 0.55f
                level < 90 -> 0.40f
                level < 95 -> 0.30f
                else -> 0.20f
            }
            val counterUah = (capacity * (100.0 - remPct) / 100.0).toLong()
            val currentMa = if (t < 60_000L) {
                (200_000L + t * 2_200_000L / 60_000L) / 1000f
            } else {
                2_400f * taper
            }
            val state = engine.update(
                snapshot(
                    t, level, currentMa, counterUah,
                    charging = t >= 30_000L // рампа: CHARGING запаздывает
                ),
                t
            )
            // Секунда ноль от кабеля: рампа не сдвигает возраст сессии.
            assertEquals(t, state.sessionAgeMs)
            val displayed = state.forecast.remainingSec
            val previous = prev
            if (displayed != null && previous != null && t > 90_000L) {
                // Спека приёмки: пересмотры вверх допустимы, но ≤25%
                // (границы корзин дают переходные выбросы нормализации).
                assertTrue(
                    "скачок показанного остатка $previous → $displayed на ${t / 1000} сек",
                    displayed <= previous * 1.25f + 60f
                )
            }
            if (displayed != null) prev = displayed
            // Физика: остаток тает со скоростью 1 %/мин · множитель доводки.
            remPct -= taper * stepMs / 60_000.0
            t += stepMs
        }
    }
}
