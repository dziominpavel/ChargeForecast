package com.example.chargeforecast.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Единственный владелец TrackingHealth на процесс. Пишет сервис,
// читают ChargeSessionMonitor (а через него экран и виджет).
// Начальное STOPPED честно означает «сервис ещё не подтвердил работу».
class TrackingHealthStore {

    private val _health = MutableStateFlow(TrackingHealth())
    val health: StateFlow<TrackingHealth> = _health.asStateFlow()

    fun markStarting() {
        val current = _health.value
        // Повторный start() уже работающего сервиса не должен откатывать
        // подтверждённый RUNNING в STARTING.
        if (current.status == TrackingStatus.RUNNING) return
        _health.value = TrackingHealth(
            status = TrackingStatus.STARTING,
            failure = current.failure
        )
    }

    fun markRunning() {
        _health.value = TrackingHealth(status = TrackingStatus.RUNNING)
    }

    fun markStopped() {
        val current = _health.value
        // Ошибку запуска не затираем нейтральным «остановлено»: иначе
        // экран потеряет причину, которую пользователь должен увидеть.
        if (current.status == TrackingStatus.BLOCKED_BY_OS ||
            current.status == TrackingStatus.NO_PERMISSION
        ) {
            return
        }
        _health.value = TrackingHealth(status = TrackingStatus.STOPPED)
    }

    fun markBlocked(failure: TrackingFailure = TrackingFailure.START_BLOCKED) {
        _health.value = TrackingHealth(
            status = TrackingStatus.BLOCKED_BY_OS,
            failure = failure
        )
    }

    fun markNoPermission() {
        _health.value = TrackingHealth(
            status = TrackingStatus.NO_PERMISSION,
            failure = TrackingFailure.PERMISSION_MISSING
        )
    }
}
