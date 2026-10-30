package com.example.chargeforecast.session

import android.content.Context
import android.content.SharedPreferences
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.DischargeSample
import com.example.chargeforecast.forecast.DischargeState

// Машинное, волатильное состояние: привязано к конкретной загрузке и
// батарее, поэтому не переносится через backup. Отдельный файл
// session_runtime.xml; сводка, история и кривая остаются в SessionCache.
class SessionRuntimeStore(private val prefs: SharedPreferences) {

    fun saveBootIdentity(identity: BootIdentity) {
        prefs.edit()
            .putInt(KEY_BOOT_COUNT, identity.bootCount ?: UNKNOWN_BOOT_COUNT)
            .putLong(KEY_BOOT_WALL_CLOCK_MS, identity.wallClockMs)
            .putLong(KEY_BOOT_ELAPSED_MS, identity.elapsedRealtimeMs)
            .apply()
    }

    fun loadBootIdentity(): BootIdentity? {
        if (!prefs.contains(KEY_BOOT_COUNT)) return null
        return BootIdentity(
            bootCount = prefs.getInt(KEY_BOOT_COUNT, UNKNOWN_BOOT_COUNT)
                .takeIf { it >= 0 },
            wallClockMs = prefs.getLong(KEY_BOOT_WALL_CLOCK_MS, 0L),
            elapsedRealtimeMs = prefs.getLong(KEY_BOOT_ELAPSED_MS, 0L)
        )
    }

    // Ёмкость батареи привязана к конкретному устройству: на новом
    // телефоне чужая ёмкость испортила бы перевод тока в проценты.
    fun saveCapacity(uah: Long) {
        if (uah > 0) prefs.edit().putLong(KEY_CAPACITY_UAH, uah).apply()
    }

    fun loadCapacity(): Long? =
        prefs.getLong(KEY_CAPACITY_UAH, -1L).takeIf { it > 0L }

    // Состояние оценщика разряда: кольцо интервалов и якоря последнего
    // замера. bootCount отделяет шкалу elapsedRealtime одной загрузки
    // от другой.
    fun saveDischarge(state: DischargeState) {
        prefs.edit()
            .putString(KEY_DISCHARGE_SAMPLES, encodeSamples(state.samples))
            .putLong(KEY_DISCHARGE_LAST_SAMPLE_MS, state.lastSampleMs ?: -1L)
            .putLong(KEY_DISCHARGE_LAST_COUNTER_UAH, state.lastCounterUah ?: -1L)
            .putInt(KEY_DISCHARGE_BOOT_COUNT, state.bootCount ?: UNKNOWN_BOOT_COUNT)
            .apply()
    }

    fun loadDischarge(): DischargeState? {
        if (!prefs.contains(KEY_DISCHARGE_SAMPLES)) return null
        return DischargeState(
            samples = decodeSamples(prefs.getString(KEY_DISCHARGE_SAMPLES, null)),
            lastSampleMs = prefs
                .getLong(KEY_DISCHARGE_LAST_SAMPLE_MS, -1L).takeIf { it >= 0L },
            lastCounterUah = prefs
                .getLong(KEY_DISCHARGE_LAST_COUNTER_UAH, -1L).takeIf { it > 0L },
            bootCount = prefs.getInt(KEY_DISCHARGE_BOOT_COUNT, UNKNOWN_BOOT_COUNT)
                .takeIf { it >= 0 }
        )
    }

    fun saveActiveSession(snapshot: ActiveSessionSnapshot) {
        prefs.edit()
            .putString(KEY_ACTIVE_ID, snapshot.id)
            .putInt(KEY_ACTIVE_BOOT_COUNT, snapshot.bootCount ?: UNKNOWN_BOOT_COUNT)
            .putLong(KEY_ACTIVE_STARTED_AT_MS, snapshot.startedAtMs)
            .putInt(KEY_ACTIVE_START_LEVEL_PCT, snapshot.startLevelPct)
            .putString(KEY_ACTIVE_CHARGE_TYPE, snapshot.chargeType.name)
            .putFloat(KEY_ACTIVE_CC_SPEED_SUM, snapshot.ccSpeedSum)
            .putInt(KEY_ACTIVE_CC_SAMPLE_COUNT, snapshot.ccSampleCount)
            .putLong(KEY_ACTIVE_PEAK_CURRENT_UA, snapshot.peakCurrentUa ?: -1L)
            .putInt(KEY_ACTIVE_LAST_LEVEL_PCT, snapshot.lastLevelPct)
            .putLong(KEY_ACTIVE_LAST_UPDATED_AT_MS, snapshot.lastUpdatedAtMs)
            .apply()
    }

