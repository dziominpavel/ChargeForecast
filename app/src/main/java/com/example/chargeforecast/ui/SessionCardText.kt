package com.example.chargeforecast.ui

import com.example.chargeforecast.session.ChargePhase
import com.example.chargeforecast.session.SessionState

// Что показывает карточка экрана зарядки. Чистая функция: выбор режима
// тестируется на JVM без Compose, экран только резолвит строки.
enum class SessionCardMode {
    // Отсчёт до 100%: скорость, остаток, ярус точности.
    FORECAST,
    // Полный заряд: «Заряжено», а не нулевой отсчёт.
    CHARGED,
    // Кабель подключён, зарядка не идёт: прогноза нет, вместо него
    // объяснение и оценка работы (спека charge-forecast).
    STALLED
}

object SessionCardText {

    fun mode(state: SessionState): SessionCardMode = when {
        state.chargePhase == ChargePhase.STALLED -> SessionCardMode.STALLED
        state.forecast.isFull -> SessionCardMode.CHARGED
        else -> SessionCardMode.FORECAST
    }
}
