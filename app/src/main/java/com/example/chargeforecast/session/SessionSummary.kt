package com.example.chargeforecast.session

import com.example.chargeforecast.battery.ChargeType

// Замороженная сводка завершённой сессии. Пишется в кэш ОДИН раз —
// в момент окончания сессии; текущая сессия никогда не перезаписывает
// её поля. Из неё же берётся приор скорости для следующей сессии.
data class SessionSummary(
    val endedAtMs: Long,
    val durationMs: Long,
    val gainedPct: Int,
    // Средняя скорость CC-фазы: сэмплы измеренной скорости ПОСЛЕ рампы
    // (+2 мин от старта) и ДО доводки (80%/CV) — рампа и медленный хвост
    // искажали бы среднее и приор следующей сессии.
    val avgCcSpeedPctPerMin: Float,
    val peakCurrentMa: Float?,
    val chargeType: ChargeType?
)
