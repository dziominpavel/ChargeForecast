package com.example.chargeforecast

import com.example.chargeforecast.forecast.TaperCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Кривая доводки: корзины 80-100, интеграл остатка, слияние.
class TaperCurveTest {

    @Test
    fun factor_binsAndLinearZone() {
        assertEquals(1f, TaperCurve.factor(50), 1e-6f)
        assertEquals(1f, TaperCurve.factor(79), 1e-6f)
        assertEquals(0.55f, TaperCurve.factor(80), 1e-6f)
        assertEquals(0.55f, TaperCurve.factor(84), 1e-6f)
        assertEquals(0.40f, TaperCurve.factor(85), 1e-6f)
        assertEquals(0.30f, TaperCurve.factor(90), 1e-6f)
        assertEquals(0.20f, TaperCurve.factor(95), 1e-6f)
        assertEquals(1f, TaperCurve.factor(100), 1e-6f)
    }

    @Test
    fun remaining_linearZone_equalsPlainDivision() {
        // Остаток, не доходящий до доводки, — чистое деление.
        assertEquals(10f, TaperCurve.remainingMinutes(20f, 2f, 50), 0.01f)
        assertEquals(0f, TaperCurve.remainingMinutes(0f, 2f, 50), 0.01f)
        // Остаток 50% с уровня 50: 30 линейных + хвост по корзинам
        // (5/0.55 + 5/0.4 + 5/0.3 + 5/0.2)·0.5 = 46.63 мин.
        assertEquals(46.63f, TaperCurve.remainingMinutes(50f, 2f, 50), 0.05f)
    }

    @Test
    fun remaining_continuousAcrossEighty() {
        // На 79% и на 80% интеграл почти одинаков — ступеньки ×2 нет.
        val at79 = TaperCurve.remainingMinutes(21f, 1f, 79)
        val at80 = TaperCurve.remainingMinutes(20f, 1f, 80)
        assertEquals(at79, at80, 1.5f)
    }

    @Test
    fun remaining_taperBins_accumulate() {
        // Уровень 90, линейная опора 1.0: 5/0.3 + 5/0.2 = 41.67 мин.
        assertEquals(41.67f, TaperCurve.remainingMinutes(10f, 1f, 90), 0.05f)
    }

    @Test
    fun merge_skipsUnlearnedBins() {
        val merged = TaperCurve.merge(
            TaperCurve.DEFAULT_FACTORS,
            floatArrayOf(0.8f, Float.NaN, 0.6f, Float.NaN)
        )
        assertEquals(0.5f * 0.55f + 0.5f * 0.8f, merged[0], 1e-5f)
        assertEquals(0.40f, merged[1], 1e-5f)
        assertEquals(0.5f * 0.30f + 0.5f * 0.6f, merged[2], 1e-5f)
        assertEquals(0.20f, merged[3], 1e-5f)
    }

    @Test
    fun merge_garbageLearned_ignored() {
        val merged = TaperCurve.merge(
            TaperCurve.DEFAULT_FACTORS,
            floatArrayOf(-1f, 0f, Float.NaN, 0.2f)
        )
        assertEquals(0.55f, merged[0], 1e-5f)
        assertEquals(0.40f, merged[1], 1e-5f)
        assertEquals(0.20f, merged[3], 1e-5f)
        assertTrue(merged.all { it > 0f })
    }
}
