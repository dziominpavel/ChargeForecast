package com.example.chargeforecast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.chargeforecast.notification.NotificationHelper

// Автозапуск/останов сервиса по подключению кабеля.
// Манифестный receiver: POWER_CONNECTED/DISCONNECTED доставляются
// даже убитому приложению. На Android 12+ старт FGS из фона может
// быть запрещён — тогда показываем fallback-уведомление и честно
// сообщаем об ошибке через монитор при следующем открытии.
class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                try {
                    ChargeForecastService.start(context.applicationContext)
                } catch (_: SecurityException) {
                    NotificationHelper.postOpenAppFallback(context.applicationContext)
                    (context.applicationContext as? ChargeForecastApplication)
                        ?.sessionMonitor()
                        ?.reportError(context.getString(R.string.error_service_denied))
                }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                NotificationHelper.dismissFallback(context.applicationContext)
                ChargeForecastService.stop(context.applicationContext)
            }
        }
    }
}
