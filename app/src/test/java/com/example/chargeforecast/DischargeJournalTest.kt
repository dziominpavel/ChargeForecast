package com.example.chargeforecast

import com.example.chargeforecast.forecast.DischargeJournal
import com.example.chargeforecast.forecast.DischargeJournalText
import com.example.chargeforecast.forecast.DischargeObservation
import com.example.chargeforecast.forecast.DischargeObservationReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

// Журнал наблюдений: строка на каждый замер с причиной пропуска и
// ротация файла (design D5).
class DischargeJournalTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = createTempDir(prefix = "journal-test")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun observation(
        reason: DischargeObservationReason = DischargeObservationReason.INTERVAL,
        intervalUah: Long? = 40_000L,
        intervalMs: Long? = 900_000L,
        counterUah: Long? = 2_400_000L,
        averageUa: Float? = 400_000f
    ) = DischargeObservation(
        wallClockMs = 0L,
        monotonicMs = 3_600_000L,
        levelPct = 87,
        counterUah = counterUah,
        intervalMs = intervalMs,
        intervalUah = intervalUah,
        trackedMs = 3_600_000L,
        averageCurrentUa = averageUa,
        reason = reason
    )

    @Test
    fun line_carriesEveryColumn() {
        val line = DischargeJournalText.line(observation(), remainingSec = 25_200L)
        assertTrue("время", line.contains("1970-01-01"))
        assertTrue("уровень", line.contains("lvl=87%"))
        assertTrue("счётчик", line.contains("counter=2400000"))
        assertTrue("дельта", line.contains("delta=40000uAh"))
        assertTrue("длина интервала", line.contains("dt=900s"))
        assertTrue("окно", line.contains("window=60m"))
        assertTrue("средний ток", line.contains("avg=400mA"))
        assertTrue("остаток", line.contains("left=25200s"))
        assertTrue("результат", line.contains("INTERVAL/estimate"))
    }

    @Test
    fun line_missingValuesAreDashes_notZeros() {
        // Нет счётчика — нет ни дельты, ни интервала, ни тока: прочерки,
        // а не нули, иначе пропуск выглядит как измеренный ноль.
        val line = DischargeJournalText.line(
            observation(
                reason = DischargeObservationReason.NO_COUNTER,
                intervalUah = null,
                intervalMs = null,
                counterUah = null,
                averageUa = null
            ),
            remainingSec = null
        )
        assertTrue(line.contains("counter=-"))
        assertTrue(line.contains("delta=-"))
        assertTrue(line.contains("dt=-"))
        assertTrue(line.contains("avg=-"))
        assertTrue(line.contains("left=-"))
        assertTrue("результат сбора данных", line.contains("NO_COUNTER/collecting"))
    }

    @Test
    fun frequentObservations_areThrottled_rareOnesAlwaysWritten() {
        var now = 0L
        val file = File(dir, "journal.txt")
        val journal = DischargeJournal(
            file,
            minIntervalMs = 60_000L,
            wallClockMs = { now }
        )
        journal.onEstimate(25_200L)
        journal.onObservation(observation())
        now = 1_000L
        journal.onObservation(observation())
        assertEquals("частые замеры троттлятся", 1, file.readLines().size)

        // Редкая причина (аномалия драйвера) пишется всегда.
        journal.onObservation(observation(reason = DischargeObservationReason.COUNTER_GREW))
        assertEquals(2, file.readLines().size)
        assertTrue(file.readLines().last().contains("COUNTER_GREW"))
    }

    @Test
    fun file_rotatesWhenFull() {
        val file = File(dir, "journal.txt")
        file.writeText("x".repeat(200) + "\n")
        val journal = DischargeJournal(file, maxBytes = 100L, wallClockMs = { 120_000L })
        journal.onObservation(observation())
        val old = File(dir, "journal.txt.old")
        assertTrue("старый файл отложен в .old", old.exists())
        assertEquals("x".repeat(200) + "\n", old.readText())
        assertTrue("свежий файл начат заново", file.readLines().size == 1)
    }

    @Test
    fun ioFailure_isSwallowed_notCrashingTheSession() {
        // Журнал — диагностика: его поломка не должна ронять расчёт.
        val journal = DischargeJournal(File(dir, "missing-dir/journal.txt"))
        journal.onObservation(observation())
        assertFalse(File(dir, "missing-dir").exists())
    }
}
