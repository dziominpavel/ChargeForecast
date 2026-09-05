package com.example.chargeforecast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.chargeforecast.sessionMonitor

// Экран получает только данные и колбэки отсюда (см. AGENTS.md).
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val monitor = app.sessionMonitor()

    val state = monitor.state

    fun dismissError() {
        monitor.reportError(null)
    }
}
