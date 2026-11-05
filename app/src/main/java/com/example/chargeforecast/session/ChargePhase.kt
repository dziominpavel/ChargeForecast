package com.example.chargeforecast.session

// Фаза сессии зарядки (design D6 change forecast-integrity).
// STALLED — кабель подключён, но телефон не заряжается: уровень не
// растёт N замеров подряд, статус не CHARGING и не FULL (на устройстве
// это status=DISCHARGING при USB powered). Прогноз до 100% в этой фазе
// не показывается — вместо него объяснение и оценка работы.
enum class ChargePhase {
    CHARGING,
    FULL,
    STALLED
}
