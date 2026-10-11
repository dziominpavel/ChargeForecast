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
        if (!transition && now - lastPushedAtMs < MIN_UPDATE_INTERVAL_MS) {
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
    }
}
