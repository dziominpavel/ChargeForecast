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

// Как выглядит слежение с точки зрения пользователя: живой сервис при
// свежих данных, живой сервис без свежих данных или данные без сервиса.
// Статусы сервиса (TrackingStatus) отвечают на другой вопрос — «что с
// сервисом», — и остаются как есть.
enum class TrackingLiveness {
    ACTIVE,
    BACKGROUND_INACTIVE,
    DATA_STALE
}

data class TrackingHealth(
    val status: TrackingStatus = TrackingStatus.STOPPED,
    val failure: TrackingFailure? = null,
    // Подтверждённый foreground-сервис. STARTING считается активным:
    // иначе виджет мигнёт «фон не активен» на время холодного старта.
    val backgroundActive: Boolean =
        status == TrackingStatus.RUNNING || status == TrackingStatus.STARTING,
    // Свежесть последнего замера: заполняет монитор, а не сервис — только
    // он знает возраст данных. По умолчанию «свежие»: сервис сам про
    // данные ничего не утверждает.
    val dataFresh: Boolean = true
) {
    val isOperational: Boolean
        get() = backgroundActive

    // Старые данные важнее фона: «данные не обновляются» — это то, что
    // пользователь видит как застывшие числа, независимо от того, жив ли
    // сервис (design D2).
    val liveness: TrackingLiveness
        get() = when {
            !dataFresh -> TrackingLiveness.DATA_STALE
            !backgroundActive -> TrackingLiveness.BACKGROUND_INACTIVE
            else -> TrackingLiveness.ACTIVE
        }

    val needsRestart: Boolean
        get() = status == TrackingStatus.STOPPED || status == TrackingStatus.BLOCKED_BY_OS

    companion object {
        // Дефолт для чистых unit-состояний: монитор без внешнего health-
        // потока считается работающим, пока тест не скажет обратное.
        fun running(): TrackingHealth = TrackingHealth(status = TrackingStatus.RUNNING)
    }
}
