package com.example.chargeforecast

import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.DischargeEstimator
import com.example.chargeforecast.session.ChargeSessionMonitor
import com.example.chargeforecast.session.SessionCache
import com.example.chargeforecast.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        cache.saveCapacity(capacity)
        return ChargeSessionMonitor(
            snapshots,
            CoroutineScope(Dispatchers.Unconfined),
            nowMs,
            cache,
            DischargeEstimator()
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
        assertTrue(
            "простой должен отдавать оценку работы",
            !updated.discharge.collecting
        )
    }

    @Test
    fun unplug_writesSummaryOnce_andSwitchesToDischarge() {
        // «Кабель вынут при закрытом приложении» (спека background-tracking):
        // сессия завершается, сводка пишется при отключении.
        val prefs = FakeSharedPreferences()
        val cache = SessionCache(prefs)
        cache.saveCapacity(capacity)
        val snapshots = MutableStateFlow<BatterySnapshot?>(null)
        var nowMs = 0L
        val monitor = ChargeSessionMonitor(
            snapshots,
            CoroutineScope(Dispatchers.Unconfined),
            { nowMs },
            cache,
            DischargeEstimator()
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
        // После отключения состояние — режим разряда, а не зарядный отсчёт.
        assertTrue(idle.discharge.collecting || idle.discharge.remainingSec != null)
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
}
