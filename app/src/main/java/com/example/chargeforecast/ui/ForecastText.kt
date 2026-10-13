package com.example.chargeforecast.ui

import android.content.Context
import com.example.chargeforecast.R

// Чистое текстовое форматирование для экрана сессии.
object ForecastText {

    fun formatRemaining(context: Context, remainingSec: Long): String {
        // Секунды — только в последнюю минуту: дальше они дёргают
        // число, не неся информации (спека charge-forecast, «Фильтр
        // показа»). Экран и виджет делят эту функцию — формат один.
        if (remainingSec < 60L) {
            return context.getString(R.string.time_seconds, remainingSec.toInt())
        }
        if (remainingSec < 3600L) {
            return context.getString(
                R.string.time_minutes, (remainingSec / 60).toInt()
            )
        }
        val hours = (remainingSec / 3600).toInt()
        val minutes = ((remainingSec % 3600) / 60).toInt()
        return if (minutes > 0) {
            context.getString(R.string.time_hours_minutes, hours, minutes)
        } else {
            context.getString(R.string.time_hours, hours)
        }
    }
}
