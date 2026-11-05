package com.example.chargeforecast

import com.example.chargeforecast.tracking.TrackingFailure
import com.example.chargeforecast.tracking.TrackingHealth
import com.example.chargeforecast.tracking.TrackingStatus
import com.example.chargeforecast.ui.TrackingAction
import com.example.chargeforecast.ui.TrackingStatusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Строка состояния слежения: текст и действие выбираются чистой функцией,
// Compose только резолвит ресурсы (спека background-tracking, design D2).
class TrackingStatusTextTest {

    @Test
    fun active_isReportedWithoutAction() {
        val text = TrackingStatusText.of(
            TrackingHealth(status = TrackingStatus.RUNNING),
            dataAgeMinutes = 0L
        )

        assertEquals(R.string.tracking_running, text.textRes)
        assertEquals(TrackingAction.NONE, text.action)
        assertNull(text.actionLabelRes)
    }

    @Test
    fun starting_isSeparateFromRunning() {
        val text = TrackingStatusText.of(
            TrackingHealth(status = TrackingStatus.STARTING),
            dataAgeMinutes = 0L
        )

        assertEquals(R.string.tracking_starting, text.textRes)
        assertEquals(TrackingAction.NONE, text.action)
    }

    @Test
    fun freshDataWithoutService_saysBackgroundInactive_andOffersResume() {
        // «Сервис убит, данные свежие»: нельзя утверждать, что данные
        // замерли, но и молчать про фон нельзя.
        val text = TrackingStatusText.of(
            TrackingHealth(status = TrackingStatus.STOPPED, dataFresh = true),
            dataAgeMinutes = 0L
        )

        assertEquals(R.string.tracking_background_inactive, text.textRes)
        assertEquals(TrackingAction.RESUME, text.action)
        assertEquals(R.string.tracking_resume, text.actionLabelRes)
    }

    @Test
    fun staleData_showsAge_andOffersResume() {
        val text = TrackingStatusText.of(
            TrackingHealth(status = TrackingStatus.RUNNING, dataFresh = false),
            dataAgeMinutes = 7L
        )

        assertEquals(R.string.tracking_data_stale, text.textRes)
        assertEquals(7L, text.ageMinutes)
        assertEquals(TrackingAction.RESUME, text.action)
    }

    @Test
    fun noPermission_opensSettings_notResume() {
        val text = TrackingStatusText.of(
            TrackingHealth(
                status = TrackingStatus.NO_PERMISSION,
                failure = TrackingFailure.PERMISSION_MISSING,
                dataFresh = false
            ),
            dataAgeMinutes = 10L
        )

        assertEquals(R.string.tracking_no_permission, text.textRes)
        assertEquals(TrackingAction.OPEN_SETTINGS, text.action)
        assertEquals(R.string.tracking_open_settings, text.actionLabelRes)
    }

    @Test
    fun blockedByOs_explainsHint_andOffersResume() {
        val text = TrackingStatusText.of(
            TrackingHealth(
                status = TrackingStatus.BLOCKED_BY_OS,
                failure = TrackingFailure.START_BLOCKED
            ),
            dataAgeMinutes = 0L
        )

        assertEquals(R.string.tracking_blocked, text.textRes)
        assertEquals(R.string.tracking_blocked_hint, text.hintRes)
        assertEquals(TrackingAction.RESUME, text.action)
    }
}
