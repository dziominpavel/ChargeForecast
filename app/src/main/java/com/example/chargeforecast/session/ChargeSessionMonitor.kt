package com.example.chargeforecast.session

import android.os.SystemClock
import com.example.chargeforecast.battery.BatteryMonitor
import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.SysfsCapacity
import com.example.chargeforecast.forecast.ChargeForecast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

// Состояние текущей сессии зарядки — единый источник правды для экрана.
data class SessionState(
    val snapshot: BatterySnapshot?,
    val forecast: ChargeForecast,
    val sessionAgeMs: Long,
    // Видимая пользователю ошибка (датчики и т.п.).
    val error: String? = null
) {
    val isPlugged: Boolean get() = snapshot?.isPlugged == true
}

// Склеивает монитор батареи и чистый движок сессии.
// Живёт на уровне Application, отдаёт состояние экрану.
// Все расчёты — на SystemClock.elapsedRealtime: подстройка системных
// часов сетью не скачет скорость и возраст сессии (см. design D7).
class ChargeSessionMonitor(
    private val batteryMonitor: BatteryMonitor,
    scope: CoroutineScope,
    clock: () -> Long = SystemClock::elapsedRealtime,
    cache: SessionCache? = null
) {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val sysfsCapacityUah: Long? by lazy { SysfsCapacity.readDesignCapacityUah() }
    private val engine = SessionEngine(cache) { sysfsCapacityUah }

    fun reportError(message: String?) {
        _error.value = message
    }

    // Тикер: переизлучаем состояние и без broadcast — для «Измеряю…»
    // и обновления цифр каждую секунду. На зарядке — раз в секунду
    // (телефон у розетки), в простое — раз в 30 секунд (см. design D8).
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(tickerDelayMs(batteryMonitor.snapshots.value?.isPlugged == true))
        }
    }

    val state: StateFlow<SessionState> =
        combine(batteryMonitor.snapshots, ticker) { snapshot, nowMs ->
            engine.update(snapshot, nowMs).copy(error = _error.value)
        }.stateIn(scope, SharingStarted.Eagerly, engine.update(null, clock()))

    companion object {
        const val TICKER_ACTIVE_MS = 1_000L
        const val TICKER_IDLE_MS = 30_000L

        // Чистая функция для unit-теста интервалов по состоянию.
        fun tickerDelayMs(plugged: Boolean): Long =
            if (plugged) TICKER_ACTIVE_MS else TICKER_IDLE_MS
    }
}
