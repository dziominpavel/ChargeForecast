package com.example.chargeforecast

import com.example.chargeforecast.session.CcPhaseAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// CC-фаза: рампа и доводка не должны портить среднюю скорость сводки —
// иначе приор следующей сессии занижается отсечением на 85%.
class CcPhaseAccumulatorTest {

    @Test
    fun rampAndTaperExcluded_ccPhaseAveraged() {
        val acc = CcPhaseAccumulator()
        acc.add(0L, 50, 0.2f)        // рампа — не считается
        acc.add(60_000L, 50, 0.3f)   // рампа — не считается
        acc.add(120_000L, 50, 1.0f)  // CC-фаза
        acc.add(300_000L, 60, 1.0f)  // CC-фаза
        acc.add(400_000L, 80, 0.5f)  // доводка — не считается
        acc.add(500_000L, 90, 0.4f)  // доводка — не считается
        assertEquals(1.0f, acc.averagePctPerMin()!!, 1e-6f)
    }

    @Test
    fun emptyOrNullSpeeds_ignored() {
        val acc = CcPhaseAccumulator()
        acc.add(120_000L, 50, null)
        acc.add(120_000L, 50, 0f)
        assertNull(acc.averagePctPerMin())
    }

    @Test
    fun reset_clearsState() {
        val acc = CcPhaseAccumulator()
        acc.add(120_000L, 50, 1.0f)
        acc.reset()
        assertNull(acc.averagePctPerMin())
    }
}
