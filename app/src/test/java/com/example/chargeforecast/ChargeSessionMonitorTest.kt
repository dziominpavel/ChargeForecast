package com.example.chargeforecast

import com.example.chargeforecast.battery.BatteryDataQuality
import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.battery.SensorAvailability
import com.example.chargeforecast.forecast.DischargeEstimator
import com.example.chargeforecast.forecast.DischargeSample
import com.example.chargeforecast.forecast.DischargeState
import com.example.chargeforecast.session.ActiveSessionSnapshot
import com.example.chargeforecast.session.ChargeSessionMonitor
import com.example.chargeforecast.session.SessionCache
import com.example.chargeforecast.session.SessionRuntimeStore
import com.example.chargeforecast.session.SessionState
import com.example.chargeforecast.tracking.TrackingFailure
import com.example.chargeforecast.tracking.TrackingHealth
import com.example.chargeforecast.tracking.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Склейка потока батареи и движка: пробуждение по событию вместо опроса
// (design D10). Unconfined даёт почти синхронную доставку — тест живёт
// без виртуального времени и без новых зависимостей.
class ChargeSessionMonitorTest {

    private val capacity = 4_000_000L

    private fun snap(
        tMs: Long,
        level: Int,
        plugged: Boolean = true,
        counterUah: Long? = capacity * level / 100L
    ) = BatterySnapshot(
        levelPct = level,
        isPlugged = plugged,
        isCharging = plugged,
        isFull = false,
        chargeType = if (plugged) ChargeType.AC else ChargeType.NONE,
        temperatureC = null,
        voltageV = null,
        currentMa = if (plugged) 2_400f else null,
        dischargeCurrentUa = if (plugged) null else 500_000L,
        chargeCounterUah = counterUah,
        timestampMs = tMs
    )

    private fun monitor(
        snapshots: MutableStateFlow<BatterySnapshot?>,
        nowMs: () -> Long
    ): ChargeSessionMonitor {
        val cache = SessionCache(FakeSharedPreferences())
        val runtime = SessionRuntimeStore(FakeSharedPreferences())
        runtime.saveCapacity(capacity)
        return ChargeSessionMonitor(
            snapshots,
            CoroutineScope(Dispatchers.Unconfined),
            nowMs,
            cache,
            DischargeEstimator(),
            runtime
        )
    }

