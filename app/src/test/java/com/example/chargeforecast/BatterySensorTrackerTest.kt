package com.example.chargeforecast

import com.example.chargeforecast.battery.BatteryDataQuality
import com.example.chargeforecast.battery.BatterySensorTracker
import com.example.chargeforecast.battery.SensorAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class BatterySensorTrackerTest {

    @Test
    fun initialQuality_isUnknown() {
        val tracker = BatterySensorTracker()

        assertEquals(BatteryDataQuality.UNKNOWN, tracker.dataQuality)
    }

    @Test
    fun missingOnlyAfterThreeConsecutiveEmptyReadings() {
        val tracker = BatterySensorTracker()

        tracker.onReading(currentUa = null, counterUah = null)
        tracker.onReading(currentUa = null, counterUah = null)
        assertEquals(SensorAvailability.UNKNOWN, tracker.dataQuality.currentSensor)

        tracker.onReading(currentUa = null, counterUah = null)
        assertEquals(SensorAvailability.MISSING, tracker.dataQuality.currentSensor)
        assertEquals(SensorAvailability.MISSING, tracker.dataQuality.counterSensor)
    }

    @Test
    fun singleValidReading_restoresAvailabilityImmediately() {
        val tracker = BatterySensorTracker()
        repeat(3) { tracker.onReading(currentUa = null, counterUah = null) }

        tracker.onReading(currentUa = 1_500_000L, counterUah = 2_000_000L)

        assertEquals(SensorAvailability.AVAILABLE, tracker.dataQuality.currentSensor)
        assertEquals(SensorAvailability.AVAILABLE, tracker.dataQuality.counterSensor)
    }

    @Test
    fun availableSensor_survivesShortGaps() {
        val tracker = BatterySensorTracker()
        tracker.onReading(currentUa = 1_500_000L, counterUah = 2_000_000L)

        tracker.onReading(currentUa = null, counterUah = null)
        tracker.onReading(currentUa = null, counterUah = null)
        assertEquals(SensorAvailability.AVAILABLE, tracker.dataQuality.currentSensor)

        tracker.onReading(currentUa = null, counterUah = null)
        assertEquals(SensorAvailability.MISSING, tracker.dataQuality.currentSensor)
    }

    @Test
    fun sensorsAreTrackedIndependently() {
        val tracker = BatterySensorTracker()
        repeat(3) { tracker.onReading(currentUa = 1_500_000L, counterUah = null) }

        assertEquals(SensorAvailability.AVAILABLE, tracker.dataQuality.currentSensor)
        assertEquals(SensorAvailability.MISSING, tracker.dataQuality.counterSensor)
    }
}
