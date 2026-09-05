package com.example.chargeforecast

import com.example.chargeforecast.forecast.TaperCurveLearner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Обучение кривой доводки: наблюдения множителей по корзинам за сессию.
class TaperCurveLearnerTest {

    @Test
    fun accumulates_learnedBinAverage() {
        val learner = TaperCurveLearner(minSamplesPerBin = 3)
        repeat(3) { learner.add(82, 0.55f, 1.0f) }
        learner.add(92, 0.3f, 1.0f)
        val learned = learner.learnedFactors()!!
        assertEquals(0.55f, learned[0], 1e-4f)
        assertTrue(learned[2].isNaN())
    }

    @Test
    fun linearZone_andGarbage_ignored() {
        val learner = TaperCurveLearner(minSamplesPerBin = 1)
        learner.add(50, 1.0f, 1.0f)   // линейная зона — не корзина
        learner.add(82, null, 1.0f)   // нет измерения
        learner.add(82, 0.5f, null)   // нет опоры
        learner.add(82, 0.0f, 1.0f)   // мусор
        assertNull(learner.learnedFactors())
    }

    @Test
    fun minSamples_gate() {
        val learner = TaperCurveLearner(minSamplesPerBin = 3)
        repeat(2) { learner.add(82, 0.6f, 1.0f) }
        assertNull(learner.learnedFactors())
        learner.add(82, 0.6f, 1.0f)
        assertEquals(0.6f, learner.learnedFactors()!![0], 1e-4f)
    }

    @Test
    fun reset_clears() {
        val learner = TaperCurveLearner(minSamplesPerBin = 1)
        learner.add(82, 0.6f, 1.0f)
        learner.reset()
        assertNull(learner.learnedFactors())
    }
}
