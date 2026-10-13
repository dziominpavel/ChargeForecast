package com.example.chargeforecast.widget

import android.content.Context
import com.example.chargeforecast.session.SessionState

// Толкает состояние сессии на виджет (design D5).
//
// Системный интервал обновления виджета — минимум 30 минут, для отсчёта
// до 100% это неприемлимо, поэтому обновление идёт push'ем от состояния.
// Троттлинг нужен сам: на зарядке состояние меняется каждую секунду, а
// каждое обновление — binder-вызов в системный процесс лаунчера. Переходы
// режима (подключение, отключение, полный заряд, остановка слежения)
// троттлинг обходят — там важна секунда, а не минута.
class BatteryWidgetUpdater(private val context: Context) {

    private var lastModel: WidgetModel? = null
    private var lastPushedAtMs = 0L

    fun onState(state: SessionState) {
        val model = WidgetText.model(state, BatteryWidgetProvider.isTrackingRunning(context))
        if (model == lastModel) {
            // Ничего не изменилось — обновление не нужно вовсе.
            return
        }
        val now = System.currentTimeMillis()
        val transition = model.mode != lastModel?.mode
        // Последняя минута зарядки: секунды на виджете идут плавно —
        // толчок раз в секунду вместо обычного троттлинга (спека
        // battery-widget, «Секунды только в последнюю минуту»).
        val interval = if (
            model.mode == WidgetMode.CHARGING &&
            (model.remainingSec ?: Long.MAX_VALUE) < LAST_MINUTE_MS
        ) {
            LAST_MINUTE_UPDATE_INTERVAL_MS
        } else {
            MIN_UPDATE_INTERVAL_MS
        }
        if (!transition && now - lastPushedAtMs < interval) {
            // Отсчёт устарел максимум на интервал троттлинга; следующий
            // тик доведёт его. Модель не запоминаем — тогда следующее
            // изменение всё равно пройдёт проверку.
            return
        }
        lastModel = model
        lastPushedAtMs = now
        BatteryWidgetProvider.pushAll(context, model)
    }

    companion object {
        // Троттлинг обычных обновлений во время зарядки (~15 с по design D5).
        const val MIN_UPDATE_INTERVAL_MS = 15_000L
        // Граница последней минуты: остаток меньше минуты — тикаем секундами.
        const val LAST_MINUTE_MS = 60_000L
        // Интервал толчка в последнюю минуту — секунда.
        const val LAST_MINUTE_UPDATE_INTERVAL_MS = 1_000L
    }
}
