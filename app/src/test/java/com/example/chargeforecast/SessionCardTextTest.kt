package com.example.chargeforecast

import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.ChargeForecast
import com.example.chargeforecast.forecast.ForecastAccuracy
import com.example.chargeforecast.session.ChargePhase
import com.example.chargeforecast.session.SessionState
import com.example.chargeforecast.ui.SessionCardMode
import com.example.chargeforecast.ui.SessionCardText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Карточка экрана зарядки: какой режим показывается в каждой фазе.
// В STALLED прогноза до 100% нет — вместо него объяснение и оценка работы
// (5.2 change forecast-integrity).
class SessionCardTextTest {

    private val forecast = ChargeForecast(
        speedPctPerMin = 0.5f,
        remainingSec = 1_200L,
        accuracy = ForecastAccuracy.APPROXIMATE,
        isFull = false,
        remainingPct = 10f
    )

    private val charged = ChargeForecast(
        speedPctPerMin = null,
        remainingSec = 0L,
        accuracy = ForecastAccuracy.PRECISE,
        isFull = true,
        remainingPct = 0f
    )

    private val hidden = ChargeForecast(
        speedPctPerMin = null,
        remainingSec = null,
        accuracy = ForecastAccuracy.COLLECTING,
        isFull = false
    )

    private fun state(phase: ChargePhase, forecast: ChargeForecast) = SessionState(
        snapshot = BatterySnapshot(
            levelPct = if (forecast.isFull) 100 else 81,
            isPlugged = true,
            isCharging = phase == ChargePhase.CHARGING,
            isFull = forecast.isFull,
            chargeType = ChargeType.AC,
            temperatureC = null,
            voltageV = null,
            currentMa = null,
            chargeCounterUah = 3_000_000L,
            timestampMs = 0L
        ),
        forecast = forecast,
        sessionAgeMs = 60_000L,
        chargePhase = phase
    )

    @Test
    fun charging_showsForecastCard() {
        assertEquals(
            SessionCardMode.FORECAST,
            SessionCardText.mode(state(ChargePhase.CHARGING, forecast))
        )
    }

    @Test
    fun stalled_showsStateCardWithoutRemaining() {
        // Прогноза зарядки нет: строки «осталось» в карточке не будет.
        val state = state(ChargePhase.STALLED, hidden)
        assertEquals(SessionCardMode.STALLED, SessionCardText.mode(state))
        assertNull(state.forecast.remainingSec)
    }

    @Test
    fun full_showsCharged() {
        assertEquals(
            SessionCardMode.CHARGED,
            SessionCardText.mode(state(ChargePhase.FULL, charged))
        )
    }

    @Test
    fun stalledTakesPrecedenceOverFullFlag() {
        // Фаза — источник истины: даже при isFull = true состояние
        // «зарядка не идёт» не маскируется под «Заряжено».
        assertEquals(
            SessionCardMode.STALLED,
            SessionCardText.mode(state(ChargePhase.STALLED, charged))
        )
    }
}
