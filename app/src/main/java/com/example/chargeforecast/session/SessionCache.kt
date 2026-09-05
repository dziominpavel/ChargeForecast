package com.example.chargeforecast.session

import android.content.Context
import android.content.SharedPreferences
import com.example.chargeforecast.battery.ChargeType

// Крошечный кэш прошлой сессии в SharedPreferences (без зависимостей):
// ёмкость батареи почти не меняется — даёт точный instant с секунды ноль,
// сводка прошлой сессии — замороженной строкой и приором следующей.
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

    // Замороженная сводка пишется один раз — в конце сессии. Приор
    // скорости следующей сессии берётся из неё же (средняя CC-фаза),
    // чтобы отсечение на доводке не занижало следующую сессию.
    fun saveSummary(summary: SessionSummary) {
        prefs.edit()
            .putLong(KEY_SUMMARY_ENDED_MS, summary.endedAtMs)
            .putLong(KEY_SUMMARY_DURATION_MS, summary.durationMs)
            .putInt(KEY_SUMMARY_GAINED_PCT, summary.gainedPct)
            .putFloat(KEY_SUMMARY_AVG_CC_SPEED, summary.avgCcSpeedPctPerMin)
            .putFloat(KEY_SUMMARY_PEAK_MA, summary.peakCurrentMa ?: -1f)
            .putString(KEY_SUMMARY_CHARGE_TYPE, summary.chargeType?.name)
            .putFloat(KEY_LAST_SPEED, summary.avgCcSpeedPctPerMin)
            .apply()
    }

    fun loadSummary(): SessionSummary? {
        val avg = prefs.getFloat(KEY_SUMMARY_AVG_CC_SPEED, -1f).takeIf { it > 0f } ?: return null
        val type = prefs.getString(KEY_SUMMARY_CHARGE_TYPE, null)
        return SessionSummary(
            endedAtMs = prefs.getLong(KEY_SUMMARY_ENDED_MS, 0L),
            durationMs = prefs.getLong(KEY_SUMMARY_DURATION_MS, 0L),
            gainedPct = prefs.getInt(KEY_SUMMARY_GAINED_PCT, 0),
            avgCcSpeedPctPerMin = avg,
            peakCurrentMa = prefs.getFloat(KEY_SUMMARY_PEAK_MA, -1f).takeIf { it > 0f },
            chargeType = type?.let { runCatching { ChargeType.valueOf(it) }.getOrNull() }
        )
    }

    // Кривая доводки по типу подключения (профиль «зарядка + кабель»,
    // фундамент п.6 дорожной карты): плоские ключи, без зависимостей.
    fun saveCurve(chargeType: ChargeType, factors: FloatArray) {
        val editor = prefs.edit()
        factors.forEachIndexed { index, f -> editor.putFloat(curveKey(chargeType, index), f) }
        editor.putBoolean(curveExistsKey(chargeType), true)
        editor.apply()
    }

    fun loadCurve(chargeType: ChargeType): FloatArray? {
        if (!prefs.getBoolean(curveExistsKey(chargeType), false)) return null
        val default = com.example.chargeforecast.forecast.TaperCurve.DEFAULT_FACTORS
        return FloatArray(default.size) { i -> prefs.getFloat(curveKey(chargeType, i), default[i]) }
    }

    companion object {
        private const val PREFS_NAME = "charge_session_cache"
        private const val KEY_CAPACITY_UAH = "capacity_uah"
        private const val KEY_LAST_SPEED = "last_speed_pct_per_min"
        private const val KEY_SUMMARY_ENDED_MS = "summary_ended_ms"
        private const val KEY_SUMMARY_DURATION_MS = "summary_duration_ms"
        private const val KEY_SUMMARY_GAINED_PCT = "summary_gained_pct"
        private const val KEY_SUMMARY_AVG_CC_SPEED = "summary_avg_cc_speed"
        private const val KEY_SUMMARY_PEAK_MA = "summary_peak_current_ma"
        private const val KEY_SUMMARY_CHARGE_TYPE = "summary_charge_type"

        private fun curveKey(chargeType: ChargeType, index: Int) =
            "curve_${chargeType.name}_$index"

        private fun curveExistsKey(chargeType: ChargeType) =
            "curve_${chargeType.name}_exists"

        fun create(context: Context): SessionCache =
            SessionCache(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
