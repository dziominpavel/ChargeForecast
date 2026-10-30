package com.example.chargeforecast.session

import android.os.SystemClock
import com.example.chargeforecast.battery.BatteryDataQuality
import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.SysfsCapacity
import com.example.chargeforecast.forecast.ChargeForecast
import com.example.chargeforecast.forecast.DischargeEstimate
import com.example.chargeforecast.forecast.DischargeEstimator
import com.example.chargeforecast.tracking.TrackingHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

// Состояние текущей сессии зарядки — единый источник правды для экрана
// и виджета. Ошибки в состояние не входят: они поток событий, а не
// значение (design D7) — иначе новый подписчик переигрывал бы их.
data class SessionState(
    val snapshot: BatterySnapshot?,
    val forecast: ChargeForecast,
    val sessionAgeMs: Long,
    // Оценка оставшейся работы телефона — вне зарядки (battery-widget).
    val discharge: DischargeEstimate = DischargeEstimate.NONE,
    // Живость фонового слежения: экран и виджет показывают её постоянно.
    val tracking: TrackingHealth = TrackingHealth.running(),
    // Какие датчики батареи реально отдают данные: экран объясняет fallback.
    val dataQuality: BatteryDataQuality = BatteryDataQuality.UNKNOWN
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
@OptIn(ExperimentalCoroutinesApi::class)
class ChargeSessionMonitor(
    private val batterySnapshots: StateFlow<BatterySnapshot?>,
    scope: CoroutineScope,
    clock: () -> Long = SystemClock::elapsedRealtime,
    cache: SessionCache? = null,
    dischargeEstimator: DischargeEstimator? = null,
    runtimeStore: SessionRuntimeStore? = null,
    bootCount: Int? = null,
    initialSnapshot: ActiveSessionSnapshot? = null,
    trackingHealth: StateFlow<TrackingHealth>? = null,
    dataQuality: StateFlow<BatteryDataQuality>? = null
) {
    // Ошибки — события, а не состояние: буферизованный канал отдаёт
    // сообщение, пришедшее до подписки UI, и не переигрывает его второму
    // подписчику. Статус слежения при этом живёт в TrackingHealth.
    private val _errors = Channel<String>(
        capacity = ERROR_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val errors: Flow<String> = _errors.receiveAsFlow()

    private val trackingHealthFlow = trackingHealth ?: MutableStateFlow(TrackingHealth.running())
    private val dataQualityFlow = dataQuality ?: MutableStateFlow(BatteryDataQuality.UNKNOWN)

    private val sysfsCapacityUah: Long? by lazy { SysfsCapacity.readDesignCapacityUah() }
    private val engine =
        SessionEngine(cache, dischargeEstimator, runtimeStore, bootCount) {
            sysfsCapacityUah
        }
    private val activeSessionPersister = ActiveSessionPersister(runtimeStore)

    init {
        // Снимок той же загрузки продолжает сессию до первого тика; после
        // смены загрузки Application уже финализировал старую сессию и
        // передал null. Невалидный снимок стираем, чтобы он не всплывал
        // при каждом запуске.
        initialSnapshot?.let {
            if (!engine.restoreActiveSession(it, clock())) {
                runtimeStore?.clearActiveSession()
            }
        }
    }

    fun reportError(message: String?) {
        if (message != null) _errors.trySend(message)
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

    // Движок обновляется только батареей и тикером. Health и качество
    // датчиков добавляются вторым слоем: иначе смена статуса вызывала бы
    // engine.update() с тем же снапшотом и искажала спидометры.
    private val engineState: StateFlow<SessionState> =
        combine(batterySnapshots, ticker) { snapshot, nowMs ->
            // Свежайшая из двух меток. В простое тикер молчит, и меткой
            // служит сам снапшот — он пишется в момент чтения батареи.
            // Без этого первая секунда после вставки кабеля считала бы
            // возраст от последнего тика (которого давно не было), а не
            // от подключения.
            val state = engine.update(
                snapshot,
                maxOf(nowMs, snapshot?.timestampMs ?: 0L)
            )
            // Снимок пишется с троттлингом: после kill сессия продолжится
            // с отставанием не более 30 секунд.
            activeSessionPersister.persist(engine.snapshotActiveSession())
            state
        }.stateIn(scope, SharingStarted.Eagerly, engine.update(null, clock()))

    val state: StateFlow<SessionState> =
        combine(engineState, trackingHealthFlow, dataQualityFlow) { base, health, quality ->
            base.copy(tracking = health, dataQuality = quality)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            engineState.value.copy(
                tracking = trackingHealthFlow.value,
                dataQuality = dataQualityFlow.value
            )
        )

    companion object {
        const val TICKER_ACTIVE_MS = 1_000L
        const val ERROR_BUFFER_CAPACITY = 16
    }
}
