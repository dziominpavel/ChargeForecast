package com.example.chargeforecast

import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.DischargeEstimate
import com.example.chargeforecast.forecast.DischargeEstimator
import com.example.chargeforecast.forecast.DischargeObservation
import com.example.chargeforecast.forecast.DischargeObservationReason
import com.example.chargeforecast.forecast.DischargeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Оценка оставшейся работы: скользящее окно 24 ч, среднее ΣdU ÷ Σdt,
// порог входа по наблюдённому времени внутри окна (спека discharge-estimate).
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

    // Постоянный разряд: каждые stepMs счётчик падает на stepUah.
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
    fun emptyWindow_showsCollecting_notZeroHours() {
        // Первый запуск: окно пустое — состояние сбора, а не 0 ч.
        val estimator = DischargeEstimator()
        assertEquals(
            DischargeEstimate.COLLECTING,
            estimator.estimate(2_000_000L, 0L)
        )
    }

    @Test
    fun lessThanTwoHours_staysCollecting() {
        // Час наблюдений (4 интервала по 15 минут) — меньше порога входа:
        // короткий участок не даёт уверенного числа.
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 4, stepUah = 100_000L)
        val estimate = estimator.estimate(1_600_000L, 3_600_000L)
        assertTrue("короткий набор данных не даёт оценки", estimate.collecting)
        // Пройденная часть пути всё равно видна — экран объясняет, чего
        // не хватает.
        assertNotNull(estimate.averageCurrentUa)
        assertEquals(3_600_000L, estimate.trackedMs)
    }

    @Test
    fun windowAverage_estimatesHoursFromEnergyAndTime() {
        // 400 мА: счётчик падает на 100k мкА·ч за 15 минут. За 2 часа
        // (8 интервалов) накоплено 800k мкА·ч / 2 ч = 400k мкА.
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 8, stepUah = 100_000L)
        val estimate = estimator.estimate(1_200_000L, 7_200_000L)
        assertTrue("опора должна появиться", !estimate.collecting)
        // Остаток 1.2 млн мкА·ч / 400k мкА = 3 ч = 10 800 с.
        assertEquals(10_800L, estimate.remainingSec!!)
        assertEquals(400_000f, estimate.averageCurrentUa!!, 1f)
        assertEquals(7_200_000L, estimate.trackedMs)
    }

    @Test
    fun fiveSecondSpike_doesNotFlipEstimate() {
        // Сутки спокойного разряда 200 мА, потом 5 секунд с током 1.2 А
        // («экран включился»). Вклад всплеска ограничен его долей в окне:
        // 5 с из суток — оценка смещается на сотые доли процента.
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 95, stepUah = 50_000L) // 23 ч 45 мин
        val before = estimator.estimate(1_000_000L, 85_500_000L)
        assertTrue(!before.collecting)

        val spikeMs = 5_000L
        val spikeUah = 1_667L // ~1.2 А
        val t = 85_500_000L
        estimator.update(snap(t, counterUah = 1_750_000L - spikeUah), t)
        val after = estimator.estimate(1_000_000L, t + spikeMs)

        val drift = kotlin.math.abs(after.remainingSec!! - before.remainingSec!!)
        val relative = drift.toDouble() / before.remainingSec!!
        assertTrue(
            "всплеск 5 с из суток не должен двигать оценку, а он сдвинул на $drift с",
            relative < 0.001
        )
    }

    @Test
    fun charging_doesNotPolluteWindow() {
        // Зарядка: окно не растёт, якоря переставляются — последующий
        // разряд считается от момента отключения.
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
        assertEquals(0L, estimator.summary(t).trackedMs)
        // Отключили кабель, дальше честный разряд 400 мА два часа.
        estimator.update(snap(t, counterUah = counter), t)
        repeat(8) {
            t += 900_000L
            counter -= 100_000L
            estimator.update(snap(t, counterUah = counter), t)
        }
        assertTrue("зарядка не должна накапливать расход", !estimator.estimate(counter, t).collecting)
    }

    @Test
    fun withoutCounter_intervalsDoNotAccumulate() {
        // Счётчика нет, датчик тока есть: без счётчика интервал не
        // накапливается и оценки нет — мгновенный ток в среднее не идёт.
        val estimator = DischargeEstimator()
        var t = 0L
        repeat(20) {
            estimator.update(snap(t, dischargeUa = 500_000L), t)
            t += 900_000L
        }
        val estimate = estimator.estimate(2_000_000L, t)
        assertTrue("без счётчика оценки нет", estimate.collecting)
        assertNull(estimate.averageCurrentUa)
    }

    @Test
    fun counterIncreaseWhileDischarging_skipsInterval() {
        // Счётчик вырос при разрядке — аномалия драйвера: интервал не
        // накапливается, оценка не портится.
        val estimator = DischargeEstimator()
        estimator.update(snap(0L, counterUah = 1_000_000L), 0L)
        estimator.update(snap(900_000L, counterUah = 1_500_000L), 900_000L)
        assertEquals(0L, estimator.summary(900_000L).trackedMs)
    }

    @Test
    fun zeroConsumption_staysHonest() {
        // Счётчик не убывает — честный ноль расхода: оценки нет, потому
        // что застой не является измеренным током, а не «0 часов».
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 8, stepUah = 0L)
        val estimate = estimator.estimate(1_200_000L, 7_200_000L)
        assertTrue("застой не даёт нулевых часов", estimate.collecting)
        assertNull(estimate.remainingSec)
        // Часть наблюдений всё ещё видна: сумма времени сохранена.
        assertEquals(7_200_000L, estimate.trackedMs)
    }

    @Test
    fun samples_mergeIntoFiveMinuteBuckets() {
        // Корзины по 5 минут держат кольцо коротким: полминутные замеры
        // одной минуты складываются, а не копятся поштучно.
        val estimator = DischargeEstimator()
        var t = 0L
        var counter = 2_000_000L
        repeat(10) {
            estimator.update(snap(t, counterUah = counter), t)
            t += 30_000L
            counter -= 1_000L
            estimator.update(snap(t, counterUah = counter), t)
        }
        val samples = estimator.state().samples
        // 10 минут замеров = две корзины (пятая минута попадает в новую).
        assertEquals(2, samples.size)
        assertEquals(10_000L, samples.sumOf { it.dischargedUah })
        assertEquals(300_000L, samples.sumOf { it.durationMs })
    }

    @Test
    fun windowPrunesSamplesOlderThanDay() {
        // Окно скользящее: через сутки и более старые интервалы
        // выкидываются.
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 8, stepUah = 100_000L) // 2 часа
        val later = 27 * 60 * 60_000L
        assertEquals(0L, estimator.summary(later).trackedMs)
        assertTrue(estimator.estimate(1_200_000L, later).collecting)
    }

    @Test
    fun rebootGap_excluded_notAccumulatedAsConsumption() {
        // Метка до ребута больше текущей (elapsedRealtime обнуляется):
        // интервал нечитаем — якорим заново, накопление не растёт.
        val estimator = DischargeEstimator()
        estimator.restore(
            DischargeState(
                samples = emptyList(),
                lastSampleMs = 10_000_000L,
                lastCounterUah = 2_000_000L
            )
        )
        // После ребута прошло 5 с: 5_000 − 10_000_000 < 0.
        estimator.update(snap(5_000L, counterUah = 1_999_000L), 5_000L)
        assertEquals(0L, estimator.summary(5_000L).trackedMs)
        assertEquals(5_000L, estimator.state().lastSampleMs)
    }

    @Test
    fun restoreAfterProcessKill_continuesFromBase() {
        // Процесс убит OEM-оболочкой: окно восстановлено, оценка
        // продолжает считаться от прежней базы, а не с нуля.
        val saved = DischargeState(
            samples = listOf(
                com.example.chargeforecast.forecast.DischargeSample(
                    atMs = 7_200_000L,
                    durationMs = 7_200_000L,
                    dischargedUah = 720_000L
                )
            ),
            lastSampleMs = 7_200_000L,
            lastCounterUah = 1_280_000L
        )
        val estimator = DischargeEstimator()
        estimator.restore(saved)
        assertEquals(saved.samples, estimator.state().samples)
        // 360 мА: остаток 1.28 млн мкА·ч / 360k мкА = 12 800 с.
        val estimate = estimator.estimate(1_280_000L, 7_200_000L)
        assertTrue(!estimate.collecting)
        assertEquals(12_800L, estimate.remainingSec!!)
    }

    @Test
    fun restore_nullState_keepsFreshCollector() {
        val estimator = DischargeEstimator()
        estimator.restore(null)
        assertEquals(DischargeState(emptyList(), null, null), estimator.state())
    }

    @Test
    fun noRemainingEnergy_showsCollecting() {
        // Остатка нет или он неположительный — сбор данных, а не ноль.
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 8, stepUah = 100_000L)
        val withoutBase = estimator.estimate(null, 7_200_000L)
        assertTrue(withoutBase.collecting)
        assertNull(withoutBase.remainingSec)
        // Опора окна при этом уже видна — экран объясняет состояние.
        assertEquals(400_000f, withoutBase.averageCurrentUa!!, 1f)
        assertTrue(estimator.estimate(0L, 7_200_000L).collecting)
        assertTrue(estimator.estimate(-1L, 7_200_000L).collecting)
    }

    @Test
    fun observations_reportReasonForEverySnapshot() {
        // Журнал получает строку на каждый замер — включая пропущенные
        // с причиной, иначе «нет счётчика» и «драйвер соврал» не различить.
        val observations = mutableListOf<DischargeObservation>()
        val estimator = DischargeEstimator(onObservation = { observations += it })
        feed(estimator, intervals = 1, stepUah = 100_000L) // зарядки нет
        estimator.update(snap(1_800_000L, plugged = true, charging = true), 1_800_000L)
        estimator.update(snap(2_700_000L, dischargeUa = 500_000L), 2_700_000L)

        assertEquals(DischargeObservationReason.INTERVAL, observations[1].reason)
        assertEquals(100_000L, observations[1].intervalUah)
        assertEquals(900_000L, observations[1].intervalMs)
        assertEquals(DischargeObservationReason.CHARGING, observations[2].reason)
        assertEquals(DischargeObservationReason.NO_COUNTER, observations[3].reason)
        assertEquals(60, observations[1].levelPct)
    }

    @Test
    fun persistedCallback_carriesFullWindow() {
        // Каждый пригодный интервал отдаёт на персист полное состояние.
        val persisted = mutableListOf<DischargeState>()
        val estimator = DischargeEstimator(onPersist = { persisted += it })
        feed(estimator, intervals = 2, stepUah = 100_000L)
        assertEquals(2, persisted.size)
        assertEquals(estimator.state(), persisted.last())
        assertTrue(persisted.last().samples.isNotEmpty())
    }
}
