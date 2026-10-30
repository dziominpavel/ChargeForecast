package com.example.chargeforecast

import com.example.chargeforecast.tracking.TrackingFailure
import com.example.chargeforecast.tracking.TrackingHealth
import com.example.chargeforecast.tracking.TrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingHealthTest {

    @Test
    fun initialState_isStoppedAndNotOperational() {
        val health = TrackingHealth()

        assertEquals(TrackingStatus.STOPPED, health.status)
        assertFalse(health.isOperational)
        assertTrue(health.needsRestart)
    }

    @Test
    fun startingAndRunning_areOperational() {
        assertTrue(TrackingHealth(status = TrackingStatus.STARTING).isOperational)
        assertTrue(TrackingHealth(status = TrackingStatus.RUNNING).isOperational)
        assertFalse(TrackingHealth(status = TrackingStatus.STOPPED).isOperational)
        assertFalse(TrackingHealth(status = TrackingStatus.BLOCKED_BY_OS).isOperational)
        assertFalse(TrackingHealth(status = TrackingStatus.NO_PERMISSION).isOperational)
    }

    @Test
    fun securityException_meansNoPermission() {
        assertEquals(
            TrackingFailure.PERMISSION_MISSING,
            TrackingFailure.classify(SecurityException("denied"))
        )
    }

    @Test
    fun foregroundRefusal_isDistinguishedFromStartRefusal() {
        assertEquals(
            TrackingFailure.FOREGROUND_BLOCKED,
            TrackingFailure.classify(IllegalStateException("foreground"), foreground = true)
        )
        assertEquals(
            TrackingFailure.START_BLOCKED,
            TrackingFailure.classify(IllegalStateException("start"))
        )
    }
}
