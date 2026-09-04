package com.example.chargeforecast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.chargeforecast.ChargeForecastApplication
import com.example.chargeforecast.ChargeForecastService
import com.example.chargeforecast.R
import com.example.chargeforecast.sessionMonitor

// Экран получает только данные и колбэки отсюда (см. AGENTS.md).
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val monitor = app.sessionMonitor()

    val state = monitor.state

    fun dismissError() {
        monitor.reportError(null)
    }

    // Поднять сервис, когда приложение на переднем плане и идёт зарядка.
    // Вызывать из Activity (foreground) — из фона старт может запретить система.
    fun ensureService() {
        val application = getApplication<ChargeForecastApplication>()
        val snapshot = try {
            application.batteryMonitor.readSnapshotNow()
        } catch (_: Exception) {
            null
        }
        if (snapshot?.isPlugged != true) return
        try {
            ChargeForecastService.start(application)
            monitor.reportError(null)
        } catch (_: SecurityException) {
            monitor.reportError(
                application.getString(R.string.error_service_denied)
            )
        } catch (_: Exception) {
            monitor.reportError(
                application.getString(R.string.error_service_denied)
            )
        }
    }
}
