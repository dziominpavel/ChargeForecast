package com.example.chargeforecast

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.chargeforecast.notification.NotificationHelper
import kotlinx.coroutines.launch

// Постоянное уведомление в шторке на время зарядки.
// Обновления дёргаются состоянием монитора (broadcast + тикер 30 сек)
// и дополнительно троттлятся, чтобы не душить систему.
class ChargeForecastService : LifecycleService() {

    private var lastNotifiedMs: Long? = null

    private fun heartbeat(nowMs: Long) {
        try {
            (application as? ChargeForecastApplication)
                ?.sessionCache?.saveHeartbeat(nowMs)
        } catch (_: Exception) {
            // Пульс — диагностика, не роняем сервис из-за него.
        }
    }

    override fun onCreate() {
        super.onCreate()
        val monitor = application.sessionMonitor()
        monitor.reportError(null)
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildNotification(this, monitor.state.value)
        )
        lastNotifiedMs = System.currentTimeMillis()
        heartbeat(lastNotifiedMs!!)
        lifecycleScope.launch {
            monitor.state.collect { state ->
                if (!state.isPlugged) {
                    stopSelf()
                    return@collect
                }
                val now = System.currentTimeMillis()
                if (NotificationHelper.shouldNotify(lastNotifiedMs, now)) {
                    lastNotifiedMs = now
                    try {
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE)
                            as? NotificationManager
                        manager?.notify(
                            NotificationHelper.NOTIFICATION_ID,
                            NotificationHelper.buildNotification(this@ChargeForecastService, state)
                        )
                        heartbeat(now)
                    } catch (_: SecurityException) {
                        // Нет разрешения — шторки не будет, экран покажет сам.
                        monitor.reportError(
                            getString(R.string.error_notif_denied)
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        NotificationHelper.dismissFallback(this)
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            NotificationHelper.ensureChannel(context)
            ContextCompat.startForegroundService(
                context, Intent(context, ChargeForecastService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChargeForecastService::class.java))
        }
    }
}