    fun loadActiveSession(): ActiveSessionSnapshot? {
        val id = prefs.getString(KEY_ACTIVE_ID, null)
            ?.takeIf { it.isNotBlank() } ?: return null
        val startedAtMs = prefs.getLong(KEY_ACTIVE_STARTED_AT_MS, -1L)
        val startLevelPct = prefs.getInt(KEY_ACTIVE_START_LEVEL_PCT, -1)
        val lastLevelPct = prefs.getInt(KEY_ACTIVE_LAST_LEVEL_PCT, -1)
        val lastUpdatedAtMs = prefs.getLong(KEY_ACTIVE_LAST_UPDATED_AT_MS, -1L)
        if (startedAtMs < 0L ||
            startLevelPct !in 0..100 ||
            lastLevelPct !in 0..100 ||
            lastUpdatedAtMs < startedAtMs
        ) {
            return null
        }
        val chargeType = prefs.getString(KEY_ACTIVE_CHARGE_TYPE, null)
            ?.let { raw -> runCatching { ChargeType.valueOf(raw) }.getOrNull() }
            ?: return null
        val ccSampleCount = prefs.getInt(KEY_ACTIVE_CC_SAMPLE_COUNT, 0)
            .coerceAtLeast(0)
        return ActiveSessionSnapshot(
            id = id,
            bootCount = prefs.getInt(KEY_ACTIVE_BOOT_COUNT, UNKNOWN_BOOT_COUNT)
                .takeIf { it >= 0 },
            startedAtMs = startedAtMs,
            startLevelPct = startLevelPct,
            chargeType = chargeType,
            ccSpeedSum = prefs.getFloat(KEY_ACTIVE_CC_SPEED_SUM, 0f)
                .takeIf { it.isFinite() && it >= 0f } ?: 0f,
            ccSampleCount = ccSampleCount,
            peakCurrentUa = prefs.getLong(KEY_ACTIVE_PEAK_CURRENT_UA, -1L)
                .takeIf { it > 0L },
            lastLevelPct = lastLevelPct,
            lastUpdatedAtMs = lastUpdatedAtMs
        )
    }

    fun clearActiveSession() {
        prefs.edit()
            .remove(KEY_ACTIVE_ID)
            .remove(KEY_ACTIVE_BOOT_COUNT)
            .remove(KEY_ACTIVE_STARTED_AT_MS)
            .remove(KEY_ACTIVE_START_LEVEL_PCT)
            .remove(KEY_ACTIVE_CHARGE_TYPE)
            .remove(KEY_ACTIVE_CC_SPEED_SUM)
            .remove(KEY_ACTIVE_CC_SAMPLE_COUNT)
            .remove(KEY_ACTIVE_PEAK_CURRENT_UA)
            .remove(KEY_ACTIVE_LAST_LEVEL_PCT)
            .remove(KEY_ACTIVE_LAST_UPDATED_AT_MS)
            .apply()
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
            if (atMs < 0L || durationMs <= 0L || dischargedUah < 0L) {
                return@mapNotNull null
            }
            DischargeSample(atMs = atMs, durationMs = durationMs, dischargedUah = dischargedUah)
        }
    }

    companion object {
        const val PREFS_NAME = "session_runtime"

        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_BOOT_WALL_CLOCK_MS = "boot_wall_clock_ms"
        private const val KEY_BOOT_ELAPSED_MS = "boot_elapsed_realtime_ms"

        private const val KEY_CAPACITY_UAH = "capacity_uah"

        private const val KEY_DISCHARGE_SAMPLES = "discharge_samples_v2"
        private const val KEY_DISCHARGE_LAST_SAMPLE_MS = "discharge_last_sample_ms"
        private const val KEY_DISCHARGE_LAST_COUNTER_UAH = "discharge_last_counter_uah"
        private const val KEY_DISCHARGE_BOOT_COUNT = "discharge_boot_count"
        private const val SAMPLE_SEPARATOR = ";"
        private const val FIELD_SEPARATOR = ":"

        private const val KEY_ACTIVE_ID = "active_session_id"
        private const val KEY_ACTIVE_BOOT_COUNT = "active_session_boot_count"
        private const val KEY_ACTIVE_STARTED_AT_MS = "active_session_started_at_ms"
        private const val KEY_ACTIVE_START_LEVEL_PCT = "active_session_start_level_pct"
        private const val KEY_ACTIVE_CHARGE_TYPE = "active_session_charge_type"
        private const val KEY_ACTIVE_CC_SPEED_SUM = "active_session_cc_speed_sum"
        private const val KEY_ACTIVE_CC_SAMPLE_COUNT = "active_session_cc_sample_count"
        private const val KEY_ACTIVE_PEAK_CURRENT_UA = "active_session_peak_current_ua"
        private const val KEY_ACTIVE_LAST_LEVEL_PCT = "active_session_last_level_pct"
        private const val KEY_ACTIVE_LAST_UPDATED_AT_MS = "active_session_last_updated_at_ms"

        private const val UNKNOWN_BOOT_COUNT = -1

        fun create(context: Context): SessionRuntimeStore =
            SessionRuntimeStore(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            )
    }
}
