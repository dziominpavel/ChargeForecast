package com.example.chargeforecast

import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.ChargeType
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
// приор «полный заряд ≈ 24 ч» и плавный смесь к измеренному за 2 ч
// (спека discharge-estimate, design D1).
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
    fun emptyWindow_showsPriorFromLevel_notZeroHours() {
        // Первый запуск: окно пустое — приор «полный заряд ≈ 24 ч»,
        // а не состояние сбора данных и не 0 ч (спека discharge-estimate).
        val estimator = DischargeEstimator()
        val estimate = estimator.estimate(
            levelPct = 60, remainingUah = 2_000_000L, nowMs = 0L
        )
        // 40% доли заряда × 24 ч = 34 560 с.
        assertEquals(34_560L, estimate.remainingSec)
        assertNull(estimate.averageCurrentUa)
    }

    @Test
    fun lessThanTwoHours_blendsWithPrior_noCollectingState() {
        // Час наблюдений (4 интервала по 15 минут) — меньше двух часов:
        // показывается смесь приора и измеренного, состояние сбора
        // данных отсутствует.
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 4, stepUah = 100_000L)
        val estimate = estimator.estimate(
            levelPct = 60, remainingUah = 1_600_000L, nowMs = 3_600_000L
        )
        // Измеренное: 1.6 млн мкА·ч / 400k мкА = 14 400 с; приор (40%) =
        // 34 560 с; вес измеренного на середине горизонта smoothstep(0.5)
        // = 0.5 → ровно половина каждого.
        assertEquals(24_480L, estimate.remainingSec)
        // Пройденная часть пути видна — средний ток уже измерен.
        assertNotNull(estimate.averageCurrentUa)
        assertEquals(3_600_000L, estimate.trackedMs)
    }

    @Test
    fun prior_blendApproachesMeasured_noStepAtTwoHourBoundary() {
        // Третья точка у границы: 1 ч 45 мин — смесь уже близка к
        // измеренному, а на границе 2 ч — чистое измеренное: вес приора
        // идёт в ноль плавно, ступени на пороге нет.
        val near = DischargeEstimator().apply {
            feed(this, intervals = 7, stepUah = 100_000L)
        }.estimate(60, 1_200_000L, 6_300_000L)
        val full = DischargeEstimator().apply {
            feed(this, intervals = 8, stepUah = 100_000L)
        }.estimate(60, 1_200_000L, 7_200_000L)
        assertEquals("на границе 2 ч — чистое измеренное", 10_800L, full.remainingSec)
        assertTrue(
            "смесь у границы ближе к измеренному, чем приор",
            near.remainingSec!! < 34_560L
        )
        assertTrue(
            "на границе остаток меньше, чем чуть раньше — без ступени вверх",
            full.remainingSec!! < near.remainingSec!!
        )
    }

    @Test
    fun windowAverage_estimatesHoursFromEnergyAndTime() {
        // 400 мА: счётчик падает на 100k мкА·ч за 15 минут. За 2 часа
        // (8 интервалов) накоплено 800k мкА·ч / 2 ч = 400k мкА.
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 8, stepUah = 100_000L)
        val estimate = estimator.estimate(
            levelPct = 60, remainingUah = 1_200_000L, nowMs = 7_200_000L
        )
        // Два часа наблюдений — вес приора обнулился, чистое измеренное:
        // остаток 1.2 млн мкА·ч / 400k мкА = 3 ч = 10 800 с.
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
        val before = estimator.estimate(60, 1_000_000L, 85_500_000L)

        val spikeMs = 5_000L
        val spikeUah = 1_667L // ~1.2 А
        val t = 85_500_000L
        estimator.update(snap(t, counterUah = 1_750_000L - spikeUah), t)
        val after = estimator.estimate(60, 1_000_000L, t + spikeMs)

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
        // Два часа чистого разряда после зарядки — оценка из окна:
        // 1.2 млн мкА·ч / 400k мкА = 10 800 с.
        assertEquals(
            "зарядка не должна накапливать расход",
            10_800L,
            estimator.estimate(60, counter, t).remainingSec
        )
    }

    @Test
    fun withoutCounter_intervalsDoNotAccumulate() {
        // Счётчика нет, датчик тока есть: без счётчика интервал не
        // накапливается — показывается приор от уровня, а мгновенный
        // ток в среднее не идёт.
        val estimator = DischargeEstimator()
        var t = 0L
        repeat(20) {
            estimator.update(snap(t, dischargeUa = 500_000L), t)
            t += 900_000L
        }
        val estimate = estimator.estimate(60, 2_000_000L, t)
        assertEquals("без счётчика держим приор от уровня", 34_560L, estimate.remainingSec)
        assertNull(estimate.averageCurrentUa)
        assertEquals(0L, estimate.trackedMs)
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
        // Счётчик не убывает — честный ноль расхода: застой не является
        // измеренным током, поэтому держится приор, а не «0 часов».
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 8, stepUah = 0L)
        val estimate = estimator.estimate(60, 1_200_000L, 7_200_000L)
        assertEquals("застой держит приор, а не нулевые часы", 34_560L, estimate.remainingSec)
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
        // Окно опустело — осознанный возврат к приору, а не 0 ч.
        assertEquals(34_560L, estimator.estimate(60, 1_200_000L, later).remainingSec)
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
        val estimate = estimator.estimate(60, 1_280_000L, 7_200_000L)
        assertEquals(12_800L, estimate.remainingSec!!)
    }

    @Test
    fun restore_nullState_keepsFreshCollector() {
        val estimator = DischargeEstimator()
        estimator.restore(null)
        assertEquals(DischargeState(emptyList(), null, null), estimator.state())
    }

    @Test
    fun noRemainingEnergy_holdsPrior_notNull() {
        // Остатка нет или он неположительный — приор от уровня, а не
        // null и не ноль. Окно наблюдений при этом по-прежнему видно.
        val estimator = DischargeEstimator()
        feed(estimator, intervals = 8, stepUah = 100_000L)
        val withoutBase = estimator.estimate(60, null, 7_200_000L)
        assertEquals(34_560L, withoutBase.remainingSec)
        assertEquals(400_000f, withoutBase.averageCurrentUa!!, 1f)
        assertEquals(34_560L, estimator.estimate(60, 0L, 7_200_000L).remainingSec)
        assertEquals(34_560L, estimator.estimate(60, -1L, 7_200_000L).remainingSec)
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
