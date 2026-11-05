package com.example.chargeforecast.tracking

import com.example.chargeforecast.diag.DiagEvent
import com.example.chargeforecast.diag.DiagEventKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Единственный владелец TrackingHealth на процесс. Пишет сервис,
// читают ChargeSessionMonitor (а через него экран и виджет).
// Начальное STOPPED честно означает «сервис ещё не подтвердил работу».
// Каждая смена статуса уходит событием в журнал диагностики: без него
// после перезапуска процесса не отличить «убила оболочка» от
// «не пытались запускать».
class TrackingHealthStore(
    private val onEvent: (DiagEvent) -> Unit = {}
) {

    private val _health = MutableStateFlow(TrackingHealth())
    val health: StateFlow<TrackingHealth> = _health.asStateFlow()

    fun markStarting() {
        val current = _health.value
        // Повторный start() уже работающего сервиса не должен откатывать
        // подтверждённый RUNNING в STARTING.
        if (current.status == TrackingStatus.RUNNING) return
        publish(TrackingHealth(status = TrackingStatus.STARTING, failure = current.failure))
    }

    fun markRunning() {
        publish(TrackingHealth(status = TrackingStatus.RUNNING))
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
        publish(TrackingHealth(status = TrackingStatus.STOPPED))
    }

    fun markBlocked(failure: TrackingFailure = TrackingFailure.START_BLOCKED) {
        publish(TrackingHealth(status = TrackingStatus.BLOCKED_BY_OS, failure = failure))
    }

    fun markNoPermission() {
        publish(
            TrackingHealth(
                status = TrackingStatus.NO_PERMISSION,
                failure = TrackingFailure.PERMISSION_MISSING
            )
        )
    }

    // Повтор того же статуса молчит: onStartCommand зовёт markRunning на
    // каждом старте, и журнал не должен заполняться одинаковыми строками.
    private fun publish(next: TrackingHealth) {
        val previous = _health.value
        _health.value = next
        if (previous.status == next.status && previous.failure == next.failure) return
        onEvent(
            DiagEvent(
                DiagEventKind.TRACKING,
                "from=" + previous.status.name + ";to=" + next.status.name +
                    ";why=" + (next.failure?.name ?: "-")
            )
        )
    }
}
