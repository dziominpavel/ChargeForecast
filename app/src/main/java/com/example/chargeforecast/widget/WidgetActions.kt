package com.example.chargeforecast.widget

import android.app.PendingIntent
import com.example.chargeforecast.service.ChargeForecastService

// Действия виджета. Возобновление слежения — прямой запуск foreground-сервиса
// из пользовательского тапа: платформа разрешает FGS-старт из действия
// пользователя, поэтому приёмник-посредник не нужен (design D3).
object WidgetActions {

    // Разные коды запросов у открытия приложения и возобновления: один
    // PendingIntent не должен перезаписывать другой.
    const val OPEN_APP_REQUEST_CODE = 0
    const val RESUME_TRACKING_REQUEST_CODE = 1

    // Интент неизменяемый (цель фиксирована) и обновляемый: после
    // перезапуска процесса действие должно вести на живой сервис.
    const val RESUME_FLAGS: Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    // Компонент, который поднимает слежение. Вынесен отдельно, чтобы
    // сборку действия можно было проверить JVM-тестом.
    fun resumeTarget(): Class<*> = ChargeForecastService::class.java
}
