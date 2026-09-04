package com.example.chargeforecast

import com.example.chargeforecast.forecast.ChargeForecaster
import com.example.chargeforecast.forecast.ChargeSpeedCalculator
import com.example.chargeforecast.forecast.ForecastAccuracy
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
            measuredSpeedPctPerMin = null,
            instantSpeedPctPerMin = null,
            sessionAgeMs = 5 * min,
            isFull = false,
            isCharging = true
        )
        assertEquals(ForecastAccuracy.COLLECTING, f.accuracy)
        assertNull(f.remainingMin)
        assertNull(f.speedPctPerMin)
    }

    @Test
    fun forecast_earlyAgeWithSpeed_showsApproximate() {
        // Было: жёсткие 3 минуты молчания. Стало: есть скорость — есть цифры.
        val f = ChargeForecaster.forecast(
            levelPct = 54,
            measuredSpeedPctPerMin = 2f,
            instantSpeedPctPerMin = null,
            sessionAgeMs = 2 * min,
            isFull = false,
            isCharging = true
        )
        assertEquals(ForecastAccuracy.APPROXIMATE, f.accuracy)
        assertEquals(23L, f.remainingMin)
    }

    @Test
    fun forecast_linearSection_extrapolates() {
        val f = ChargeForecaster.forecast(
            levelPct = 50,
            measuredSpeedPctPerMin = 2f,
            instantSpeedPctPerMin = null,
            sessionAgeMs = 5 * min,
            isFull = false,
            isCharging = true
        )
        assertEquals(ForecastAccuracy.APPROXIMATE, f.accuracy)
        assertEquals(25L, f.remainingMin)
    }

    @Test
    fun forecast_taperSection_slowsDown() {
        val f = ChargeForecaster.forecast(
            levelPct = 90,
            measuredSpeedPctPerMin = 1f,
            instantSpeedPctPerMin = null,
            sessionAgeMs = 12 * min,
            isFull = false,
            isCharging = true
        )
        assertEquals(ForecastAccuracy.PRECISE, f.accuracy)
        // Линейно было бы 10 мин, с доводкой — вдвое больше, не меньше.
        assertEquals(20L, f.remainingMin)
    }

    @Test
    fun forecast_instantOnly_showsApproximateImmediately() {
        // Первые секунды: измеренной скорости нет, но ток со счётчиком есть.
        val f = ChargeForecaster.forecast(
            levelPct = 50,
            measuredSpeedPctPerMin = null,
            instantSpeedPctPerMin = 0.833f,
            sessionAgeMs = 5_000L,
            isFull = false,
            isCharging = true
        )
        assertEquals(ForecastAccuracy.APPROXIMATE, f.accuracy)
        assertEquals(60L, f.remainingMin)
    }

    @Test
    fun forecast_blend_shiftsWeightWithAge() {
        // Возраст 0: только instant (2.0) → 25 мин.
        val fresh = ChargeForecaster.forecast(
            levelPct = 50,
            measuredSpeedPctPerMin = 1f,
            instantSpeedPctPerMin = 2f,
            sessionAgeMs = 0L,
            isFull = false,
            isCharging = true
        )
        assertEquals(25L, fresh.remainingMin)
        assertEquals(ForecastAccuracy.APPROXIMATE, fresh.accuracy)

        // 5 минут: пополам (1.5) → 33 мин.
        val mid = ChargeForecaster.forecast(
            levelPct = 50,
            measuredSpeedPctPerMin = 1f,
            instantSpeedPctPerMin = 2f,
            sessionAgeMs = 5 * min,
            isFull = false,
            isCharging = true
        )
        assertEquals(33L, mid.remainingMin)

        // 10+ минут: только замер (1.0) → 50 мин и «точно».
        val mature = ChargeForecaster.forecast(
            levelPct = 50,
            measuredSpeedPctPerMin = 1f,
            instantSpeedPctPerMin = 2f,
            sessionAgeMs = 12 * min,
            isFull = false,
            isCharging = true
        )
        assertEquals(50L, mature.remainingMin)
        assertEquals(ForecastAccuracy.PRECISE, mature.accuracy)
    }

    @Test
    fun forecast_convergence_preciseEarly() {
        // Оценки сошлись (±10%) на 4-й минуте — объективно точно.
        val f = ChargeForecaster.forecast(
            levelPct = 50,
            measuredSpeedPctPerMin = 1f,
            instantSpeedPctPerMin = 1.1f,
            sessionAgeMs = 4 * min,
            isFull = false,
            isCharging = true
        )
        assertEquals(ForecastAccuracy.PRECISE, f.accuracy)
        // Бленд на 4-й минуте: 1.1·0.6 + 1.0·0.4 = 1.06 → 50/1.06 = 47.
        assertEquals(47L, f.remainingMin)
    }

    @Test
    fun forecast_divergence_staysApproximate() {
        // Разъехались вдвое — рано говорить «точно».
        val f = ChargeForecaster.forecast(
            levelPct = 50,
            measuredSpeedPctPerMin = 1f,
            instantSpeedPctPerMin = 2f,
            sessionAgeMs = 4 * min,
            isFull = false,
            isCharging = true
        )
        assertEquals(ForecastAccuracy.APPROXIMATE, f.accuracy)
    }

    @Test
    fun forecast_convergence_tooYoung_staysApproximate() {
        // Сошлись, но сессии меньше 3 минут — ещё рано.
        val f = ChargeForecaster.forecast(
            levelPct = 50,
            measuredSpeedPctPerMin = 1f,
            instantSpeedPctPerMin = 1.05f,
            sessionAgeMs = 60_000L,
            isFull = false,
            isCharging = true
        )
        assertEquals(ForecastAccuracy.APPROXIMATE, f.accuracy)
    }

    @Test
    fun forecast_rampGarbage_hiddenBelowEighty() {
        // 0.01 %/мин на 60% — мусор рампы («1000 часов»), цифр нет.
        val f = ChargeForecaster.forecast(
            levelPct = 60,
            measuredSpeedPctPerMin = 0.01f,
            instantSpeedPctPerMin = 0.01f,
            sessionAgeMs = 12 * min,
            isFull = false,
            isCharging = true
        )
        assertEquals(ForecastAccuracy.COLLECTING, f.accuracy)
        assertNull(f.remainingMin)
    }

    @Test
    fun forecast_slowTrickle_shownNearFull() {
        // Та же скорость на 90% — настоящая доводка, показываем.
        val f = ChargeForecaster.forecast(
            levelPct = 90,
            measuredSpeedPctPerMin = 0.01f,
            instantSpeedPctPerMin = null,
            sessionAgeMs = 12 * min,
            isFull = false,
            isCharging = true
        )
        assertEquals(2000L, f.remainingMin)
    }

    @Test
    fun forecast_full_showsCharged() {
        val f = ChargeForecaster.forecast(
            levelPct = 100,
            measuredSpeedPctPerMin = null,
            instantSpeedPctPerMin = null,
            sessionAgeMs = 30 * min,
            isFull = true,
            isCharging = false
        )
        assertTrue(f.isFull)
        assertEquals(0L, f.remainingMin)
    }

    @Test
    fun forecast_notCharging_noRemaining() {
        val f = ChargeForecaster.forecast(
            levelPct = 50,
            measuredSpeedPctPerMin = 1f,
            instantSpeedPctPerMin = null,
            sessionAgeMs = 15 * min,
            isFull = false,
            isCharging = false
        )
        assertNull(f.remainingMin)
    }
}
