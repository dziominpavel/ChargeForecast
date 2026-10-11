package com.example.chargeforecast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.chargeforecast.sessionMonitor
import kotlinx.coroutines.flow.SharedFlow

// Экран получает только данные и колбэки отсюда (см. AGENTS.md).
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val monitor = app.sessionMonitor()

    val state = monitor.state

    // Ошибки — поток событий, а не значение состояния (design D7):
    // у каждого подписчика своё окно, новое не переигрывает старое.
    val errors: SharedFlow<String> = monitor.errors
}
