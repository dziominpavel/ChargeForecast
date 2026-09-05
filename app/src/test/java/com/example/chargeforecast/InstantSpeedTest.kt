package com.example.chargeforecast

import com.example.chargeforecast.forecast.InstantSpeedEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// Ёмкость из счётчика и мгновенная скорость из тока.
class InstantSpeedTest {

    @Test
    fun capacity_derivedFromCounterAndLevel() {
        // Остаток 2000 мА·ч при 50% → ёмкость 4000 мА·ч.
        assertEquals(4_000_000L, InstantSpeedEstimator.estimateCapacityUah(2_000_000L, 50))
        assertEquals(4_000_000L, InstantSpeedEstimator.estimateCapacityUah(3_600_000L, 90))
    }

    @Test
    fun capacity_missingData_returnsNull() {
        assertNull(InstantSpeedEstimator.estimateCapacityUah(null, 50))
        assertNull(InstantSpeedEstimator.estimateCapacityUah(0L, 50))
        assertNull(InstantSpeedEstimator.estimateCapacityUah(-5L, 50))
        assertNull(InstantSpeedEstimator.estimateCapacityUah(2_000_000L, 0))
        assertNull(InstantSpeedEstimator.estimateCapacityUah(2_000_000L, 101))
    }

    @Test
    fun instantSpeed_fromCurrentAndCapacity() {
        // 2 А при ёмкости 4000 мА·ч → ~0.833 %/мин.
        val speed = InstantSpeedEstimator.instantSpeedPctPerMin(2_000_000L, 4_000_000L)!!
        assertTrue(abs(speed - 0.833f) < 0.01f)
    }

    @Test
    fun emaCurrent_firstSample_returnsCurrent() {
        assertEquals(
            2_000_000L,
            InstantSpeedEstimator.updateEmaCurrentUa(null, 2_000_000L, 10_000L)
        )
    }

    @Test
    fun emaCurrent_dip_pulledDownGradually() {
        // Пик держал бы 3.0 млн навсегда; EMA после падения плавно снижается.
        val afterDip = InstantSpeedEstimator.updateEmaCurrentUa(3_000_000L, 1_000_000L, 10_000L)
        assertTrue(
            "EMA должна лечь между 1.0 и 3.0 млн, получили $afterDip",
            afterDip!! in 1_100_000L..2_900_000L
        )
        val later = InstantSpeedEstimator.updateEmaCurrentUa(afterDip, 1_000_000L, 30_000L)
        assertTrue("через 30 сек EMA почти у 1.0 млн: $later", later!! < 1_600_000L)
    }

    @Test
    fun emaCurrent_spike_doesNotJumpToPeak() {
        // Один выброс драйвера лишь приподнимает EMA, а не ставит её на пик.
        val ema = InstantSpeedEstimator.updateEmaCurrentUa(2_000_000L, 6_000_000L, 10_000L)
        assertTrue("EMA после выброса должна быть < 4.0 млн: $ema", ema!! < 4_000_000L)
    }

    @Test
    fun emaCurrent_garbageKeepsPrevious() {
        assertEquals(
            2_000_000L,
            InstantSpeedEstimator.updateEmaCurrentUa(2_000_000L, null, 10_000L)
        )
        assertEquals(
            2_000_000L,
            InstantSpeedEstimator.updateEmaCurrentUa(2_000_000L, 0L, 10_000L)
        )
        assertEquals(
            2_000_000L,
            InstantSpeedEstimator.updateEmaCurrentUa(2_000_000L, -50L, 10_000L)
        )
    }

    @Test
    fun emaCurrent_ramp_convergesToSteadyCurrent() {
        // Регрессия «рампа 0.2 → 2.4 А за 60 сек»: EMA сходитcя к ровному
        // току и не запоминает рампу как пик.
        var ema: Long? = null
        for (t in 0..5) {
            ema = InstantSpeedEstimator.updateEmaCurrentUa(
                ema, 200_000L + t * 2_200_000L / 5, 10_000L
            )
        }
        repeat(40) {
            ema = InstantSpeedEstimator.updateEmaCurrentUa(ema, 2_400_000L, 10_000L)
        }
        assertTrue(
            "EMA должна сойтись к 2.4 млн ±10%, получили $ema",
            abs(ema!! - 2_400_000L) < 240_000L
        )
    }

    @Test
    fun selectInstant_rampGarbage_shadowsCacheNoMore() {
        val prior = 2.0f
        // Мусор рампы не затеняет приор — regression на «вечное collecting».
        assertEquals(
            2.0f,
            InstantSpeedEstimator.selectInstantSpeed(0.001f, prior)!!
        )
        // Доверенный живой выше приора — берём его.
        assertEquals(
            2.5f,
            InstantSpeedEstimator.selectInstantSpeed(2.5f, prior)!!
        )
        // Доверенный живой ниже приора — на разгоне верим приору.
        assertEquals(
            2.0f,
            InstantSpeedEstimator.selectInstantSpeed(1.5f, prior)!!
        )
        assertEquals(2.0f, InstantSpeedEstimator.selectInstantSpeed(null, prior)!!)
        assertNull(InstantSpeedEstimator.selectInstantSpeed(null, null))
        assertNull(InstantSpeedEstimator.selectInstantSpeed(0.01f, null))
    }

    @Test
    fun instantSpeed_missingData_returnsNull() {
        assertNull(InstantSpeedEstimator.instantSpeedPctPerMin(null, 4_000_000L))
        assertNull(InstantSpeedEstimator.instantSpeedPctPerMin(0L, 4_000_000L))
        assertNull(InstantSpeedEstimator.instantSpeedPctPerMin(-100L, 4_000_000L))
        assertNull(InstantSpeedEstimator.instantSpeedPctPerMin(2_000_000L, null))
        assertNull(InstantSpeedEstimator.instantSpeedPctPerMin(2_000_000L, 0L))
    }
}
