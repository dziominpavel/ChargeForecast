package com.example.chargeforecast

import com.example.chargeforecast.diag.DiagRing
import org.junit.Assert.assertEquals
import org.junit.Test

// Кольцо журнала: вытесняется самое старое, порядок сохраняется.
class DiagRingTest {

    @Test
    fun overflowKeepsNewestAndOrder() {
        val ring = DiagRing(capacity = 3)
        repeat(5) { ring.add("line-" + it) }

        assertEquals(3, ring.size())
        assertEquals(listOf("line-2", "line-3", "line-4"), ring.lines())
    }

    @Test
    fun underCapacityKeepsEverything() {
        val ring = DiagRing(capacity = 10)
        ring.add("a")
        ring.add("b")

        assertEquals(listOf("a", "b"), ring.lines())
    }

    @Test
    fun clearEmptiesRing() {
        val ring = DiagRing(capacity = 2)
        ring.add("a")
        ring.clear()

        assertEquals(0, ring.size())
        assertEquals(emptyList<String>(), ring.lines())
    }
}