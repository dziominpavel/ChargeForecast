package com.example.chargeforecast

import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.battery.sameReading
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Сравнение снимков для тика опроса: метка времени не в счёт.
class BatteryMonitorTest {

    private fun snapshot(
        level: Int = 50,
        currentMa: Float? = 1500f,
        timestampMs: Long = 1_000L
    ) = BatterySnapshot(
        levelPct = level,
        isPlugged = true,
        isCharging = true,
        isFull = false,
        chargeType = ChargeType.AC,
        temperatureC = 28.5f,
        voltageV = 4.1f,
        currentMa = currentMa,
        chargeCounterUah = 2_000_000L,
        timestampMs = timestampMs
    )

    @Test
    fun sameReading_ignoresTimestamp() {
        assertTrue(sameReading(snapshot(timestampMs = 1_000L), snapshot(timestampMs = 2_000L)))
    }

    @Test
    fun sameReading_detectsLevelChange() {
        assertFalse(sameReading(snapshot(level = 50), snapshot(level = 51)))
    }

    @Test
    fun sameReading_detectsCurrentDrift() {
        assertFalse(sameReading(snapshot(currentMa = 1500f), snapshot(currentMa = 1600f)))
    }

    @Test
    fun sameReading_detectsUnplug() {
        val unplugged = snapshot().copy(isPlugged = false, isCharging = false)
        assertFalse(sameReading(snapshot(), unplugged))
    }
}
