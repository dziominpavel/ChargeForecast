package com.example.chargeforecast

import com.example.chargeforecast.R
import com.example.chargeforecast.battery.BatteryDataQuality
import com.example.chargeforecast.battery.SensorAvailability
import com.example.chargeforecast.ui.SensorFallbackText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorFallbackTextTest {

    @Test
    fun unknownQuality_hasNoExplanation() {
        assertNull(SensorFallbackText.messageRes(BatteryDataQuality.UNKNOWN, whileCharging = true))
        assertNull(SensorFallbackText.messageRes(BatteryDataQuality.UNKNOWN, whileCharging = false))
    }

    @Test
    fun charging_explainsCurrentCounterOrBoth() {
        val currentMissing = BatteryDataQuality(
            currentSensor = SensorAvailability.MISSING,
            counterSensor = SensorAvailability.AVAILABLE
        )
        val counterMissing = BatteryDataQuality(
            currentSensor = SensorAvailability.AVAILABLE,
            counterSensor = SensorAvailability.MISSING
        )
        val bothMissing = BatteryDataQuality(
            currentSensor = SensorAvailability.MISSING,
            counterSensor = SensorAvailability.MISSING
        )

        assertEquals(
            R.string.session_sensor_fallback_current,
            SensorFallbackText.messageRes(currentMissing, whileCharging = true)
        )
        assertEquals(
            R.string.session_sensor_fallback_counter,
            SensorFallbackText.messageRes(counterMissing, whileCharging = true)
        )
        assertEquals(
            R.string.session_sensor_fallback_both,
            SensorFallbackText.messageRes(bothMissing, whileCharging = true)
        )
    }

    @Test
    fun discharge_explainsOnlyCounterFallback() {
        val counterMissing = BatteryDataQuality(
            currentSensor = SensorAvailability.AVAILABLE,
            counterSensor = SensorAvailability.MISSING
        )
        val currentMissing = BatteryDataQuality(
            currentSensor = SensorAvailability.MISSING,
            counterSensor = SensorAvailability.AVAILABLE
        )

        assertEquals(
            R.string.session_sensor_fallback_discharge,
            SensorFallbackText.messageRes(counterMissing, whileCharging = false)
        )
        assertNull(SensorFallbackText.messageRes(currentMissing, whileCharging = false))
    }
}
