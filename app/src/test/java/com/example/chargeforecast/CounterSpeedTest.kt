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

    @Test
    fun capacityTracker_inversionUnreachableByOneStep() {
        // Симптом «0 с на 81%»: счётчик 0.81 ёмкости, уточнение вниз
        // (0.80 ёмкости) одним шагом обязано отклониться — иначе ёмкость
        // оказывается меньше заряда в батарее.
        val tracker = CapacityTracker()
        assertEquals(4_030_000L, tracker.update(3_264_300L, 81))
        // Δуровень 2 и Δсчётчик 64 480 дают кандидата 3 224 000 —
        // меньше заряда в батарее 3 328 780: отказ по инварианту.
        assertEquals(4_030_000L, tracker.update(3_328_780L, 83))
        assertEquals("rejected-invariant", tracker.lastChange!!.reason)
        // Шаг вниз больше 12% отклоняется и по эшеллону 3.
        assertEquals(4_030_000L, tracker.update(3_328_780L, 84))
    }

    @Test
    fun capacityTracker_threeRejectionsResetToCurrentReading() {
        // Оценка «уехала»: каждое уточнение расходится с текущей больше
        // чем на 12% — три отказа подряд дают сброс к счётчик·100/уровень.
        val tracker = CapacityTracker()
        assertEquals(4_000_000L, tracker.update(2_000_000L, 50))
        // Кандидат 5.0 млн (+25%) — отказ по шагу, оценка не меняется.
        assertEquals(4_000_000L, tracker.update(2_100_000L, 52))
        assertEquals("rejected-step", tracker.lastChange!!.reason)
        assertEquals(4_000_000L, tracker.update(2_200_000L, 54))
        // Третий отказ — сброс из текущих уровня и счётчика.
        assertEquals(4_107_142L, tracker.update(2_300_000L, 56))
        assertEquals("reset-rejected", tracker.lastChange!!.reason)
        assertEquals(4_000_000L, tracker.lastChange!!.was)
    }

    @Test
    fun capacityTracker_passportCorridorWhenDesignKnown() {
        // Паспортная ёмкость известна: кандидат вне коридора ±15%
        // отклоняется, оценка остаётся прежней.
        val tracker = CapacityTracker()
        assertEquals(4_000_000L, tracker.update(800_000L, 20, 4_030_000L))
        // 3.3 млн — ниже 85% от 4.03 млн (3.4255 млн).
        assertEquals(4_000_000L, tracker.update(866_000L, 22, 4_030_000L))
        assertEquals("rejected-passport", tracker.lastChange!!.reason)
    }

    @Test
    fun capacityTracker_withoutDesignCorridorUsesStepAndSanity() {
        // design = null (устройство без паспортной ёмкости): тот же
        // кандидат отклоняется шагом — 17.5% против допустимых 12%.
        val tracker = CapacityTracker()
        assertEquals(4_000_000L, tracker.update(800_000L, 20))
        assertEquals(4_000_000L, tracker.update(866_000L, 22))
        assertEquals("rejected-step", tracker.lastChange!!.reason)
    }

    @Test
    fun capacityTracker_seededOutsidePassportCorridorClamped() {
        // Начальная оценка вне коридора прижимается к его границе:
        // 640k/20% дал бы 3.2 млн, а нижняя граница ±15% — 3.4 млн.
        val tracker = CapacityTracker()
        assertEquals(3_400_000L, tracker.update(640_000L, 20, 4_000_000L))
    }

    @Test
    fun capacityEvent_hasWasNowAndReason() {
        val tracker = CapacityTracker()
        assertNull(tracker.lastChange)
        tracker.update(2_000_000L, 50)
        assertEquals(null, tracker.lastChange!!.was)
        assertEquals(4_000_000L, tracker.lastChange!!.now)
        assertEquals("init", tracker.lastChange!!.reason)
        // Без шага уровня уточнения нет — и события нет.
        tracker.update(2_010_000L, 50)
        assertNull(tracker.lastChange)
    }
}
