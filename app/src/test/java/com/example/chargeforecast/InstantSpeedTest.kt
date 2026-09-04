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
    fun sessionMax_keepsPeakIgnoringDips() {
        assertEquals(2_000_000L, InstantSpeedEstimator.updateSessionMax(null, 2_000_000L))
        assertEquals(
            2_000_000L,
            InstantSpeedEstimator.updateSessionMax(2_000_000L, 1_200_000L)
        )
        assertEquals(
            3_000_000L,
            InstantSpeedEstimator.updateSessionMax(2_000_000L, 3_000_000L)
        )
        // Мусор вместо тока пик не портит.
        assertEquals(2_000_000L, InstantSpeedEstimator.updateSessionMax(2_000_000L, null))
        assertEquals(2_000_000L, InstantSpeedEstimator.updateSessionMax(2_000_000L, 0L))
        assertEquals(2_000_000L, InstantSpeedEstimator.updateSessionMax(2_000_000L, -50L))
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
