package com.example.chargeforecast

import com.example.chargeforecast.forecast.DisplayFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// Фильтр показа: якорный отсчёт (показ = якорь − время, между
// коррекциями минус секунда за секунду), сглаживание EMA tau=10 с,
// коррекции якоря — скольжением в обе стороны, вверх только через
// подтверждённый пересмотр (рывок +25%/3 тика или тренд долгого окна).
class DisplayFilterTest {

    @Test
    fun firstDisplay_roundsRawToSeconds() {
        // Первое значение принимается как есть, округлённо до секунд.
        val filter = DisplayFilter()
        assertEquals(2967L, filter.update(2967.4f, 0L))
        // Полная зарядка — ноль мгновенно, без дотухания.
        assertEquals(0L, DisplayFilter().update(0f, 0L))
    }

    @Test
    fun downSlides_notJumps() {
        // Падение модели расходится скольжением, а не прыжком одним кадром
        // (спека-дельта: «вниз — скольжением» вместо прежнего «вниз свободно»).
        val filter = DisplayFilter()
        assertEquals(3600L, filter.update(3600.0f, 0L))
        // Модель обвалилась до 2400 (-33%): первый шаг ограничен долей.
        var prev = 3600L
        var maxStep = 0L
        var t = 1_000L
        while (t <= 120_000L) {
            val out = filter.update(2400.0f, t)!!
            val step = prev - out
            assertTrue("шаг вниз не может быть прыжком: $prev → $out", step <= prev / 4)
            if (step > maxStep) maxStep = step
            prev = out
            t += 1_000L
        }
        assertTrue("скольжение не сошлось к модели: $prev", kotlin.math.abs(prev - 2400L) <= 3L)
    }

    @Test
    fun frozenRaw_displayedTicksDownOneSecondPerSecond() {
        // Сырая оценка застыла на константе (квантование уровня, замерший
        // счётчик) — показанный всё равно убывает ровно на секунду за
        // секунду: это арифметика «якорь минус время», а не пересчёт.
        val filter = DisplayFilter()
        var prev = filter.update(3600.0f, 0L)!!
        var t = 1_000L
        while (t <= 120_000L) {
            val out = filter.update(3600.0f, t)!!
            assertEquals("каждый тик минус ровно секунда", prev - 1, out)
            prev = out
            t += 1_000L
        }
        assertEquals(3480L, prev)
    }

    @Test
    fun downSlide_smallUpwardNoiseDoesNotStick() {
        // Небольшой шум вверх гаснет: показанное не растёт, продолжает
        // свой ход; движение модели вниз — скольжением.
        val filter = DisplayFilter()
        assertEquals(2967L, filter.update(2967.4f, 0L))
        val down = filter.update(2900.0f, 1_000L)
        assertTrue("вниз должен идти скольжением: $down", down!! < 2967L)
        val noisy = filter.update(3200.0f, 2_000L)
        assertTrue("шум вверх просочился: $noisy", noisy!! <= down!!)
    }

    @Test
    fun property_constantTrueSpeed_displayedNeverRisesAfterNinetySeconds() {
        // Истинная скорость 1 %/мин с уровня 40%: сырой остаток = 3600 - t
        // секунд с шумом ±5%; опрос раз в секунду. Показанный остаток
        // после 90-й секунды не возрастает (всплески гасятся EMA tau=10с,
        // вверх — только устойчивый пересмотр >25% за 3 тика).
        // Константа шумового порога зафиксирована здесь и в тесте «антипила»:
        // калибровать только парой, менять = пересмотр обоих сценариев.
        assertEquals(40f, DisplayFilter.TREND_NOISE_FLOOR_SEC)
        val filter = DisplayFilter()
        val random = Random(42)
        var prevOutput: Long? = null
        var t = 0L
        while (t <= 20 * 60_000L) {
            val trueRemaining = 3600f - t / 1000f
            val noisy = trueRemaining * (1f + (random.nextFloat() - 0.5f) * 0.1f)
            val output = filter.update(noisy, t)
            val prev = prevOutput
            if (output != null && prev != null && t > 90_000L) {
                assertTrue(
                    "показанный остаток возрос: $prev → $output на ${t / 1000} сек",
                    output <= prev
                )
            }
            if (output != null) prevOutput = output
            t += 1_000L
        }
    }

    @Test
    fun ratchet_modelRevisionAfterStableTicks_glidesUpInSteps() {
        // Устойчивый пересмотр >25% три тика подряд открывает движение
        // якоря вверх; дальше якорь сходится к модели скольжением —
        // каждый шаг ограничен, финал у модели.
        val filter = DisplayFilter()
        assertEquals(1200L, filter.update(1200.0f, 0L))
        var out = 0L
        var prev = 1200L
        repeat(3) {
            out = filter.update(2000.0f, 0L)!!
        }
        assertTrue("подтверждённый пересмотр должен начать движение вверх", out > prev)
        prev = out
        var t = 1_000L
        while (t <= 60_000L) {
            out = filter.update(2000.0f, t)!!
            assertTrue("рывок вверх: $prev → $out", out <= prev + prev / 4)
            prev = out
            t += 1_000L
        }
        assertTrue("якорь не сошёлся к модели: $prev", kotlin.math.abs(prev - 2000L) <= 3L)
    }

