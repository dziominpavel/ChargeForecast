package com.example.chargeforecast

import com.example.chargeforecast.diag.DiagEvent
import com.example.chargeforecast.diag.DiagEventKind
import com.example.chargeforecast.tracking.TrackingFailure
import com.example.chargeforecast.tracking.TrackingHealthStore
import com.example.chargeforecast.tracking.TrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun statusChangesAreJournaledOnce() {
        // Каждая смена статуса — строка события с причиной; повтор
        // того же статуса молчит (onStartCommand зовёт markRunning часто).
        val events = mutableListOf<DiagEvent>()
        val store = TrackingHealthStore(onEvent = { events += it })

        store.markStarting()
        store.markRunning()
        store.markRunning()
        store.markBlocked(TrackingFailure.START_BLOCKED)

        assertEquals(3, events.size)
        assertTrue(events.all { it.kind == DiagEventKind.TRACKING })
        assertTrue(events[0].fields.contains("to=STARTING"))
        assertTrue(events[1].fields.contains("from=STARTING"))
        assertTrue(events[1].fields.contains("to=RUNNING"))
        assertTrue(events[2].fields.contains("why=START_BLOCKED"))
    }
}
