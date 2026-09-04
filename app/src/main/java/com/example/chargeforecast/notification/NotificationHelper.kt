package com.example.chargeforecast.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.chargeforecast.MainActivity
import com.example.chargeforecast.R
import com.example.chargeforecast.forecast.ForecastAccuracy
import com.example.chargeforecast.session.SessionState

// Канал и тексты постоянного уведомления. Чистые функции форматирования
// вынесены отдельно, чтобы их можно было проверять без устройства.
object NotificationHelper {
    const val CHANNEL_ID = "charge_forecast"
    const val NOTIFICATION_ID = 1001
    const val FALLBACK_NOTIFICATION_ID = 1002

    // Троттлинг шторки: не чаще MIN, тикер дёргает не реже TICKER.
    const val MIN_UPDATE_INTERVAL_MS = 30_000L

    // Чистая функция для unit-теста троттлинга (задача 3.3).
    fun shouldNotify(lastNotifiedMs: Long?, nowMs: Long): Boolean {
        if (lastNotifiedMs == null) return true
        return nowMs - lastNotifiedMs >= MIN_UPDATE_INTERVAL_MS
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
        )
    }

    fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // Чистое форматирование строк уведомления — тестируемо без устройства.
    fun formatTitle(state: SessionState, context: Context): String {
        val level = state.snapshot?.levelPct
        return if (level != null) {
            context.getString(R.string.notif_title_level, level)
        } else {
            context.getString(R.string.app_name)
        }
    }

    fun formatText(state: SessionState, context: Context): String {
        val snapshot = state.snapshot ?: return context.getString(R.string.session_collecting)
        if (state.forecast.isFull) return context.getString(R.string.session_charged)
        val speed = state.forecast.speedPctPerMin
        val remaining = state.forecast.remainingMin
        if (speed == null || remaining == null) {
            return context.getString(R.string.session_collecting)
        }
        val accuracy = when (state.forecast.accuracy) {
            ForecastAccuracy.APPROXIMATE ->
                context.getString(R.string.session_accuracy_approx)
            ForecastAccuracy.PRECISE ->
                context.getString(R.string.session_accuracy_precise)
            ForecastAccuracy.COLLECTING ->
                context.getString(R.string.session_collecting)
        }
        return context.getString(
            R.string.notif_text_forecast,
            String.format(java.util.Locale.US, "%.1f", speed),
            formatRemaining(context, remaining),
            accuracy
        )
    }

    fun formatRemaining(context: Context, remainingMin: Long): String {
        if (remainingMin < 1L) return context.getString(R.string.time_less_minute)
        if (remainingMin < 60L) {
            return context.getString(R.string.time_minutes, remainingMin.toInt())
        }
        val hours = (remainingMin / 60).toInt()
        val mins = (remainingMin % 60).toInt()
        return if (mins == 0) {
            context.getString(R.string.time_hours, hours)
        } else {
            context.getString(R.string.time_hours_minutes, hours, mins)
        }
    }

    fun buildNotification(context: Context, state: SessionState): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_charge_bolt)
            .setContentTitle(formatTitle(state, context))
            .setContentText(formatText(state, context))
            .setContentIntent(contentIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    // Fallback на случай запрета старта FGS из фона (Android 12+):
    // обычное уведомление «откройте приложение». Без разрешения — молча нет.
    fun postOpenAppFallback(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_charge_bolt)
            .setContentTitle(context.getString(R.string.notif_fallback_title))
            .setContentText(context.getString(R.string.notif_fallback_text))
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.notify(FALLBACK_NOTIFICATION_ID, notification)
    }

    fun dismissFallback(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(FALLBACK_NOTIFICATION_ID)
    }
}
