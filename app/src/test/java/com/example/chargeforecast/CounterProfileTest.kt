package com.example.chargeforecast

import com.example.chargeforecast.forecast.CounterMode
import com.example.chargeforecast.forecast.CounterProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Профиль квантования счётчика (design D2): шаг — НОД разностей,
// отставание — медиана «уровень − счётчик×100/ёмкость». Грубый режим —
// безопасный по умолчанию; точный только после подтверждения.
class CounterProfileTest {

    private val capacity = 4_097_000L

    @Test
    fun coarseCounter_isDetectedFromOnePercentSteps() {
        val profile = CounterProfile()
        // Значения из реального лога MNA-LX9: шаг ровно ёмкость/100,
        // уровень опережает счётчик на 1%.
        val counters = listOf(
            1_515_890L, 1_556_860L, 1_597_830L, 1_638_800L, 1_679_770L, 1_720_740L
        )
        var mode = CounterMode.COARSE
        counters.forEachIndexed { index, counter ->
            mode = profile.observe(counter, 38 + index, capacity, index * 1_000L)
        }
        assertEquals(CounterMode.COARSE, mode)
        assertTrue(profile.isConfirmed)
        assertEquals(40_970L, profile.stepUah)
        assertEquals("confirmed", profile.lastChange!!.reason)
    }

    @Test
    fun fineCounter_isDetectedWhenStepIsMicroampHours() {
        val profile = CounterProfile()
        // Ровно 50% ёмкости: отставание около нуля, шаг — 1 мкА·ч.
        val base = 2_048_500L
        var mode = CounterMode.COARSE
        for (i in 0..5) {
            mode = profile.observe(base + i, 50, capacity, i * 1_000L)
        }
        assertEquals(CounterMode.FINE, mode)
        assertEquals(1L, profile.stepUah)
        assertEquals("confirmed", profile.lastChange!!.reason)
    }

    @Test
    fun fineStepButLaggingCounter_staysCoarse() {
        val profile = CounterProfile()
        // Шаг мелкий, но счётчик отстаёт от уровня на ~3%.
        var mode = CounterMode.COARSE
        for (i in 0..5) {
            mode = profile.observe(2_000_000L + i, 52, capacity, i * 1_000L)
        }
        assertEquals(CounterMode.COARSE, mode)
    }

    @Test
    fun oneChangeAfterTwoMinutes_confirmsProfile() {
        val profile = CounterProfile()
        profile.observe(2_048_500L, 50, capacity, 0L)
        val mode = profile.observe(2_048_501L, 50, capacity, 120_000L)
        assertEquals(CounterMode.FINE, mode)
        assertTrue(profile.isConfirmed)
    }

    @Test
    fun noObservations_defaultsToCoarse() {
        val profile = CounterProfile()
        assertEquals(
            CounterMode.COARSE,
            profile.observe(null, 50, capacity, 0L)
        )
        assertFalse(profile.isConfirmed)
    }

    @Test
    fun reset_clearsProfileAndKeepsInitialMode() {
        val profile = CounterProfile()
        profile.observe(2_048_500L, 50, capacity, 0L)
        profile.reset(CounterMode.FINE)
        assertEquals(CounterMode.FINE, profile.currentMode)
        assertFalse(profile.isConfirmed)
        assertNull(profile.stepUah)
        assertNull(profile.lastChange)
    }
}
