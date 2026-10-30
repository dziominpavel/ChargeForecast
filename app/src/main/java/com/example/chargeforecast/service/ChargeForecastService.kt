package com.example.chargeforecast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.chargeforecast.ChargeForecastApplication
import com.example.chargeforecast.MainActivity
import com.example.chargeforecast.R
import com.example.chargeforecast.tracking.TrackingFailure
import com.example.chargeforecast.tracking.TrackingStatus

// Постоянный сервис слежения за зарядкой (design D3, D5).
//
// Единственный способ поймать вставку кабеля без открытого приложения:
// manifest-receiver неявный ACTION_POWER_CONNECTED не получает (нет в
// исключениях неявных broadcast с API 26+), а foreground service из фона
// с API 31+ не поднимается вовсе. Значит процесс обязан жить до
// подключения — держим foreground-сервисом специального назначения.
//
// Владельцы состояния — в Application (design D6): сервис лишь читает
// тот же экземпляр движка, что экран и виджет. Второго движка нет.
// Состояние сервис наружу не отражает: уведомление статично (design D8).
class ChargeForecastService : Service() {

    private var powerReceiver: BroadcastReceiver? = null
    private var foregroundStarted = false

    private val app: ChargeForecastApplication
        get() = application as ChargeForecastApplication

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Платформа отводит ~5 с от startForegroundService() до
        // startForeground() — иначе крэш. Поэтому сразу в onCreate().
        // Тип указываем явно: с API 34 специальный FGS требует его
        // подтверждения, ServiceCompat на старых версиях его игнорирует.
        // Текст уведомления один на всё время жизни сервиса (design D8):
        // смена состояния зарядки шторку не трогает. Прогрев состояния
        // остаётся в Application — там же подписчики (экран, виджет, журнал).
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            foregroundStarted = true
            app.trackingHealthStore.markRunning()
            registerPowerReceiver()
        } catch (e: SecurityException) {
            // Нет нужного разрешения — это не «ОС вредничает», а
            // отдельное состояние с действием «Настройки».
            Log.w(TAG, "startForeground denied", e)
            app.trackingHealthStore.markNoPermission()
            stopSelf()
        } catch (e: Exception) {
            // Запрет foreground/типа сервиса не должен ронять процесс:
            // фиксируем причину и отдаём её экрану и виджету.
            Log.w(TAG, "startForeground failed", e)
            app.trackingHealthStore.markBlocked(TrackingFailure.FOREGROUND_BLOCKED)
            stopSelf()
        }
    }

    // START_STICKY: если оболочка всё же выгрузит процесс — система
    // перезапустит сервис, слежение восстановится без пользователя.
    // После неудачного foreground перезапуск бессмысленен и был бы
    // циклом падений — тогда START_NOT_STICKY.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            stopSelf()
            return START_NOT_STICKY
        }
        app.trackingHealthStore.markRunning()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        powerReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Уже отписан — не ошибка.
            }
        }
        powerReceiver = null
        // Ошибку foreground не затираем: markStopped сам сохраняет
        // BLOCKED_BY_OS/NO_PERMISSION. Нормальная остановка сервиса
        // становится честным «слежение остановлено».
        if (foregroundStarted) {
            app.trackingHealthStore.markStopped()
        }
        foregroundStarted = false
        super.onDestroy()
    }

    // Событие подключения — наше пробуждение конвейера. Опрос в простое
    // остановлен (design D3), поэтому без этого тика ждать пришлось бы
    // до следующего системного broadcast о батарее.
    private fun registerPowerReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                if (action != Intent.ACTION_POWER_CONNECTED &&
                    action != Intent.ACTION_POWER_DISCONNECTED
                ) {
                    return
                }
                app.batteryMonitor.refresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // Оба action — защищённые системные broadcast: система доставляет
        // их и NOT_EXPORTED-подписчику, другим приложениям подделать нельзя.
        ContextCompat.registerReceiver(
            this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        powerReceiver = receiver
    }

    private fun createNotificationChannel() {
        val manager = notificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        // Важность канала после создания не меняется — для отката (design
        // D8) канал пересоздаётся: старый удаляется, важность новая.
        if (existing != null && existing.importance == CHANNEL_IMPORTANCE) return
        if (existing != null) manager.deleteNotificationChannel(CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_tracking),
                CHANNEL_IMPORTANCE
            ).apply {
                description = getString(R.string.notification_channel_tracking_desc)
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
    }

    // Содержимое без текста: только иконка и имя приложения — служебная
    // строка «Слежение за зарядкой активно» не несла пользы и была видна
    // в шторке вопреки IMPORTANCE_NONE (спека background-tracking).
    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle(getString(R.string.app_name))
            .setContentIntent(openAppIntent())
            // ongoing: не смахивается пользователем — сервис жив, пока
            // не выгрузится сам.
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val TAG = "ChargeForecastService"
        private const val CHANNEL_ID = "charge_tracking"
        private const val NOTIFICATION_ID = 4210

        // Канал без возможности показа (design D8): сервис остаётся
        // foreground, уведомление пользователю не показывается. Если на
        // устройстве это мешает слежению (оболочка убьёт сервис или
        // выведет своё служебное уведомление) — по чек-листу D8 значение
        // возвращается к IMPORTANCE_MIN: откат одной строкой.
        private const val CHANNEL_IMPORTANCE = NotificationManager.IMPORTANCE_NONE

        // Поднять слежение. Вызывается при открытии приложения, после
        // перезагрузки и после обновления пакета. Старт из фона с API 31+
        // может бросить ForegroundServiceStartNotAllowedException; оболочка
        // (EMUI) может ограничить и разрешённый контекст.
        fun start(context: Context) {
            val app = context.applicationContext as? ChargeForecastApplication ?: return
            val store = app.trackingHealthStore
            // Не откатываем подтверждённый RUNNING в STARTING, но и не
            // пропускаем вызов: сервис мог умереть, оставив статус.
            if (store.health.value.status != TrackingStatus.RUNNING) {
                store.markStarting()
            }
            try {
                context.startForegroundService(
                    Intent(context, ChargeForecastService::class.java)
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "startForegroundService denied", e)
                store.markNoPermission()
            } catch (e: Exception) {
                // Спека background-tracking: ошибка не всплывает падением,
                // а становится видимым статусом для экрана и виджета.
                Log.w(TAG, "startForegroundService failed", e)
                store.markBlocked(TrackingFailure.classify(e))
            }
        }
    }
}
