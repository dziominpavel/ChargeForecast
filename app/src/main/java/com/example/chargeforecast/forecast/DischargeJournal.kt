package com.example.chargeforecast.forecast

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Журнал наблюдений (design D5): человекочитаемые строки о каждом замере —
// что видел датчик и что вышло в окне. «Откуда 40 минут» нужно уметь
// разобрать, а экрана настроек нет — выгрузка файла через adb pull.
object DischargeJournalText {

    // CSV-строка: время, уровень, счётчик, дельта, длина интервала,
    // наблюдённое время в окне, средний ток, остаток, результат.
    // Остаток и результат берутся из последнего состояния движка: движок
    // считает оценку, журнал только подписывает её входными величинами.
    fun line(observation: DischargeObservation, remainingSec: Long?): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(observation.wallClockMs))
        val estimateTag = if (remainingSec != null) "estimate" else "collecting"
        return listOf(
            time,
            "lvl=${observation.levelPct}%",
            observation.counterUah?.let { "counter=$it" } ?: "counter=-",
            observation.intervalUah?.let { "delta=${it}uAh" } ?: "delta=-",
            observation.intervalMs?.let { "dt=${it / 1_000}s" } ?: "dt=-",
            "window=${observation.trackedMs / 60_000}m",
            observation.averageCurrentUa
                ?.let { String.format(Locale.US, "avg=%.0fmA", it / 1_000f) }
                ?: "avg=-",
            remainingSec?.let { "left=${it}s" } ?: "left=-",
            "${observation.reason}/$estimateTag"
        ).joinToString(";")
    }
}

// Писатель журнала: один файл внутреннего хранилища с ротацией по размеру.
// Ротация одноуровневая (свежий → .old): журнал диагностика, а не история.
// Тонкий троттлинг по времени: частые замеры дублировали бы один и тот же
// кадр, а всплески причин (нет счётчика, аномалия драйвера) пишутся всегда.
class DischargeJournal(
    private val file: File,
    private val maxBytes: Long = MAX_BYTES,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val wallClockMs: () -> Long = { System.currentTimeMillis() }
) {

    // Последняя оценка движка — подписывается строкой журнала.
    @Volatile
    private var remainingSec: Long? = null

    @Volatile
    private var lastWrittenMs: Long? = null

    fun onEstimate(remainingSec: Long?) {
        this.remainingSec = remainingSec
    }

    @Synchronized
    fun onObservation(observation: DischargeObservation) {
        // Редкие состояния пишем всегда, частые — не чаще раза в минуту.
        val always = observation.reason == DischargeObservationReason.NO_COUNTER ||
            observation.reason == DischargeObservationReason.COUNTER_GREW ||
            observation.reason == DischargeObservationReason.BAD_INTERVAL
        val now = wallClockMs()
        // Первая строка пишется всегда: иначе запуск, пришедший ровно на
        // границу окна, мог бы её пропустить.
        val throttled = lastWrittenMs?.let { now - it < minIntervalMs } ?: false
        if (!always && throttled) return
        lastWrittenMs = now
        runCatching { append(DischargeJournalText.line(observation, remainingSec)) }
    }

    private fun append(line: String) {
        rotateIfNeeded()
        file.appendText(line + "\n")
    }

    private fun rotateIfNeeded() {
        if (!file.exists() || file.length() < maxBytes) return
        val old = File(file.parentFile, "${file.name}.old")
        if (old.exists()) old.delete()
        file.renameTo(old)
    }

    companion object {
        // Полтора мегабайта — около суток наблюдений при троттлинге.
        const val MAX_BYTES = 1_500_000L
        const val MIN_INTERVAL_MS = 60_000L
    }
}
