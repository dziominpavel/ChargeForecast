package com.example.chargeforecast.session

import android.content.Context
import android.content.SharedPreferences
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.DischargeSample
import com.example.chargeforecast.forecast.DischargeState

// Крошечный кэш прошлой сессии в SharedPreferences (без зависимостей):
// ёмкость батареи почти не меняется — даёт точный instant с секунды ноль,
// сводка прошлой сессии — замороженной строкой и приором следующей.
class SessionCache(private val prefs: SharedPreferences) {

    fun saveCapacity(uah: Long) {
        if (uah > 0) prefs.edit().putLong(KEY_CAPACITY_UAH, uah).apply()
    }

    fun loadCapacity(): Long? =
        prefs.getLong(KEY_CAPACITY_UAH, -1L).takeIf { it > 0 }

    // Замороженная сводка пишется один раз — в конце сессии. Опора
    // скорости следующей сессии берётся из истории (средняя CC-фаза),
    // чтобы отсечение на доводке не занижало следующую сессию.
    fun saveSummary(summary: SessionSummary) {
        prefs.edit()
            .putLong(KEY_SUMMARY_ENDED_MS, summary.endedAtMs)
            .putLong(KEY_SUMMARY_DURATION_MS, summary.durationMs)
            .putInt(KEY_SUMMARY_GAINED_PCT, summary.gainedPct)
            .putFloat(KEY_SUMMARY_AVG_CC_SPEED, summary.avgCcSpeedPctPerMin)
            .putFloat(KEY_SUMMARY_PEAK_MA, summary.peakCurrentMa ?: -1f)
            .putString(KEY_SUMMARY_CHARGE_TYPE, summary.chargeType?.name)
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

    // История скоростей последних сессий: кольцевой буфер средних CC-фаз
    // на тип подключения (профиль «зарядка + кабель» — п.6 дорожной карты,
    // здесь ключ пока chargeType). Медиана вместо среднего: одна чудная
    // сессия не двигает оценку. Плоские ключи в стиле кривых.
    fun appendHistory(chargeType: ChargeType, avgCcSpeedPctPerMin: Float) {
        if (avgCcSpeedPctPerMin <= 0f) return
        val entries = loadHistoryRaw(chargeType).toMutableList()
        entries.add(avgCcSpeedPctPerMin)
        if (entries.size > HISTORY_CAPACITY) entries.removeAt(0)
        val editor = prefs.edit()
        entries.forEachIndexed { index, speed ->
            editor.putFloat(historyKey(chargeType, index), speed)
        }
        editor.putInt(historyCountKey(chargeType), entries.size)
        editor.apply()
    }

    // Агрегат истории для расчёта: медиана буфера. Ленивая миграция:
    // буфер пуст, а сводка есть (та же добрая сессия, тот же гейт) —
    // засеиваем из неё, чтобы история работала сразу после обновления.
    fun loadHistoryMedian(chargeType: ChargeType): Float? {
        if (loadHistoryRaw(chargeType).isEmpty()) {
            val summary = loadSummary() ?: return null
            if (summary.chargeType != chargeType) return null
            appendHistory(chargeType, summary.avgCcSpeedPctPerMin)
        }
        val entries = loadHistoryRaw(chargeType)
        if (entries.isEmpty()) return null
        val sorted = entries.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2f
        }
    }

    // Состояние оценщика разряда (design D4, D9): кольцо интервалов окна
    // плюс якоря последнего замера — переживает убийство процесса
    // OEM-оболочкой и перезагрузку. Промежуток через ребут отсекает сам
    // оценщик по метке времени. Прежние скаляры накопителя не
    // мигрируются: окно начинается пустым, а не с искажённого среднего.
    fun saveDischarge(state: DischargeState) {
        prefs.edit()
            .putString(KEY_DISCHARGE_SAMPLES, encodeSamples(state.samples))
            .putLong(KEY_DISCHARGE_LAST_SAMPLE_MS, state.lastSampleMs ?: -1L)
            .putLong(KEY_DISCHARGE_LAST_COUNTER_UAH, state.lastCounterUah ?: -1L)
            .apply()
    }

