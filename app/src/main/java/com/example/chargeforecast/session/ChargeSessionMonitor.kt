package com.example.chargeforecast.session

import com.example.chargeforecast.battery.BatteryMonitor
import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.SysfsCapacity
import com.example.chargeforecast.forecast.CapacityTracker
import com.example.chargeforecast.forecast.ChargeForecaster
import com.example.chargeforecast.forecast.ChargeForecast
import com.example.chargeforecast.forecast.ChargeSpeedCalculator
import com.example.chargeforecast.forecast.CounterSpeedometer
import com.example.chargeforecast.forecast.InstantSpeedEstimator
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

// Состояние текущей сессии зарядки — единый источник правды
// для шторки (сервис) и экрана (ViewModel).
data class SessionState(
    val snapshot: BatterySnapshot?,
    val forecast: ChargeForecast,
    val sessionAgeMs: Long,
    // Скорость прошлой сессии — справочно, пока текущая уточняется.
    val lastSpeedPctPerMin: Float? = null,
    // Метка последнего обновления шторки (пульс сервиса).
    val lastNotifUpdateMs: Long? = null,
    // Видимая пользователю ошибка (отказ сервиса, датчики и т.п.).
    val error: String? = null
) {
    val isPlugged: Boolean get() = snapshot?.isPlugged == true
}

private fun idleState(error: String? = null, lastSpeed: Float? = null) = SessionState(
    snapshot = null,
    forecast = ChargeForecast(
        speedPctPerMin = null,
        remainingMin = null,
        accuracy = com.example.chargeforecast.forecast.ForecastAccuracy.COLLECTING,
        isFull = false
    ),
    sessionAgeMs = 0L,
    lastSpeedPctPerMin = lastSpeed,
    error = error
)

// Склеивает монитор батареи, калькулятор скорости и прогноз.
// Живёт на уровне Application, переиспользуется сервисом и экраном.
class ChargeSessionMonitor(
    private val batteryMonitor: BatteryMonitor,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cache: SessionCache? = null
) {
    private val calculator = ChargeSpeedCalculator()
    private val counterSpeedo = CounterSpeedometer()
    private val capacityTracker = CapacityTracker()
    private var sessionStartMs: Long? = null
    private var sessionMaxCurrentUa: Long? = null
    private var lastCapacityUah: Long? = null
    private var lastMeasuredSpeed: Float? = null
    // Проектная ёмкость из sysfs читается один раз за процесс (I/O).
    private val sysfsCapacityUah: Long? by lazy { SysfsCapacity.readDesignCapacityUah() }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun reportError(message: String?) {
        _error.value = message
    }

    // Тикер: переизлучаем состояние и без broadcast — для переходов
    // точности по возрасту сессии и регулярного обновления шторки.
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(TICKER_MS)
        }
    }

    val state: StateFlow<SessionState> =
        combine(batteryMonitor.snapshots, ticker) { snapshot, nowMs ->
            buildState(snapshot, nowMs)
        }.stateIn(scope, SharingStarted.Eagerly, idleState())

    private fun lastSpeed(): Float? = lastMeasuredSpeed ?: cache?.loadLastSpeed()

    private fun buildState(snapshot: BatterySnapshot?, nowMs: Long): SessionState {
        if (snapshot == null) return idleState(_error.value, lastSpeed())
        val active = snapshot.isPlugged && (snapshot.isCharging || snapshot.isFull)
        if (!active) {
            if (sessionStartMs != null) {
                // Сессия кончилась — сохраняем стабильные числа для следующей.
                lastCapacityUah?.let { cache?.saveCapacity(it) }
                lastMeasuredSpeed?.let { cache?.saveLastSpeed(it) }
            }
            sessionStartMs = null
            sessionMaxCurrentUa = null
            calculator.reset()
            counterSpeedo.reset()
            capacityTracker.reset()
            return idleState(_error.value, lastSpeed())
        }
        val start = sessionStartMs ?: nowMs.also {
            sessionStartMs = it
            sessionMaxCurrentUa = null
            calculator.reset()
            counterSpeedo.reset()
            capacityTracker.reset()
        }
        calculator.addSample(nowMs, snapshot.levelPct)
        if (snapshot.chargeCounterUah != null) {
            counterSpeedo.addSample(nowMs, snapshot.chargeCounterUah)
        }
        val age = (nowMs - start).coerceAtLeast(0L)
        // Лестница зрелости: уровень-дельта → счётчик-дельта → ток.
        // Ёмкость: живой трекер → sysfs → кэш прошлой сессии.
        val liveCapacity = capacityTracker.update(
            snapshot.chargeCounterUah, snapshot.levelPct
        )
        if (liveCapacity != null) {
            lastCapacityUah = liveCapacity
            cache?.saveCapacity(liveCapacity)
        }
        val capacityUah = liveCapacity ?: sysfsCapacityUah ?: cache?.loadCapacity()
        val measured = calculator.speedPctPerMin(nowMs)
            ?: counterSpeedo.speedPctPerMin(nowMs, capacityUah)
        if (measured != null) {
            lastMeasuredSpeed = measured
            cache?.saveLastSpeed(measured)
        }
        // Мгновенная оценка с первых секунд: пик тока сессии + ёмкость.
        // Ёмкость почти не меняется между сессиями — берём живую,
        // иначе sysfs/кэш: instant точен с секунды ноль.
        val currentUa = snapshot.currentMa?.let { (it * 1000f).toLong() }
        sessionMaxCurrentUa =
            InstantSpeedEstimator.updateSessionMax(sessionMaxCurrentUa, currentUa)
        val instantLive = InstantSpeedEstimator.instantSpeedPctPerMin(
            sessionMaxCurrentUa, capacityUah
        )
        // Опора с секунды 0: живой ток, если уже доверяем (вышел из
        // рампы), иначе — скорость прошлой сессии из кэша.
        val instant = InstantSpeedEstimator.selectInstantSpeed(
            instantLive, cache?.loadLastSpeed()
        )
        val forecast = ChargeForecaster.forecast(
            levelPct = snapshot.levelPct,
            measuredSpeedPctPerMin = measured,
            instantSpeedPctPerMin = instant,
            sessionAgeMs = age,
            isFull = snapshot.isFull,
            isCharging = snapshot.isCharging
        )
        return SessionState(
            snapshot = snapshot,
            forecast = forecast,
            sessionAgeMs = age,
            lastSpeedPctPerMin = lastSpeed(),
            lastNotifUpdateMs = cache?.loadHeartbeat(),
            error = _error.value
        )
    }

    companion object {
        const val TICKER_MS = 30_000L
    }
}
