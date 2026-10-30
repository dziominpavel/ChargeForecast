package com.example.chargeforecast.tracking

// Состояние фонового слежения — значение на процесс, а не разовое событие.
// Экран и виджет читают его из общего SessionState, поэтому «слежение
// остановлено» видно постоянно, а не только в момент ошибки.
enum class TrackingStatus {
    STARTING,
    RUNNING,
    STOPPED,
    BLOCKED_BY_OS,
    NO_PERMISSION
}

// Причина отказа: хранится отдельно от статуса, чтобы экран мог показать
// понятное объяснение, а лог — техническую деталь.
enum class TrackingFailure {
    START_BLOCKED,
    FOREGROUND_BLOCKED,
    PERMISSION_MISSING,
    UNKNOWN;

    companion object {
        // Классификация не зависит от Android: сервис передаёт сюда
        // пойманное исключение, тест проверяет правило на JVM.
        fun classify(error: Throwable, foreground: Boolean = false): TrackingFailure = when {
            error is SecurityException -> PERMISSION_MISSING
            foreground -> FOREGROUND_BLOCKED
            else -> START_BLOCKED
        }
    }
}

data class TrackingHealth(
    val status: TrackingStatus = TrackingStatus.STOPPED,
    val failure: TrackingFailure? = null
) {
    // STARTING считается рабочим: иначе виджет мигнёт «слежение
    // остановлено» на время холодного старта.
    val isOperational: Boolean
        get() = status == TrackingStatus.RUNNING || status == TrackingStatus.STARTING

    val needsRestart: Boolean
        get() = status == TrackingStatus.STOPPED || status == TrackingStatus.BLOCKED_BY_OS

    companion object {
        // Дефолт для чистых unit-состояний: монитор без внешнего health-
        // потока считается работающим, пока тест не скажет обратное.
        fun running(): TrackingHealth = TrackingHealth(status = TrackingStatus.RUNNING)
    }
}
