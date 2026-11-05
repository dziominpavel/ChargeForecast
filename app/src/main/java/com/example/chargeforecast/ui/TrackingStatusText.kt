package com.example.chargeforecast.ui

import com.example.chargeforecast.R
import com.example.chargeforecast.tracking.TrackingHealth
import com.example.chargeforecast.tracking.TrackingLiveness
import com.example.chargeforecast.tracking.TrackingStatus

// Действие в строке статуса: что именно сделает кнопка.
enum class TrackingAction {
    NONE,
    RESUME,
    OPEN_SETTINGS
}

// Что написать в строке состояния слежения и какое действие предложить.
// Чистая функция: выбор текста проверяется JVM-тестом, экран только
// резолвит строковые ресурсы (design D2: экран показывает и причину, и
// действие).
data class TrackingStatusInfo(
    val textRes: Int,
    val action: TrackingAction = TrackingAction.NONE,
    val actionLabelRes: Int? = null,
    val hintRes: Int? = null,
    // Возраст замера для строки «данные не обновляются N мин».
    val ageMinutes: Long = 0L
)

object TrackingStatusText {

    fun of(health: TrackingHealth, dataAgeMinutes: Long): TrackingStatusInfo =
        when (health.status) {
            // Причина отказа важнее общего статуса: без неё пользователь
            // не знает, что именно чинить.
            TrackingStatus.NO_PERMISSION -> TrackingStatusInfo(
                textRes = R.string.tracking_no_permission,
                action = TrackingAction.OPEN_SETTINGS,
                actionLabelRes = R.string.tracking_open_settings
            )
            TrackingStatus.BLOCKED_BY_OS -> TrackingStatusInfo(
                textRes = R.string.tracking_blocked,
                action = TrackingAction.RESUME,
                actionLabelRes = R.string.tracking_resume,
                hintRes = R.string.tracking_blocked_hint
            )
            else -> when (health.liveness) {
                TrackingLiveness.DATA_STALE -> TrackingStatusInfo(
                    textRes = R.string.tracking_data_stale,
                    action = TrackingAction.RESUME,
                    actionLabelRes = R.string.tracking_resume,
                    ageMinutes = dataAgeMinutes
                )
                TrackingLiveness.BACKGROUND_INACTIVE -> TrackingStatusInfo(
                    textRes = R.string.tracking_background_inactive,
                    action = TrackingAction.RESUME,
                    actionLabelRes = R.string.tracking_resume
                )
                TrackingLiveness.ACTIVE -> TrackingStatusInfo(
                    textRes = if (health.status == TrackingStatus.STARTING) {
                        R.string.tracking_starting
                    } else {
                        R.string.tracking_running
                    }
                )
            }
        }
}
