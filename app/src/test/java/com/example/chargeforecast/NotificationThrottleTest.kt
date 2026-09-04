package com.example.chargeforecast

import com.example.chargeforecast.notification.NotificationHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Троттлинг шторки: не чаще раза в 30 секунд, без спама.
class NotificationThrottleTest {

    @Test
    fun firstUpdate_alwaysAllowed() {
        assertTrue(NotificationHelper.shouldNotify(null, 1_000L))
    }

    @Test
    fun updateWithinInterval_blocked() {
        assertFalse(NotificationHelper.shouldNotify(1_000L, 1_000L + 10_000L))
        assertFalse(
            NotificationHelper.shouldNotify(
                1_000L,
                1_000L + NotificationHelper.MIN_UPDATE_INTERVAL_MS - 1L
            )
        )
    }

    @Test
    fun updateAfterInterval_allowed() {
        assertTrue(
            NotificationHelper.shouldNotify(
                1_000L,
                1_000L + NotificationHelper.MIN_UPDATE_INTERVAL_MS
            )
        )
        assertTrue(
            NotificationHelper.shouldNotify(
                1_000L,
                1_000L + 5 * NotificationHelper.MIN_UPDATE_INTERVAL_MS
            )
        )
    }
}
