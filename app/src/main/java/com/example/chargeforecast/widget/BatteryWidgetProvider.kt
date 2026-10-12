package com.example.chargeforecast.widget

import android.app.ActivityManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.example.chargeforecast.ChargeForecastApplication
import com.example.chargeforecast.MainActivity
import com.example.chargeforecast.R
import com.example.chargeforecast.service.ChargeForecastService
import com.example.chargeforecast.ui.ForecastText

// Виджет домашнего экрана — вторая поверхность того же состояния сессии.
// Состояние читается из Application, второго экземпляра движка нет
// (design D6): поэтому виджет и экран не расходятся.
//
// Обновление — push от состояния (design D5), а не системный интервал:
// тот самый минимум в 30 минут не годится для отсчёта до 100%. См.
// BatteryWidgetUpdater.
class BatteryWidgetProvider : AppWidgetProvider() {

    // Система зовёт при установке виджета, при своём интервале и при
    // пробуждении процесса. Рисуем текущее состояние — свежее либо
    // честную остановку, а не застывшее число.
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        pushAll(context, currentModel(context))
    }

    // Смена размера ячейки на домашнем экране перерисовывает содержимое
    // под новый бакет (design D7): без этого ресайз менял бы только рамку.
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateWidget(context, appWidgetManager, appWidgetId, currentModel(context))
    }

    companion object {

        // Толкнуть состояние во все ячейки виджета — каждую по её бакету.
        fun pushAll(context: Context, model: WidgetModel) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, BatteryWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            ids.forEach { id -> updateWidget(context, manager, id, model) }
        }

        // Одна ячейка: бакет по фактическому dp-размеру → своя раскладка.
        // Два виджета разного размера живут одним состоянием, но разными
        // картинками (спека battery-widget, «адаптивная раскладка»).
        fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            model: WidgetModel
        ) {
            manager.updateAppWidget(id, render(context, model, bucketOf(manager, id)))
        }

        // dp-размер ячейки из её опций. 0 — размер ещё не известен
        // (ячейка только ставится): привычный средний бакет. Ключи —
        // значения AppWidgetManager.OPTION_APPWIDGET_{WIDTH,HEIGHT} (API 31),
        // константы в стабах SDK недоступны — пишем буквами.
        private fun bucketOf(manager: AppWidgetManager, id: Int): WidgetSize {
            val options = manager.getAppWidgetOptions(id)
            val widthDp = options.getInt(OPTION_WIDTH_KEY)
            val heightDp = options.getInt(OPTION_HEIGHT_KEY)
            if (widthDp <= 0 || heightDp <= 0) return WidgetSize.MEDIUM
            return WidgetText.sizeBucket(widthDp, heightDp)
        }

        private const val OPTION_WIDTH_KEY = "appWidgetWidth"
        private const val OPTION_HEIGHT_KEY = "appWidgetHeight"

        // Модель по текущему состоянию процесса — без ожидания событий.
        fun currentModel(context: Context): WidgetModel {
            val app = context.applicationContext as? ChargeForecastApplication
            val state = app?.sessionMonitor?.state?.value
                ?: return WidgetModel(WidgetMode.NO_DATA, null, null, false)
            return WidgetText.model(state, isTrackingRunning(context))
        }

        // Работает ли фоновое слежение. getRunningServices с API 26 отдаёт
        // только сервисы собственного приложения — ровно то, что нужно;
        // несмотря на deprecated-статус именно этот случай он и покрывает.
        // Отказ (OEM запретил доступ) трактуем как «не работает»: виджет
        // честно скажет об остановке, а не покажет застывшее число.
        fun isTrackingRunning(context: Context): Boolean = try {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            activityManager?.getRunningServices(Int.MAX_VALUE)
                ?.any { it.service.className == ChargeForecastService::class.java.name } == true
        } catch (_: Exception) {
            false
        }

        private fun render(
            context: Context,
            model: WidgetModel,
            bucket: WidgetSize
        ): RemoteViews = when (bucket) {
            WidgetSize.NARROW -> renderNarrow(context, model)
            WidgetSize.WIDE -> renderStacked(context, model, R.layout.widget_battery_wide)
            WidgetSize.MEDIUM -> renderStacked(context, model, R.layout.widget_battery)
        }

        // Средний и широкий бакеты: три строки, свои кегли в раскладке.
        private fun renderStacked(
            context: Context,
            model: WidgetModel,
            layoutRes: Int
        ): RemoteViews =
            RemoteViews(context.packageName, layoutRes).apply {
                setTextViewText(R.id.widget_title, title(context, model))

                val time = time(context, model)
                if (time == null) {
                    setViewVisibility(R.id.widget_time, View.GONE)
                } else {
                    setViewVisibility(R.id.widget_time, View.VISIBLE)
                    // Приблизительность помечается явно: оценка разряда —
                    // продолжение недавнего расхода, а не обещание.
                    setTextViewText(
                        R.id.widget_time,
                        if (model.approximate) context.getString(R.string.approx_marker, time) else time
                    )
                }

                val level = model.levelPct
                if (level == null) {
                    setViewVisibility(R.id.widget_level, View.GONE)
                } else {
                    setViewVisibility(R.id.widget_level, View.VISIBLE)
                    setTextViewText(R.id.widget_level, context.getString(R.string.session_level, level))
                }

                // Тап по виджету открывает экран сессии — полный контекст,
                // который одну строку вместить не может (спека battery-widget).
                setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            }

        // Узкий бакет: одна строка — уровень, время, короткая подпись.
        // Полные подписи здесь обрезались бы, поэтому состояния сокращены.
        private fun renderNarrow(context: Context, model: WidgetModel): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_battery_narrow).apply {
                val level = model.levelPct
                if (level == null) {
                    setViewVisibility(R.id.widget_level, View.GONE)
                } else {
                    setViewVisibility(R.id.widget_level, View.VISIBLE)
                    setTextViewText(R.id.widget_level, context.getString(R.string.session_level, level))
                }

                val time = time(context, model)
                if (time == null) {
                    setViewVisibility(R.id.widget_time, View.GONE)
                } else {
                    setViewVisibility(R.id.widget_time, View.VISIBLE)
                    setTextViewText(
                        R.id.widget_time,
                        if (model.approximate) context.getString(R.string.approx_marker, time) else time
                    )
                }

                setTextViewText(R.id.widget_title, shortTitle(context, model))
                setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            }

        private fun shortTitle(context: Context, model: WidgetModel): String =
            when (model.mode) {
                WidgetMode.CHARGING -> context.getString(R.string.widget_short_charging)
                WidgetMode.FULL -> context.getString(R.string.widget_short_full)
                WidgetMode.DISCHARGE -> context.getString(R.string.widget_short_discharge)
                WidgetMode.COLLECTING -> context.getString(R.string.widget_short_collecting)
                WidgetMode.NOT_TRACKING -> context.getString(R.string.widget_short_not_tracking)
                WidgetMode.NO_DATA -> context.getString(R.string.widget_short_no_data)
            }

        private fun title(context: Context, model: WidgetModel): String =
            when (model.mode) {
                WidgetMode.CHARGING -> context.getString(R.string.widget_charging_title)
                WidgetMode.FULL -> context.getString(R.string.widget_full_title)
                WidgetMode.DISCHARGE, WidgetMode.COLLECTING ->
                    context.getString(R.string.widget_discharge_title)
                WidgetMode.NOT_TRACKING -> context.getString(R.string.widget_not_tracking)
                WidgetMode.NO_DATA -> context.getString(R.string.widget_no_data)
            }

        // null — строку времени показывать не нужно.
        private fun time(context: Context, model: WidgetModel): String? =
            when (model.mode) {
                WidgetMode.FULL -> context.getString(R.string.session_charged)
                WidgetMode.CHARGING -> model.remainingSec
                    ?.let { ForecastText.formatRemaining(context, it) }
                    ?: context.getString(R.string.widget_measuring)
                WidgetMode.DISCHARGE -> model.remainingSec
                    ?.let { ForecastText.formatRemaining(context, it) }
                WidgetMode.COLLECTING -> context.getString(R.string.widget_collecting)
                WidgetMode.NOT_TRACKING, WidgetMode.NO_DATA -> null
            }

        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