    @Test
    fun displayedDecaysWithTime_evenWhenNoNewData() {
        // Данные кончились (null) — якорь не трогаем, отсчёт продолжает
        // убывать по времени.
        val filter = DisplayFilter()
        assertEquals(1200L, filter.update(1200.0f, 0L))
        val after = filter.update(null, 120_000L)!!
        assertEquals("показанное убывает по секундам: $after", 1080L, after)
    }

    @Test
    fun trendSmoothRevision_displayedRisesInSmallSteps_noSawtooth() {
        // Задача 3.2, сценарий спеки «Плавный переход без пилы»: модель
        // ревидирует остаток вверх ровно со скоростью смеси (~2.7 сек/сек)
        // 180 секунд. До открытия тренда показанный идёт своим отсчётом
        // (минус секунда за секунду — пауза, а не заморозка); после
        // открытия растёт каждым тиком, каждый шаг ≤25%, финал ≈ сырому.
        // Константа шумового порога зафиксирована парой с property-тестом.
        assertEquals(40f, DisplayFilter.TREND_NOISE_FLOOR_SEC)
        val filter = DisplayFilter()
        var prev: Long? = null
        var firstRiseAtMs: Long? = null
        var finalShown: Long? = null
        var t = 0L
        while (t <= 180_000L) {
            val raw = 1500f + 2.7f * t / 1000f
            val output = filter.update(raw, t)
            val previous = prev
            if (output != null && previous != null) {
                if (firstRiseAtMs == null) {
                    // До открытия тренда допустим только отсчёт (−1/тик)
                    // и держание значения, но не падение быстрее секунды.
                    assertTrue(
                        "падение быстрее отсчёта $previous → $output на ${t / 1000}с",
                        output >= previous - 1
                    )
                } else {
                    assertTrue(
                        "ложное падение после открытия тренда " +
                            "$previous → $output на ${t / 1000}с",
                        output >= previous
                    )
                }
                if (output > previous) {
                    if (firstRiseAtMs == null) {
                        firstRiseAtMs = t
                    }
                    assertTrue(
                        "рывок вверх $previous → $output на ${t / 1000}с",
                        output <= previous + previous / 4
                    )
                }
                if (firstRiseAtMs != null) {
                    assertTrue(
                        "после открытия тренда показанный замер " +
                            "на ${t / 1000}с: $previous → $output",
                        output > previous
                    )
                }
            }
            if (output != null) {
                prev = output
                finalShown = output
            }
            t += 1_000L
        }
        val risenAt = firstRiseAtMs
        assertNotNull("тренд не открылся за 180с", risenAt)
        // Окно тренда 60с + 3 тика удержания — пересмотр должен начаться
        // заметно раньше конца сценария.
        assertTrue(
            "тренд открылся слишком поздно: ${risenAt!! / 1000}с",
            risenAt <= 120_000L
        )
        val rawFinal = 1500f + 2.7f * 180f
        val drift = rawFinal - finalShown!!
        assertTrue(
            "показанный $finalShown ушёл от сырого $rawFinal на $drift сек",
            drift in -60f..60f
        )
    }

    @Test
    fun singleSpikeUpDown_trendPathNotOpened_displayedNeverRises() {
        // Задача 3.4: одиночный всплеск ±30% (ровно один тик) при
        // падающей модели не открывает тренд-путь: показанный не растёт
        // ни на одном тике, после всплеска возобновляет убывание.
        assertEquals(40f, DisplayFilter.TREND_NOISE_FLOOR_SEC)
        val filter = DisplayFilter()
        var prev: Long? = null
        var shownJustAfterSpike: Long? = null
        var t = 0L
        while (t <= 150_000L) {
            val base = 1500f - t / 1000f
            val raw = when (t) {
                30_000L -> base * 1.3f // всплеск вверх, один тик
                90_000L -> base * 0.7f // всплеск вниз, один тик
                else -> base
            }
            val output = filter.update(raw, t)
            val previous = prev
            if (output != null && previous != null) {
                assertTrue(
                    "шумовой рост $previous → $output на ${t / 1000}с",
                    output <= previous
                )
            }
            if (output != null) {
                if (t == 31_000L) {
                    shownJustAfterSpike = output
                }
                prev = output
            }
            t += 1_000L
        }
        // После гашения всплеска убывание продолжилось, а не зависло.
        val last = prev!!
        assertTrue(
            "после всплеска убывание не возобновилось: " +
                "$shownJustAfterSpike → $last",
            last < shownJustAfterSpike!!
        )
    }

    @Test
    fun glideConstants_arePinnedForDeviceCalibration() {
        // Константы скольжения закреплены парой с тестами анти-пилы и
        // схождения — калибровать на устройстве только парой.
        assertEquals(3f, DisplayFilter.CORRECTION_ENTER_SEC)
        assertEquals(0.2f, DisplayFilter.SLEW_FRACTION)
        assertEquals(2f, DisplayFilter.SLEW_MIN_STEP_SEC)
    }
}
