package com.example.chargeforecast

import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.DischargeEstimate
import com.example.chargeforecast.forecast.DischargeEstimator
import com.example.chargeforecast.forecast.DischargeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Оценка оставшейся работы: накопление только в простое, персистентность,
// честность при отсутствии опоры (спека discharge-estimate).
class DischargeEstimatorTest {

    private fun snap(
        tMs: Long,
        level: Int = 60,
        plugged: Boolean = false,
        charging: Boolean = false,
        counterUah: Long? = null,
        dischargeUa: Long? = null
    ) = BatterySnapshot(
        levelPct = level,
        isPlugged = plugged,
        isCharging = charging,
        isFull = false,
        chargeType = if (plugged) ChargeType.AC else ChargeType.NONE,
        temperatureC = null,
        voltageV = null,
        currentMa = null,
        dischargeCurrentUa = dischargeUa,
        chargeCounterUah = counterUah,
        timestampMs = tMs
    )

    // Разряд постоянным током: каждые 15 минут счётчик падает на stepUah.
    private fun feed(
        estimator: DischargeEstimator,
        intervals: Int,
        stepUah: Long,
        startUah: Long = 2_000_000L,
        startMs: Long = 0L,
        stepMs: Long = 900_000L
    ) {
        estimator.update(snap(startMs, counterUah = startUah), startMs)
        var counter = startUah
        var t = startMs
        repeat(intervals) {
            t += stepMs
            counter -= stepUah
            estimator.update(snap(t, counterUah = counter), t)
        }
    }

    @Test
    fun emptyCollector_showsCollecting_notZeroHours() {
        // Первый запуск: накопителя нет — состояние сбора, а не 0 ч.
        val estimator = DischargeEstimator()
        assertEquals(DischargeEstimate.COLLECTING, estimator.estimate(2_000_000L))
    }

