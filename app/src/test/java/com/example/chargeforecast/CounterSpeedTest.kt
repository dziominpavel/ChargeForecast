package com.example.chargeforecast

import com.example.chargeforecast.forecast.CapacityTracker
import com.example.chargeforecast.forecast.CounterSpeedometer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// Счётчик-спидометр и живой трекер ёмкости.
class CounterSpeedTest {

    private val min = 60_000L
    private val capacity = 4_000_000L

    @Test
    fun counterSpeed_steadyClimb_returnsRate() {
        val speedo = CounterSpeedometer()
        speedo.addSample(0L, 2_000_000L)
        speedo.addSample(30_000L, 2_016_666L)
        speedo.addSample(60_000L, 2_033_332L)
        val speed = speedo.speedPctPerMin(60_000L, capacity)!!
        assertTrue("ожидали ~0.833, получили $speed", abs(speed - 0.833f) < 0.01f)
    }

    @Test
    fun counterSpeed_tooEarly_returnsNull() {
        val speedo = CounterSpeedometer()
        // Одна точка — не скорость.
        speedo.addSample(0L, 2_000_000L)
        assertNull(speedo.speedPctPerMin(5_000L, capacity))
    }

    @Test
    fun counterSpeed_twoPointsAfterTwoSeconds_returnsRate() {
        // Цифры с первых секунд: две точки с интервалом ≥2 сек — уже скорость.
        val speedo = CounterSpeedometer()
        speedo.addSample(0L, 2_000_000L)
        speedo.addSample(5_000L, 2_002_777L)
        val speed = speedo.speedPctPerMin(5_000L, capacity)!!
        assertTrue("ожидали ~0.833, получили $speed", abs(speed - 0.833f) < 0.01f)
    }

    @Test
    fun counterSpeed_secondTick_returnsRate() {
        // Второй тик опроса (10 сек) — уже скорость, пусть шумная.
        val speedo = CounterSpeedometer()
        speedo.addSample(0L, 2_000_000L)
        speedo.addSample(10_000L, 2_005_555L)
        val speed = speedo.speedPctPerMin(10_000L, capacity)!!
        assertTrue("ожидали ~0.833, получили $speed", abs(speed - 0.833f) < 0.01f)
    }

    @Test
    fun counterSpeed_fallingOrStill_returnsNull() {
        val falling = CounterSpeedometer()
        falling.addSample(0L, 2_000_000L)
        falling.addSample(min, 1_999_000L)
        assertNull(falling.speedPctPerMin(min, capacity))

        assertNull(CounterSpeedometer().speedPctPerMin(min, null))
        assertNull(CounterSpeedometer().speedPctPerMin(min, 0L))
    }

    @Test
    fun counterSpeed_lsqNoise_resistantVersusEndPoints() {
        // Регрессия МНК: линейный счётчик с шумом ±10% — наклон МНК
        // ближе к истине, чем дельта двух концевых точек.
        // Идеал: +40k мкА·ч в минуту (1%/мин при ёмкости 4 млн), шаг 15 сек.
        // Счётчик при зарядке растёт — отрицательный наклон это разряд.
        val noise = mapOf(
            0 to -3_000L, 1 to 2_500L, 2 to -1_500L, 3 to 3_500L,
            4 to -2_000L, 5 to 1_000L, 6 to -3_500L, 7 to 2_000L
        )
        val lsq = CounterSpeedometer()
        var firstValue = 0L
        var lastValue = 0L
        for (i in 0..8) {
            val value = 2_000_000L + i * 10_000L + (noise[i] ?: 0L)
            if (i == 0) firstValue = value
            if (i == 8) lastValue = value
            lsq.addSample(i * 15_000L, value)
        }
        val lsqSpeed = lsq.speedPctPerMin(120_000L, capacity)!!
        val endPointSpeed =
            (lastValue - firstValue) * 60_000f / 120_000f / capacity * 100f
        assertTrue(
            "МНК ($lsqSpeed) должен быть ближе к 1.0, чем концевые точки ($endPointSpeed)",
            abs(lsqSpeed - 1f) < abs(endPointSpeed - 1f)
        )
    }

    @Test
    fun counterSpeed_shortWindow_availableAtThirtySeconds() {
        // Короткое окно S1(30с): три сэмпла по 10 сек — уже скорость.
        val short = CounterSpeedometer(windowMs = CounterSpeedometer.WINDOW_SHORT_MS)
        short.addSample(0L, 2_000_000L)
        short.addSample(10_000L, 2_006_667L)
        short.addSample(20_000L, 2_013_334L)
        val speed = short.speedPctPerMin(20_000L, capacity)!!
        assertTrue("ожидали ~1.0, получили $speed", kotlin.math.abs(speed - 1f) < 0.02f)
        // Окно эвиктит: сэмпл старше 30 сек не влияет.
        short.addSample(45_000L, 2_030_000L)
        val evolved = short.speedPctPerMin(45_000L, capacity)!!
        assertTrue("ожидали ~1.0 после эвикции, получили $evolved", kotlin.math.abs(evolved - 1f) < 0.05f)
    }

    @Test
    fun capacityTracker_initialAndRefinement() {
        val tracker = CapacityTracker()
        assertEquals(4_000_000L, tracker.update(2_000_000L, 50))
        // Сдвиг меньше 2% — держим стартовую оценку.
        assertEquals(4_000_000L, tracker.update(2_040_000L, 51))
        // Набрали 2%: Δ80k/2% → рефайн 4.0M, якорь сдвинулся.
        assertEquals(4_000_000L, tracker.update(2_080_000L, 52))
        // Дальше считаем от нового якоря.
        assertEquals(4_000_000L, tracker.update(2_160_000L, 54))
    }

    @Test
    fun capacityTracker_garbageKeptOut() {
        val tracker = CapacityTracker()
        assertEquals(4_000_000L, tracker.update(2_000_000L, 50))
        // Абсурдный скачок счётчика — оценку не ломаем.
        assertEquals(4_000_000L, tracker.update(200_000_000L, 52))
        assertNull(CapacityTracker().update(null, 50))
        assertNull(CapacityTracker().update(2_000_000L, 0))
    }
}
