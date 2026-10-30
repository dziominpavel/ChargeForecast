package com.example.chargeforecast.session

import com.example.chargeforecast.battery.ChargeType

// Live-снимок активной сессии зарядки. Хранит только то, что нельзя
// восстановить после смерти процесса: старт, уровни, тип подключения,
// накопления CC-фазы и пик тока. Транзиентные спидометры, EMA и фильтр
// показа после восстановления пересобираются с первых секунд.
data class ActiveSessionSnapshot(
    // UUID сессии — ключ идемпотентной финализации сводки.
    val id: String,
    // Загрузка, в которой идёт сессия; null — BOOT_COUNT недоступен.
    val bootCount: Int?,
    // Старт сессии (elapsedRealtime).
    val startedAtMs: Long,
    val startLevelPct: Int,
    val chargeType: ChargeType,
    // Накопленная сумма скоростей CC-фазы и число сэмплов.
    val ccSpeedSum: Float,
    val ccSampleCount: Int,
    val peakCurrentUa: Long?,
    // Последний известный уровень и время снимка (elapsedRealtime).
    val lastLevelPct: Int,
    val lastUpdatedAtMs: Long
)

// Сводка по снимку — для финализации после ребута или отключения кабеля
// при мёртвом процессе. Порог качества тот же, что у живой сессии:
// средняя CC-скорость и набранные проценты.
internal fun ActiveSessionSnapshot.toSummary(): SessionSummary? {
    if (ccSampleCount <= 0) return null
    val average = ccSpeedSum / ccSampleCount
    if (average < SessionEngine.SUMMARY_MIN_AVG_PCT_PER_MIN) return null
    val gained = (lastLevelPct - startLevelPct).coerceAtLeast(0)
    if (gained < SessionEngine.SUMMARY_MIN_GAINED_PCT) return null
    return SessionSummary(
        endedAtMs = lastUpdatedAtMs,
        durationMs = (lastUpdatedAtMs - startedAtMs).coerceAtLeast(0L),
        gainedPct = gained,
        avgCcSpeedPctPerMin = average,
        peakCurrentMa = peakCurrentUa?.let { it / 1000f },
        chargeType = chargeType,
        id = id
    )
}
