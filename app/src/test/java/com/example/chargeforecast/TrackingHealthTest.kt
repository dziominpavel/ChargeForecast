package com.example.chargeforecast

import com.example.chargeforecast.tracking.TrackingFailure
import com.example.chargeforecast.tracking.TrackingHealth
import com.example.chargeforecast.tracking.TrackingLiveness
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
    fun liveness_distinguishesActiveInactiveAndStale() {
        // Три состояния из спеки background-tracking: «свежие + сервис»,
        // «свежие без сервиса», «старые». Статус сервиса их не заменяет.
        val active = TrackingHealth(status = TrackingStatus.RUNNING, dataFresh = true)
        val backgroundInactive =
            TrackingHealth(status = TrackingStatus.STOPPED, dataFresh = true)
        val stale = TrackingHealth(status = TrackingStatus.RUNNING, dataFresh = false)

        assertEquals(TrackingLiveness.ACTIVE, active.liveness)
        assertEquals(TrackingLiveness.BACKGROUND_INACTIVE, backgroundInactive.liveness)
        assertEquals(TrackingLiveness.DATA_STALE, stale.liveness)
    }

    @Test
    fun liveness_staleDataWinsOverBackgroundInactive() {
        // Замерзшие данные важнее умершего сервиса: «данные не обновляются»
        // — то, что пользователь видит как застывшее число.
        val both = TrackingHealth(status = TrackingStatus.STOPPED, dataFresh = false)
        assertEquals(TrackingLiveness.DATA_STALE, both.liveness)
    }

    @Test
    fun backgroundActive_isDerivedFromStatus_byDefault() {
        assertTrue(TrackingHealth(status = TrackingStatus.RUNNING).backgroundActive)
        assertTrue(TrackingHealth(status = TrackingStatus.STARTING).backgroundActive)
        assertFalse(TrackingHealth(status = TrackingStatus.STOPPED).backgroundActive)
        assertFalse(TrackingHealth(status = TrackingStatus.BLOCKED_BY_OS).backgroundActive)
        assertEquals(
            "свежесть по умолчанию не утверждается сервисом",
            true,
            TrackingHealth().dataFresh
        )
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