    @Test
    fun accumulatedConsumption_estimatesHoursFromAverage() {
        // 400 мА, счётчик падает на 100k мкА·ч за 15 минут. Остаток
        // 1.7 млн мкА·ч / 400k мкА = 4.25 ч = 15300 с.
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 3, stepUah = 100_000L)
        val estimate = estimator.estimate(1_700_000L)
        assertTrue("опора должна появиться", !estimate.collecting)
        assertEquals(15_300L, estimate.remainingSec!!)
    }

    @Test
    fun tooLittleTrackedTime_staysCollecting() {
        // Опора из одного интервала (10 минут < MIN_TRACKED_MS=15) не строится.
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 1, stepUah = 100_000L, stepMs = 600_000L)
        assertEquals(DischargeEstimate.COLLECTING, estimator.estimate(1_900_000L))
    }

    @Test
    fun charging_doesNotPolluteAccumulator() {
        // Зарядка: накопитель не растёт, якоря переставляются — последующий
        // разряд считается от момента отключения, а не от последнего замера
        // на зарядке.
        val estimator = DischargeEstimator()
        var t = 0L
        var counter = 1_000_000L
        repeat(10) {
            estimator.update(
                snap(t, plugged = true, charging = true, counterUah = counter),
                t
            )
            counter += 100_000L
            t += 900_000L
        }
        assertEquals(0L, estimator.state().trackedMs)
        // Отключили кабель, дальше честный разряд 400 мА.
        estimator.update(snap(t, counterUah = counter), t)
        repeat(3) {
            t += 900_000L
            counter -= 100_000L
            estimator.update(snap(t, counterUah = counter), t)
        }
        assertTrue("зарядка не должна накапливать расход", !estimator.estimate(counter).collecting)
    }

    @Test
    fun zeroConsumption_dragsEstimateDownGradually() {
        // Нулевой расход — честный ноль, а не пропущенный интервал:
        // средний ток падает (оцениваемое время растёт), но не мгновенно.
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 3, stepUah = 100_000L)
        val before = estimator.state().smoothedCurrentUa!!
        feed(estimator, intervals = 10, stepUah = 0L, startUah = 1_700_000L)
        val after = estimator.state().smoothedCurrentUa!!
        assertTrue("нулевые интервалы должны утягивать среднее вниз", after < before)
        // Полуокно в сутки: 2.5 часа застоя не уводят оценку в ноль.
        assertTrue("среднее осталось измеримым", after > 0f)
    }

    @Test
    fun sustainedZeroConsumption_fallsBelowCredibleFloor() {
        // Достаточно долгий застой при коротком полуокне (тестируемая
        // константа) опускает среднее ниже порога доверия → сбор данных.
        val estimator = DischargeEstimator(halfLifeMs = 600_000L)
        feed(estimator, intervals = 3, stepUah = 100_000L)
        feed(estimator, intervals = 40, stepUah = 0L, startUah = 1_700_000L)
        assertEquals(DischargeEstimate.COLLECTING, estimator.estimate(1_700_000L))
    }

    @Test
    fun counterIncreaseWhileDischarging_skipsInterval() {
        // Счётчик вырос при разрядке — аномалия драйвера: интервал не
        // накапливается, оценка не портится.
        val estimator = DischargeEstimator()
        estimator.update(snap(0L, counterUah = 1_000_000L), 0L)
        estimator.update(snap(900_000L, counterUah = 1_500_000L), 900_000L)
        assertEquals(0L, estimator.state().trackedMs)
    }

    @Test
    fun sensorFallback_whenCounterMissing() {
        // Счётчика нет — модуль тока датчика: 500 мА, остаток 2 млн мкА·ч
        // = 4 ч = 14400 с.
        val estimator = DischargeEstimator()
        estimator.update(snap(0L, dischargeUa = 500_000L), 0L)
        estimator.update(snap(900_000L, dischargeUa = 500_000L), 900_000L)
        estimator.update(snap(1_800_000L, dischargeUa = 500_000L), 1_800_000L)
        val estimate = estimator.estimate(2_000_000L)
        assertTrue(!estimate.collecting)
        assertEquals(14_400L, estimate.remainingSec!!)
    }

    @Test
    fun rebootGap_excluded_notAccumulatedAsConsumption() {
        // Метка до ребута больше текущей (elapsedRealtime обнуляется):
        // интервал нечитаем — якорим заново, накопление не растёт.
        val estimator = DischargeEstimator()
        estimator.restore(
            DischargeState(
                smoothedCurrentUa = 400_000f,
                lastSampleMs = 10_000_000L,
                lastCounterUah = 2_000_000L,
                trackedMs = 3_600_000L
            )
        )
        // После ребута прошло 5 с: 5_000 − 10_000_000 < 0.
        estimator.update(snap(5_000L, counterUah = 1_999_000L), 5_000L)
        assertEquals(3_600_000L, estimator.state().trackedMs)
        assertEquals(5_000L, estimator.state().lastSampleMs)
    }

    @Test
    fun restoreAfterProcessKill_continuesFromBase() {
        // Процесс убит OEM-оболочкой: состояние восстановлено, оценка
        // продолжает считаться от прежней базы, а не с нуля.
        val saved = DischargeState(
            smoothedCurrentUa = 400_000f,
            lastSampleMs = 0L,
            lastCounterUah = 2_000_000L,
            trackedMs = 3_600_000L
        )
        val estimator = DischargeEstimator()
        estimator.restore(saved)
        assertEquals(saved, estimator.state())
        val estimate = estimator.estimate(2_000_000L)
        assertTrue(!estimate.collecting)
        assertEquals(18_000L, estimate.remainingSec!!)
    }

    @Test
    fun belowCredibleFloor_staysCollecting() {
        // 0.5 мА — мусор датчика, а не измеренный расход.
        val estimator = DischargeEstimator()
        estimator.update(snap(0L, dischargeUa = 500L), 0L)
        estimator.update(snap(3_600_000L, dischargeUa = 500L), 3_600_000L)
        assertEquals(DischargeEstimate.COLLECTING, estimator.estimate(4_000_000L))
    }

    @Test
    fun halfLife_limitsHowFastNewRegimeMovesEstimate() {
        // Полуокно ~сутки: один час тяжёлого расхода не переворачивает
        // сглаженное значение — оценка смещается постепенно (спека:
        // «бурный вечер смещает оценку постепенно»).
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 4, stepUah = 40_000L) // ~160 мА
        val before = estimator.state().smoothedCurrentUa!!
        feed(estimator, intervals = 4, stepUah = 1_000_000L, startUah = 1_840_000L)
        val after = estimator.state().smoothedCurrentUa!!
        assertTrue("сглаженное должно вырасти", after > before)
        // Час тяжёлого расхода при полуокне в сутки сдвигает значение
        // заметно меньше, чем на величину нового интервала (~6600 мА).
        assertTrue(
            "сдвиг должен быть ограничен: $before → $after",
            after - before < 1_000_000f
        )
    }

    @Test
    fun noRemainingEnergy_showsCollecting() {
        // Остатка нет или он неположительный — сбор данных, а не ноль.
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 3, stepUah = 100_000L)
        assertEquals(DischargeEstimate.COLLECTING, estimator.estimate(null))
        assertEquals(DischargeEstimate.COLLECTING, estimator.estimate(0L))
        assertEquals(DischargeEstimate.COLLECTING, estimator.estimate(-1L))
    }

    @Test
    fun persistedCallback_carriesFullState() {
        // Каждый пригодный интервал отдаёт на персист полное состояние.
        val persisted = mutableListOf<DischargeState>()
        val estimator = DischargeEstimator(onPersist = { persisted += it })
        feed(estimator, intervals = 2, stepUah = 100_000L)
        assertEquals(2, persisted.size)
        assertEquals(estimator.state(), persisted.last())
        assertNull(persisted.first().smoothedCurrentUa?.takeIf { it <= 0f })
        assertTrue(persisted.last().trackedMs > 0L)
    }

    @Test
    fun restore_nullState_keepsFreshCollector() {
        val estimator = DischargeEstimator()
        estimator.restore(null)
        assertEquals(DischargeState(null, null, null, 0L), estimator.state())
    }
}
