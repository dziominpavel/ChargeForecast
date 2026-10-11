package com.example.chargeforecast.widget

import com.example.chargeforecast.forecast.ForecastAccuracy
import com.example.chargeforecast.session.SessionState

// Режим виджета: чем он занят. Выбирается состоянием сессии сам, без
// настройки со стороны пользователя (спека battery-widget).
enum class WidgetMode {
    // Идёт зарядка — отсчёт до 100%.
    CHARGING,
    // Заряжено полностью: состояние, а не нулевой отсчёт.
    FULL,
    // Простой — приблизительное оставшееся время работы.
    DISCHARGE,
    // Простой, но опоры для оценки ещё нет.
    COLLECTING,
    // Слежение остановлено: любое число могло замерзнуть.
    NOT_TRACKING,
    // Состояние ещё не посчитано.
    NO_DATA
}

// Чистое представление виджета: решение «что показывать» без Context.
// Строки из ресурсов резолвит провайдер — так форматирование обоих
// режимов тестируется на JVM.
data class WidgetModel(
    val mode: WidgetMode,
    // Секунд до цели, null — цифры нет, показывается состояние.
    val remainingSec: Long?,
    val levelPct: Int?,
    // Приблизительность: оценка разряда всегда «примерно», зарядный
    // отсчёт — по ярусу точности конвейера.
    val approximate: Boolean
)

object WidgetText {

    fun model(state: SessionState, tracking: Boolean): WidgetModel {
        val level = state.snapshot?.levelPct

        // Слежение не работает — данные могли замерзнуть на последнем
        // числе. Честнее сказать об этом, чем показать устаревшее
        // (риски design.md, спека battery-widget).
        if (!tracking) {
            return WidgetModel(WidgetMode.NOT_TRACKING, null, level, false)
        }
        if (state.snapshot == null) {
            return WidgetModel(WidgetMode.NO_DATA, null, null, false)
        }
        if (state.isPlugged) {
            if (state.forecast.isFull) {
                return WidgetModel(WidgetMode.FULL, null, level, false)
            }
            return WidgetModel(
                WidgetMode.CHARGING,
                state.forecast.remainingSec,
                level,
                state.forecast.accuracy != ForecastAccuracy.PRECISE
            )
        }
        // Простой: оценка работы. Без устойчивой опоры — сбор данных,
        // а не пустой отсчёт и не нулевые минуты.
        val estimate = state.discharge
        return if (estimate.collecting || estimate.remainingSec == null) {
            WidgetModel(WidgetMode.COLLECTING, null, level, true)
        } else {
            WidgetModel(WidgetMode.DISCHARGE, estimate.remainingSec, level, true)
        }
    }
}
