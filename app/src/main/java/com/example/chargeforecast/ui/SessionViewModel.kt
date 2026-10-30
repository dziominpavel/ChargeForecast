package com.example.chargeforecast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.chargeforecast.service.ChargeForecastService
import com.example.chargeforecast.sessionMonitor
import kotlinx.coroutines.flow.Flow

// Экран получает только данные и колбэки отсюда (см. AGENTS.md).
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val monitor = app.sessionMonitor()

    val state = monitor.state

    // Ошибки — поток событий, а не значение состояния: буфер канала
    // сохраняет сообщение, пришедшее до подписки экрана.
    val errors: Flow<String> = monitor.errors

    fun restartTracking() {
        ChargeForecastService.start(getApplication())
    }
}
