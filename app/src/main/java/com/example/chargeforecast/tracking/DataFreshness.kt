package com.example.chargeforecast.tracking

// Порог устаревания последнего замера батареи — в одном месте (design D1).
// На зарядке опрос идёт раз в секунду: две минуты без замера означают сбой
// конвейера, а не «телефон просто стоит». В простое источник — системные
// broadcast об изменении уровня и температуры, и ночью они редки: короткий
// порог дал бы ложное «данные замерли» у телефона, который просто лежит.
object DataFreshness {

    const val CHARGING_MAX_AGE_MS = 2 * 60_000L
    const val IDLE_MAX_AGE_MS = 90 * 60_000L

    fun maxAgeMs(plugged: Boolean): Long =
        if (plugged) CHARGING_MAX_AGE_MS else IDLE_MAX_AGE_MS

    // Свежий замер — тот, что не старше порога своего режима.
    fun isFresh(dataAgeMs: Long, plugged: Boolean): Boolean =
        dataAgeMs <= maxAgeMs(plugged)
}
