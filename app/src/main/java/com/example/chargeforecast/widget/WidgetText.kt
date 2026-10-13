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
    // Простой — приблизительное оставшееся время работы (приор или
    // измеренное среднее).
    DISCHARGE,
    // Слежение остановлено: любое число могло замерзнуть.
    NOT_TRACKING,
    // Состояние ещё не посчитано — пустое содержимое, без текста.
    NO_DATA
}

// Бакет размера ячейки виджета (design D7).
enum class WidgetSize {
    // Полоса в одну строку: уровень, время, короткая подпись.
    NARROW,
    // Привычные три строки: режим, время, уровень.
    MEDIUM,
    // Широкое/высокое поле: те же три строки крупнее.
    WIDE
}

// Границы бакетов размера (design D7) в dp. Опора — минимальная ячейка
// 180×110 dp из battery_widget_info: это средний бакет, три строки. Полоса
// в одну строку заметно ниже (одна ячейка по высоте) — узкий бакет.
// Широкий бакет начинается там, где есть место под крупные кегли.
const val NARROW_MAX_WIDTH_DP = 140
const val NARROW_MAX_HEIGHT_DP = 90
const val WIDE_MIN_WIDTH_DP = 340
const val WIDE_MIN_HEIGHT_DP = 180

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

    // Бакет размера по фактическому dp-размеру ячейки (design D7): один
    // адаптивный виджет, у каждого бакета своя раскладка и кегли. Границы
    // взяты так, чтобы полоса 1×3 попадала в узкий бакет, а привычный
    // 3×2 — в средний.
    fun sizeBucket(widthDp: Int, heightDp: Int): WidgetSize = when {
        widthDp >= WIDE_MIN_WIDTH_DP && heightDp >= WIDE_MIN_HEIGHT_DP ->
            WidgetSize.WIDE
        widthDp < NARROW_MAX_WIDTH_DP || heightDp < NARROW_MAX_HEIGHT_DP ->
            WidgetSize.NARROW
        else -> WidgetSize.MEDIUM
    }

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
        // Простой: приор «полный заряд ≈ 24 ч» или измеренное среднее —
        // число с первой секунды, текстов-заглушек нет. Числа нет
        // только у отключённого оценщика — тогда пустое содержимое.
        val estimate = state.discharge
        return if (estimate.remainingSec == null) {
            WidgetModel(WidgetMode.NO_DATA, null, level, false)
        } else {
            WidgetModel(WidgetMode.DISCHARGE, estimate.remainingSec, level, true)
        }
    }
}