    // combine и тикер подписываются на поток параллельно — дожидаемся
    // условия, а не расчитываем на одну доставку.
    private fun await(
        monitor: ChargeSessionMonitor,
        timeoutMs: Long = 2_000L,
        predicate: (SessionState) -> Boolean
    ): SessionState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = monitor.state.value
            if (predicate(state)) return state
            Thread.sleep(5)
        }
        return monitor.state.value
    }

    @Test
    fun plugEvent_startsSessionAtEventTime_notFromStaleTick() {
        // В простое тикер молчит (design D10). Если бы возраст считался от
        // последнего тика, час простоя перед вставкой кабеля превратился бы
        // в «сессии час» — секунда ноль обязана быть от подключения.
        val snapshots = MutableStateFlow<BatterySnapshot?>(null)
        var nowMs = 0L
        val monitor = monitor(snapshots) { nowMs }

        // Простой длиной в час: снапшот есть, свежих замеров нет.
        snapshots.value = snap(0L, 60, plugged = false)
        nowMs = 3_600_000L

        // Вставили кабель: свежий снапшот несёт собственную метку времени.
        snapshots.value = snap(nowMs, 60, plugged = true)
        val started = await(monitor) { it.isPlugged }

        assertEquals(
            "возраст сессии должен отсчитываться от подключения, а не от последнего тика",
            0L,
            started.sessionAgeMs
        )
    }

    @Test
    fun idle_recomputesOnBatteryEvent_notOnItsOwn() {
        // В простое обновление приходит по событию батареи, а не по тику:
        // между снапшотами состояние не пересчитывается само.
        val snapshots = MutableStateFlow<BatterySnapshot?>(null)
        var nowMs = 0L
        val monitor = monitor(snapshots) { nowMs }

        snapshots.value = snap(0L, 70, plugged = false)
        val first = await(monitor) { it.snapshot?.levelPct == 70 }

        // Два с лишним часа простоя без новых замеров: состояние остаётся
        // прежним. Столько же нужно, чтобы накопилось 2 часа наблюдаемого
        // времени — порог входа в оценку (спека discharge-estimate).
        nowMs = 7_500_000L
        Thread.sleep(50)
        assertEquals(
            "простой не должен будить состояние само по себе",
            first,
            monitor.state.value
        )

        // Событие батареи (уровень упал) — состояние пересчитано сразу.
        snapshots.value = snap(nowMs, 69, plugged = false)
        val updated = await(monitor) { it.snapshot?.levelPct == 69 }
        assertEquals(69, updated.snapshot!!.levelPct)
        // Свежий снапшот несёт и свежую оценку разряда, а не устаревшую.
        assertNotNull(
            "простой должен отдавать оценку работы",
            updated.discharge.remainingSec
        )
    }

    @Test
    fun unplug_writesSummaryOnce_andSwitchesToDischarge() {
        // «Кабель вынут при закрытом приложении» (спека background-tracking):
        // сессия завершается, сводка пишется при отключении.
        val prefs = FakeSharedPreferences()
        val cache = SessionCache(prefs)
        val runtime = SessionRuntimeStore(FakeSharedPreferences())
        runtime.saveCapacity(capacity)
        val snapshots = MutableStateFlow<BatterySnapshot?>(null)
        var nowMs = 0L
        val monitor = ChargeSessionMonitor(
            snapshots,
            CoroutineScope(Dispatchers.Unconfined),
            { nowMs },
            cache,
            DischargeEstimator(),
            runtime
        )

        // Зарядка 50% → 80% за 30 минут.
        var level = 50
        snapshots.value = snap(0L, level)
        repeat(30) {
            nowMs += 60_000L
            level += 1
            snapshots.value = snap(nowMs, level)
        }
        await(monitor) { it.sessionAgeMs >= 1_800_000L }

        // Вынули кабель.
        nowMs += 60_000L
        snapshots.value = snap(nowMs, level, plugged = false)
        val idle = await(monitor) { !it.isPlugged && it.snapshot != null }

        val summary = cache.loadSummary()
        assertNotNull("сводка пишется в момент отключения", summary)
        assertEquals(30, summary!!.gainedPct)
        assertEquals(ChargeType.AC, summary.chargeType)
        // После отключения состояние — режим разряда: оценка работы
        // (приор или измеренное среднее) есть сразу.
        assertNotNull(idle.discharge.remainingSec)
    }

    @Test
    fun charging_ageCountsFromPlug_notFirstSample() {
        // Возраст на зарядке — от секунды ноль подключения, а не от
        // времени первого замера батареи.
        val snapshots = MutableStateFlow<BatterySnapshot?>(null)
        var nowMs = 0L
        val monitor = monitor(snapshots) { nowMs }

        snapshots.value = snap(0L, 50, plugged = true)
        assertEquals(0L, await(monitor) { it.isPlugged }.sessionAgeMs)

        nowMs = 5_000L
        snapshots.value = snap(nowMs, 50, plugged = true)
        assertEquals(5_000L, await(monitor) { it.sessionAgeMs > 0L }.sessionAgeMs)
    }

    @Test
    fun bootChange_dropsDischargeWindow_andShowsPrior() {
        // После ребута окно разряда начинается заново: старые сэмплы не
        // смешиваются с новой шкалой, показывается приор от уровня.
        val saved = DischargeState(
            samples = listOf(
                DischargeSample(
                    atMs = 7_200_000L,
                    durationMs = 7_200_000L,
                    dischargedUah = 720_000L
                )
            ),
            lastSampleMs = 7_200_000L,
            lastCounterUah = 1_280_000L,
            bootCount = 1
        )
        val estimator = DischargeEstimator().also {
            it.restore(saved, currentBootCount = 2, nowElapsedMs = 5_000L)
        }
        val snapshots = MutableStateFlow<BatterySnapshot?>(null)
        var nowMs = 5_000L
        val monitor = ChargeSessionMonitor(
            snapshots,
            CoroutineScope(Dispatchers.Unconfined),
            { nowMs },
            SessionCache(FakeSharedPreferences()),
            estimator,
            SessionRuntimeStore(FakeSharedPreferences()),
            bootCount = 2
        )

        snapshots.value = snap(nowMs, 60, plugged = false)
        val state = await(monitor) { it.snapshot?.levelPct == 60 }

        assertEquals(0L, state.discharge.trackedMs)
        assertEquals(34_560L, state.discharge.remainingSec)
    }

    @Test
    fun initialSnapshot_sameBoot_continuesSession() {
        // Снимок той же загрузки передан монитору до первого тика:
        // возраст и старт продолжаются, а не начинаются с нуля.
        val snapshot = ActiveSessionSnapshot(
            id = "session-monitor",
            bootCount = 1,
            startedAtMs = 0L,
            startLevelPct = 50,
            chargeType = ChargeType.AC,
            ccSpeedSum = 0f,
            ccSampleCount = 0,
            peakCurrentUa = null,
            lastLevelPct = 55,
            lastUpdatedAtMs = 60_000L
        )
        val snapshots = MutableStateFlow<BatterySnapshot?>(null)
        var nowMs = 60_000L
        val monitor = ChargeSessionMonitor(
            snapshots,
            CoroutineScope(Dispatchers.Unconfined),
            { nowMs },
            SessionCache(FakeSharedPreferences()),
            DischargeEstimator(),
            SessionRuntimeStore(FakeSharedPreferences()),
            bootCount = 1,
            initialSnapshot = snapshot
        )

        snapshots.value = snap(nowMs, 55, plugged = true)
        val state = await(monitor) { it.sessionAgeMs >= 60_000L }

        assertEquals(60_000L, state.sessionAgeMs)
        assertNotNull(state.forecast.remainingSec)
    }

    @Test
    fun trackingHealth_andQuality_reachState_withoutTouchingSession() {
        val snapshots = MutableStateFlow<BatterySnapshot?>(null)
        var nowMs = 0L
        val health = MutableStateFlow(TrackingHealth.running())
        val quality = MutableStateFlow(BatteryDataQuality.UNKNOWN)
        val monitor = ChargeSessionMonitor(
            snapshots,
            CoroutineScope(Dispatchers.Unconfined),
            { nowMs },
            SessionCache(FakeSharedPreferences()),
            DischargeEstimator(),
            SessionRuntimeStore(FakeSharedPreferences()),
            trackingHealth = health,
            dataQuality = quality
        )

        snapshots.value = snap(0L, 50, plugged = true)
        await(monitor) { it.isPlugged }
        nowMs = 10_000L
        snapshots.value = snap(nowMs, 50, plugged = true)
        val before = await(monitor) { it.sessionAgeMs >= 10_000L }

        health.value = TrackingHealth(
            status = TrackingStatus.BLOCKED_BY_OS,
            failure = TrackingFailure.START_BLOCKED
        )
        quality.value = BatteryDataQuality(
            currentSensor = SensorAvailability.MISSING,
            counterSensor = SensorAvailability.AVAILABLE
        )
        val after = await(monitor) {
            it.tracking.status == TrackingStatus.BLOCKED_BY_OS &&
                it.dataQuality.currentMissing
        }

        // Health и качество — только копия поверх состояния движка:
        // сессия не начинается заново и не теряет накопленный возраст.
        assertEquals(before.sessionAgeMs, after.sessionAgeMs)
        assertEquals(before.snapshot, after.snapshot)
        assertEquals(before.forecast, after.forecast)
    }

    @Test
    fun restoredSession_seenUnpluggedOnFirstRead_isClearedFromStore() {
        // Процесс убили во время зарядки, кабель вынули, приложение открыли:
        // восстановленная сессия обязана закрыться и стереть свой снимок,
        // иначе следующий запуск воскресит её как активную.
        val runtime = SessionRuntimeStore(FakeSharedPreferences())
        val restored = ActiveSessionSnapshot(
            id = "restored-session",
            bootCount = 127,
            startedAtMs = 1_000L,
            startLevelPct = 50,
            chargeType = ChargeType.USB,
            ccSpeedSum = 0f,
            ccSampleCount = 0,
            peakCurrentUa = null,
            lastLevelPct = 55,
            lastUpdatedAtMs = 2_000L
        )
        runtime.saveActiveSession(restored)
        // Первый же замер после старта процесса — уже без кабеля:
        // промежуточного null-состояния, которое записало бы снимок
        // в persister, не будет.
        val snapshots = MutableStateFlow<BatterySnapshot?>(
            snap(3_000L, 55, plugged = false)
        )
        val monitor = ChargeSessionMonitor(
            snapshots,
            CoroutineScope(Dispatchers.Unconfined),
            { 3_000L },
            SessionCache(FakeSharedPreferences()),
            DischargeEstimator(),
            runtime,
            bootCount = 127,
            initialSnapshot = restored
        )

        val idle = await(monitor) { it.snapshot?.levelPct == 55 && !it.isPlugged }

        assertTrue("первый замер без кабеля должен закрыть сессию", !idle.isPlugged)
        assertNull(
            "снимок закрытой сессии не должен переживать перезапуск",
            runtime.loadActiveSession()
        )
    }

    @Test
    fun errorReportedBeforeUiSubscription_isDeliveredOnce() = runBlocking {
        val monitor = monitor(MutableStateFlow(null)) { 0L }

        // UI ещё не подписан — сообщение обязано дождаться первого
        // подписчика, а не потеряться вместе с replay = 0.
        monitor.reportError("слежение не запустилось")

        val first = withTimeoutOrNull(1_000L) { monitor.errors.first() }
        assertEquals("слежение не запустилось", first)

        // Второй подписчик не должен получить то же событие повторно.
        val second = withTimeoutOrNull(100L) { monitor.errors.first() }
        assertNull(second)
    }
}
