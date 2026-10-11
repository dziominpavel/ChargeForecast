package com.example.chargeforecast.session

import android.os.SystemClock
import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.SysfsCapacity
import com.example.chargeforecast.forecast.ChargeForecast
import com.example.chargeforecast.forecast.DischargeEstimate
import com.example.chargeforecast.forecast.DischargeEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// Состояние текущей сессии зарядки — единый источник правды для экрана
// и виджета. Ошибки в состояние не входят: они поток событий, а не
// значение (design D7) — иначе новый подписчик переигрывал бы их.
data class SessionState(
    val snapshot: BatterySnapshot?,
    val forecast: ChargeForecast,
    val sessionAgeMs: Long,
    // Оценка оставшейся работы телефона — вне зарядки (battery-widget).
    val discharge: DischargeEstimate = DischargeEstimate.NONE
) {
    val isPlugged: Boolean get() = snapshot?.isPlugged == true
}

// Склеивает поток снапшотов батареи и чистый движок сессии.
// Живёт на уровне Application, отдаёт состояние экрану, сервису и виджету.
// Все расчёты — на SystemClock.elapsedRealtime: подстройка системных
// часов сетью не скачет скорость и возраст сессии (см. design D7).
//
// Зависимость — именно поток, а не BatteryMonitor: монитору нужен только
// источник снапшотов, а Context-зависимый класс сделал бы класс
// непригодным для JVM-теста (там SystemClock и.getSystemService недоступны).
class ChargeSessionMonitor(
    private val batterySnapshots: StateFlow<BatterySnapshot?>,
    scope: CoroutineScope,
    clock: () -> Long = SystemClock::elapsedRealtime,
    cache: SessionCache? = null,
    dischargeEstimator: DischargeEstimator? = null
) {
    // Ошибки — события, а не состояние (design D7): replay 0, каждый
    // подписчик получает сообщение ровно один раз. Значением их держать
    // нельзя — новый подписчик (сервис, виджет) переигрывал бы устаревшее.
    private val _errors = MutableSharedFlow<String>(replay = 0)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val sysfsCapacityUah: Long? by lazy { SysfsCapacity.readDesignCapacityUah() }
    private val engine =
        SessionEngine(cache, dischargeEstimator) { sysfsCapacityUah }

    fun reportError(message: String?) {
        if (message != null) _errors.tryEmit(message)
    }

    // Тикер: переизлучаем состояние и без broadcast — для «Измеряю…»
    // и обновления цифр каждую секунду. В простое тика нет вовсе
    // (design D10): возраст сессии там не растёт, а пробуждение
    // выполняет сервис по ACTION_POWER_CONNECTED.
    private val ticker: Flow<Long> = batterySnapshots
        .map { it?.isPlugged == true }
        .distinctUntilChanged()
        .flatMapLatest { plugged ->
            if (!plugged) {
                // Простой: одна метка, чтобы combine знал последнее время.
                // Свежестью в простое займётся снапшот (см. state ниже).
                flowOf(clock())
            } else {
                flow {
                    while (true) {
                        emit(clock())
                        delay(TICKER_ACTIVE_MS)
                    }
                }
            }
        }

    val state: StateFlow<SessionState> =
        combine(batterySnapshots, ticker) { snapshot, nowMs ->
            // Свежайшая из двух меток. В простое тикер молчит, и меткой
            // служит сам снапшот — он пишется в момент чтения батареи.
            // Без этого первая секунда после вставки кабеля считала бы
            // возраст от последнего тика (которого давно не было), а не
            // от подключения.
            engine.update(snapshot, maxOf(nowMs, snapshot?.timestampMs ?: 0L))
        }.stateIn(scope, SharingStarted.Eagerly, engine.update(null, clock()))

    companion object {
        const val TICKER_ACTIVE_MS = 1_000L
    }
}
