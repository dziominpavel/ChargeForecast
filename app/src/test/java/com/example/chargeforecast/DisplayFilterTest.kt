package com.example.chargeforecast

import com.example.chargeforecast.forecast.DisplayFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// Фильтр показа: каждый тик — новое значение в секундах (без корзин),
// сглаживание EMA tau=10 с, защита только от мусорных всплесков вверх.
class DisplayFilterTest {

    @Test
    fun firstDisplay_roundsRawToSeconds() {
        val filter = DisplayFilter()
        // Первое значение принимается как есть, округлённо до секунд.
        assertEquals(2967L, filter.update(2967.4f, 0L))
        assertEquals(0L, filter.update(0.4f, 0L))
    }

    @Test
    fun downFree_smallUpwardNoiseDoesNotStick() {
        // Вниз — свободно, каждый тик.
        val filter = DisplayFilter()
        assertEquals(2967L, filter.update(2967.4f, 0L))
        val down = filter.update(2900.0f, 1_000L)
        assertTrue("вниз должно идти свободно: $down", down!! < 2967L)
        // Небольшой шум вверх гаснет: показанное не растёт.
        val noisy = filter.update(3200.0f, 2_000L)
        assertTrue("шум вверх просочился: $noisy", noisy!! <= down!!)
    }

    @Test
    fun property_constantTrueSpeed_displayedNeverRisesAfterNinetySeconds() {
        // Истинная скорость 1 %/мин с уровня 40%: сырой остаток = 3600 - t
        // секунд с шумом ±5%; опрос раз в секунду. Показанный остаток
        // после 90-й секунды не возрастает (всплески гасятся EMA tau=10с,
        // вверх — только устойчивый пересмотр >25% за 3 тика).
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
    fun ratchet_modelRevisionAfterStableTicks_accepted() {
        // Устойчивый пересмотр >25% три тика подряд — вверх можно.
        val filter = DisplayFilter()
        assertEquals(1200L, filter.update(1200.0f, 0L))
        // Тот же момент времени: EMA равна сырому значению, кандидат
        // стабилен и выше порога +25% — на третьем тике принимаем.
        var out = 0L
        repeat(3) {
            out = filter.update(2000.0f, 0L)!!
        }
        assertEquals(2000L, out)
    }

    @Test
    fun displayedDecaysWithTime_evenWhenNoNewData() {
        // Данные кончились (null) — показанное продолжает убывать.
        val filter = DisplayFilter()
        assertEquals(1200L, filter.update(1200.0f, 0L))
        val after = filter.update(null, 120_000L)!!
        assertTrue("показанное должно убывать со временем: $after", after < 1200L)
    }
}
