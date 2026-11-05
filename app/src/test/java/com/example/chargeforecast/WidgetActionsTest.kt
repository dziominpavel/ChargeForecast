package com.example.chargeforecast

import android.app.PendingIntent
import com.example.chargeforecast.service.ChargeForecastService
import com.example.chargeforecast.widget.WidgetActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Действие возобновления на виджете собирается без Context: проверяем
// компонент, отдельный код запроса и флаги интента (спека battery-widget,
// design D3).
class WidgetActionsTest {

    @Test
    fun resumeAction_targetsTrackingService() {
        assertEquals(ChargeForecastService::class.java, WidgetActions.resumeTarget())
    }

    @Test
    fun resumeAction_usesItsOwnRequestCode() {
        assertNotEquals(
            "коды открытия приложения и возобновления не должны совпадать",
            WidgetActions.OPEN_APP_REQUEST_CODE,
            WidgetActions.RESUME_TRACKING_REQUEST_CODE
        )
    }

    @Test
    fun resumeAction_intentIsImmutableAndUpdated() {
        // IMMUTABLE — обязательный флаг начиная с Android 12; UPDATE_CURRENT
        // нужен, чтобы после перезапуска процесса действие вело на живой
        // сервис, а не на устаревший интент.
        assertTrue(
            "неизменяемый PendingIntent",
            WidgetActions.RESUME_FLAGS and PendingIntent.FLAG_IMMUTABLE != 0
        )
        assertTrue(
            "содержимое обновляется",
            WidgetActions.RESUME_FLAGS and PendingIntent.FLAG_UPDATE_CURRENT != 0
        )
    }
}
