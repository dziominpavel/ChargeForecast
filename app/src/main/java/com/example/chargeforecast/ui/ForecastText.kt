package com.example.chargeforecast.ui

import android.content.Context
import com.example.chargeforecast.R
import com.example.chargeforecast.forecast.ChargeForecaster

// Чистое текстовое форматирование для экрана сессии.
object ForecastText {

    // Ярус «Измеряю…»: живой счётчик секунд в первые 20 сек сессии.
    fun measuringAgeSeconds(sessionAgeMs: Long): Long? =
        if (sessionAgeMs in 0 until ChargeForecaster.MEASURING_MAX_MS) {
            sessionAgeMs / 1000L
        } else {
            null
        }

    // Компактная длительность для окна наблюдения: часы и минуты, без
    // секунд — в накопленном времени секунды не несут смысла.
    fun formatHoursMinutes(context: Context, durationMs: Long): String {
        val totalMinutes = durationMs / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> context.getString(
                R.string.time_hours_minutes, hours.toInt(), minutes.toInt()
            )
            hours > 0L -> context.getString(R.string.time_hours, hours.toInt())
            else -> context.getString(R.string.time_minutes, minutes.toInt())
        }
    }

    fun formatRemaining(context: Context, remainingSec: Long): String {
        if (remainingSec < 60L) {
            return context.getString(R.string.time_seconds, remainingSec.toInt())
        }
        if (remainingSec < 3600L) {
            return context.getString(
                R.string.time_minutes_seconds,
                (remainingSec / 60).toInt(),
                (remainingSec % 60).toInt()
            )
        }
        return context.getString(
            R.string.time_hours_minutes_seconds,
            (remainingSec / 3600).toInt(),
            ((remainingSec % 3600) / 60).toInt(),
            (remainingSec % 60).toInt()
        )
    }
}
