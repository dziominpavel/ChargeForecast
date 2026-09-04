package com.example.chargeforecast.session

import android.content.Context
import android.content.SharedPreferences

// Крошечный кэш прошлой сессии в SharedPreferences (без зависимостей):
// ёмкость батареи почти не меняется — даёт точный instant с секунды ноль,
// скорость прошлой сессии — справочной строкой, пока текущая уточняется.
class SessionCache(private val prefs: SharedPreferences) {

    fun saveCapacity(uah: Long) {
        if (uah > 0) prefs.edit().putLong(KEY_CAPACITY_UAH, uah).apply()
    }

    fun loadCapacity(): Long? =
        prefs.getLong(KEY_CAPACITY_UAH, -1L).takeIf { it > 0 }

    fun saveLastSpeed(pctPerMin: Float) {
        if (pctPerMin > 0f) prefs.edit().putFloat(KEY_LAST_SPEED, pctPerMin).apply()
    }

    fun loadLastSpeed(): Float? =
        prefs.getFloat(KEY_LAST_SPEED, -1f).takeIf { it > 0f }

    // Пульс шторки: сервис пишет метку каждого обновления уведомления.
    // Экран по нему понимает, жива ли шторка, и честно жалуется, если нет.
    fun saveHeartbeat(nowMs: Long) {
        prefs.edit().putLong(KEY_HEARTBEAT_MS, nowMs).apply()
    }

    fun loadHeartbeat(): Long? =
        prefs.getLong(KEY_HEARTBEAT_MS, -1L).takeIf { it > 0L }

    companion object {
        private const val PREFS_NAME = "charge_session_cache"
        private const val KEY_CAPACITY_UAH = "capacity_uah"
        private const val KEY_LAST_SPEED = "last_speed_pct_per_min"
        private const val KEY_HEARTBEAT_MS = "last_notif_update_ms"

        fun create(context: Context): SessionCache =
            SessionCache(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
