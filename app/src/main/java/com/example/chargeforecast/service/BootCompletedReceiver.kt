package com.example.chargeforecast.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Рестарт слежения после перезагрузки — иначе вставка кабеля после
// ребута прошла бы мимо: приложение не открыто, живого процесса нет.
// BOOT_COMPLETED — защищённый broadcast, доставляется и экземпляру с
// android:exported="false".
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ChargeForecastService.start(context)
    }
}