    fun loadDischarge(): DischargeState? {
        if (!prefs.contains(KEY_DISCHARGE_SAMPLES)) return null
        return DischargeState(
            samples = decodeSamples(prefs.getString(KEY_DISCHARGE_SAMPLES, null)),
            lastSampleMs = prefs
                .getLong(KEY_DISCHARGE_LAST_SAMPLE_MS, -1L).takeIf { it >= 0L },
            lastCounterUah = prefs
                .getLong(KEY_DISCHARGE_LAST_COUNTER_UAH, -1L).takeIf { it > 0L }
        )
    }

    private fun loadHistoryRaw(chargeType: ChargeType): List<Float> {
        val count = prefs.getInt(historyCountKey(chargeType), 0)
            .coerceIn(0, HISTORY_CAPACITY)
        return (0 until count).mapNotNull { index ->
            prefs.getFloat(historyKey(chargeType, index), -1f).takeIf { it > 0f }
        }
    }

    // Кольцо интервалов: плоская строка "atMs:durationMs:uah;...",
    // без зависимостей. Лимит корзин держит оценщик при обрезке окна.
    private fun encodeSamples(samples: List<DischargeSample>): String =
        samples.joinToString(SAMPLE_SEPARATOR) {
            "${it.atMs}$FIELD_SEPARATOR${it.durationMs}$FIELD_SEPARATOR${it.dischargedUah}"
        }

    private fun decodeSamples(raw: String?): List<DischargeSample> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SAMPLE_SEPARATOR).mapNotNull { entry ->
            val parts = entry.split(FIELD_SEPARATOR)
            if (parts.size != 3) return@mapNotNull null
            val atMs = parts[0].toLongOrNull() ?: return@mapNotNull null
            val durationMs = parts[1].toLongOrNull() ?: return@mapNotNull null
            val dischargedUah = parts[2].toLongOrNull() ?: return@mapNotNull null
            if (atMs < 0L || durationMs <= 0L || dischargedUah < 0L) return@mapNotNull null
            DischargeSample(atMs = atMs, durationMs = durationMs, dischargedUah = dischargedUah)
        }
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
        private const val KEY_SUMMARY_ENDED_MS = "summary_ended_ms"
        private const val KEY_SUMMARY_DURATION_MS = "summary_duration_ms"
        private const val KEY_SUMMARY_GAINED_PCT = "summary_gained_pct"
        private const val KEY_SUMMARY_AVG_CC_SPEED = "summary_avg_cc_speed"
        private const val KEY_SUMMARY_PEAK_MA = "summary_peak_current_ma"
        private const val KEY_SUMMARY_CHARGE_TYPE = "summary_charge_type"

        // Накопитель расхода (см. DischargeEstimator): кольцо интервалов
        // окна плюс якоря. Прежние ключи скаляров намеренно не читаются.
        private const val KEY_DISCHARGE_SAMPLES = "discharge_samples_v2"
        private const val KEY_DISCHARGE_LAST_SAMPLE_MS = "discharge_last_sample_ms"
        private const val KEY_DISCHARGE_LAST_COUNTER_UAH = "discharge_last_counter_uah"
        private const val SAMPLE_SEPARATOR = ";"
        private const val FIELD_SEPARATOR = ":"

        // Ёмкость истории: последних N сессий на тип подключения.
        const val HISTORY_CAPACITY = 5

        private fun curveKey(chargeType: ChargeType, index: Int) =
            "curve_${chargeType.name}_$index"

        private fun curveExistsKey(chargeType: ChargeType) =
            "curve_${chargeType.name}_exists"

        private fun historyKey(chargeType: ChargeType, index: Int) =
            "hist_${chargeType.name}_$index"

        private fun historyCountKey(chargeType: ChargeType) =
            "hist_${chargeType.name}_count"

        fun create(context: Context): SessionCache =
            SessionCache(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
