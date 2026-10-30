package com.example.chargeforecast

import com.example.chargeforecast.tracking.TrackingFailure
import com.example.chargeforecast.tracking.TrackingHealthStore
import com.example.chargeforecast.tracking.TrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingHealthStoreTest {

    @Test
    fun lifecycle_stoppedStartingRunningStopped() {
        val store = TrackingHealthStore()

        assertEquals(TrackingStatus.STOPPED, store.health.value.status)

        store.markStarting()
        assertEquals(TrackingStatus.STARTING, store.health.value.status)

        store.markRunning()
        assertEquals(TrackingStatus.RUNNING, store.health.value.status)

        store.markStopped()
        assertEquals(TrackingStatus.STOPPED, store.health.value.status)
        assertNull(store.health.value.failure)
    }

    @Test
    fun starting_doesNotDowngradeConfirmedRunning() {
        val store = TrackingHealthStore()
        store.markRunning()

        store.markStarting()

        assertEquals(TrackingStatus.RUNNING, store.health.value.status)
    }

    @Test
    fun blockedFailure_isNotOverwrittenByNeutralStopped() {
        val store = TrackingHealthStore()
        store.markBlocked(TrackingFailure.START_BLOCKED)

        store.markStopped()

        assertEquals(TrackingStatus.BLOCKED_BY_OS, store.health.value.status)
        assertEquals(TrackingFailure.START_BLOCKED, store.health.value.failure)
    }

    @Test
    fun noPermission_isNotOverwrittenByNeutralStopped() {
        val store = TrackingHealthStore()
        store.markNoPermission()

        store.markStopped()

        assertEquals(TrackingStatus.NO_PERMISSION, store.health.value.status)
        assertEquals(TrackingFailure.PERMISSION_MISSING, store.health.value.failure)
    }

    @Test
    fun runningClearsPreviousFailure() {
        val store = TrackingHealthStore()
        store.markBlocked(TrackingFailure.FOREGROUND_BLOCKED)

        store.markRunning()

        assertEquals(TrackingStatus.RUNNING, store.health.value.status)
        assertNull(store.health.value.failure)
    }
}
