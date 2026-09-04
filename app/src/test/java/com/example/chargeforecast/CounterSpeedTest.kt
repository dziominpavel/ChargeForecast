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
        speedo.addSample(0L, 2_000_000L)
        assertNull(speedo.speedPctPerMin(5_000L, capacity))
        speedo.addSample(5_000L, 2_002_777L)
        assertNull(speedo.speedPctPerMin(5_000L, capacity))
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
