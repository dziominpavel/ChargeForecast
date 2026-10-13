package com.example.chargeforecast.forecast

import com.example.chargeforecast.battery.ChargeType

// Дефолтный приор скорости по типу подключения — «полный заряд за
// N часов»: цифры остатка с первой секунды даже при чистой установке,
// без текстов-заглушек (спека charge-forecast, design D2).
//
// Значения грубые и видны максимум до первых живых замеров/истории
// (смесь HistoryBlender уводит к факту за первые 3 минуты) — поэтому
// константы калибруются по наблюдениям, а не по тестам.
object ChargePrior {

    // Полный заряд (0 → 100%) по типам подключения, часы.
    const val AC_HOURS = 2f
    const val USB_HOURS = 6f
    const val WIRELESS_HOURS = 4f
    const val UNKNOWN_HOURS = 3f

    // Скорость «от 0 до 100% за N часов» в %/мин — та же единица, что
    // медиана истории в слоте истории SessionEngine.
    fun ratePctPerMin(type: ChargeType): Float = 100f / (hours(type) * 60f)

    private fun hours(type: ChargeType): Float = when (type) {
        ChargeType.AC -> AC_HOURS
        ChargeType.USB -> USB_HOURS
        ChargeType.WIRELESS -> WIRELESS_HOURS
        ChargeType.NONE, ChargeType.UNKNOWN -> UNKNOWN_HOURS
    }
}
