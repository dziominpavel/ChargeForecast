package com.example.chargeforecast.diag

import java.util.Locale

// Форматирование журнала: одна строка — один факт, формат key=value
// через «;». Отсутствующая величина — «-», а не пропуск поля: тогда
// число полей постоянно и разбор не съезжает.
object DiagText {

    fun tick(tick: DiagTick): String = buildString {
        append("t=").append(tick.atMs)
        append(";lvl=").append(tick.levelPct)
        append(";plug=").append(bit(tick.plugged))
        append(";chg=").append(bit(tick.charging))
        append(";full=").append(bit(tick.full))
        append(";type=").append(tick.chargeType)
        append(";ma=").append(tick.currentMa?.let { String.format(Locale.US, "%.0f", it) } ?: MISSING)
        append(";cnt=").append(tick.counterUah ?: MISSING)
        append(";cap=").append(tick.capacityUah ?: MISSING)
        append(";s1=").append(speed(tick.s1ShortPctPerMin))
        append(";s1l=").append(speed(tick.s1LongPctPerMin))
        append(";s2=").append(speed(tick.s2PctPerMin))
        append(";s3=").append(speed(tick.s3PctPerMin))
        append(";fus=").append(speed(tick.fusedPctPerMin))
        append(";base=").append(speed(tick.baseRatePctPerMin))
        append(";k=").append(String.format(Locale.US, "%.2f", tick.taperFactor))
        append(";raw=").append(tick.rawRemainingSec ?: MISSING)
        append(";shown=").append(tick.shownRemainingSec ?: MISSING)
        append(";eff=").append(speed(tick.shownSpeedPctPerMin))
        append(";acc=").append(tick.accuracy)
        append(";ref=").append(bit(tick.refining))
        append(";ph=").append(tick.phase)
    }

    fun event(atMs: Long, event: DiagEvent): String =
        "t=" + atMs + ";ev=" + event.kind.token + ";" + event.fields

    private fun speed(value: Float?): String =
        value?.let { String.format(Locale.US, "%.2f", it) } ?: MISSING

    private fun bit(value: Boolean): Int = if (value) 1 else 0

    private const val MISSING = "-"
}