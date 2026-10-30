package com.example.chargeforecast.ui

import com.example.chargeforecast.R
import com.example.chargeforecast.battery.BatteryDataQuality

// Какой fallback честно назвать на экране. Чистая функция: выбор текста
// тестируется без Compose и Context, а экран только резолвит строку.
object SensorFallbackText {

    fun messageRes(quality: BatteryDataQuality, whileCharging: Boolean): Int? {
        if (whileCharging) {
            return when {
                quality.currentMissing && quality.counterMissing ->
                    R.string.session_sensor_fallback_both
                quality.currentMissing -> R.string.session_sensor_fallback_current
                quality.counterMissing -> R.string.session_sensor_fallback_counter
                else -> null
            }
        }
        // В разряде оценщик не использует ток, а без счётчика теряет
        // интервалы и опирается на приор — об этом и говорим.
        return if (quality.counterMissing) {
            R.string.session_sensor_fallback_discharge
        } else {
            null
        }
    }
}
