package com.example.chargeforecast.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Рестарт слежения после перезагрузки и после обновления приложения:
// иначе вставка кабеля прошла бы мимо, пока пользователь не откроет
// приложение вручную. Оба broadcast — защищённые системные, доставляются
// и экземпляру с android:exported="false".
class TrackingRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> ChargeForecastService.start(context)
        }
    }
}
