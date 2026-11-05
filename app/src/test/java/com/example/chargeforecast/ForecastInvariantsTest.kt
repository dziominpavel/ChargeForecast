package com.example.chargeforecast

import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.ForecastAccuracy
import com.example.chargeforecast.session.ChargePhase
import com.example.chargeforecast.session.SessionEngine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Инварианты прогноза на записанных трассах (change forecast-integrity):
// I1 заряд в батарее меньше оценённой ёмкости, I2 ёмкость в паспортном
// коридоре ±15%, I3 при уровне ниже 99% остаток не ноль, I4 «точно»
// только на согласованном входе. Трассы — синтетика по числам Huawei
// P20 Pro (ёмкость ≈ 4.03 А·ч, счётчик ≈ 0.81·ёмкости на 81%).
class ForecastInvariantsTest {

    private data class Trace(val name: String, val snapshots: List<BatterySnapshot>)

    private fun load(name: String): Trace {
        val rows = javaClass.getResourceAsStream("/traces/$name")
            ?.bufferedReader()
            ?.useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { line ->
                        val f = line.split(",")
                        val status = f[4]
                        BatterySnapshot(
                            levelPct = f[1].toInt(),
                            isPlugged = true,
                            isCharging = status == "charging",
                            isFull = status == "full",
                            chargeType = ChargeType.valueOf(f[5]),
                            temperatureC = null,
                            voltageV = null,
                            currentMa = f[2].takeIf { it != "-" }?.toFloat(),
                            chargeCounterUah = f[3].toLong(),
                            timestampMs = f[0].toLong()
                        )
                    }
                    .toList()
            } ?: error("трасса не найдена: $name")
        return Trace(name, rows)
    }

    private fun snapshots(trace: Trace): List<BatterySnapshot> = trace.snapshots

    @Test
    fun traces_haveExpectedShape() {
        val ac = snapshots(load("ac_60_to_100.csv"))
        val usb = snapshots(load("usb_weak_pauses.csv"))
        val cable = snapshots(load("cable_no_charge.csv"))
        listOf(ac, usb, cable).forEach { trace ->
            assertTrue("снапшотов должно быть от 600 до 3600: ${trace.size}", trace.size in 600..3600)
            assertTrue("метки времени идут по секунде", trace.zipWithNext().all { (a, b) -> b.timestampMs - a.timestampMs == 1_000L })
            assertTrue("уровень не падает", trace.zipWithNext().all { (a, b) -> b.levelPct >= a.levelPct })
        }
        assertEqualsInt(60, ac.first().levelPct)
        assertEqualsInt(100, ac.last().levelPct)
        assertEqualsInt(81, usb.first().levelPct)
        assertTrue(
            "слабый USB: заряд идёт после паузы",
            usb.last().chargeCounterUah!! > usb.first().chargeCounterUah!!
        )
        assertTrue("кабель без зарядки: пауза в начале", cable.first().isCharging.not())
        assertTrue("кабель без зарядки: зарядка вернулась", cable.last().isCharging)
    }

    private fun assertEqualsInt(expected: Int, actual: Int) =
        org.junit.Assert.assertEquals(expected, actual)

    // Прогон движка по трассе с проверкой инвариантов на каждом тике.
    private fun assertInvariants(trace: Trace, designCapacityUah: Long? = null) {
        val engine = SessionEngine(sysfsCapacityUah = { designCapacityUah })
        for (snap in trace.snapshots) {
            val state = engine.update(snap, snap.timestampMs)
            val tick = engine.lastTick
            val ctx = "${trace.name} t=${snap.timestampMs / 1000}с lvl=${snap.levelPct}"
            val counter = tick?.counterUah
            val capacity = tick?.capacityUah
            val notFull = !snap.isFull && snap.levelPct < 99
            // В STALLED прогноза зарядки нет по спеке — I3 к нему не
            // относится, но вход остаётся согласованным (I1 проверяется).
            val stalled = state.chargePhase == ChargePhase.STALLED
            if (notFull && counter != null && capacity != null) {
                // I1: заряд в батарее не может быть больше ёмкости.
                assertTrue(
                    "$ctx: I1 счётчик $counter не меньше ёмкости $capacity",
                    counter < capacity
                )
            }
            if (designCapacityUah != null && capacity != null) {
                // I2: оценка ёмкости в паспортном коридоре ±15%.
                assertTrue(
                    "$ctx: I2 ёмкость $capacity вне коридора паспортной $designCapacityUah",
                    capacity * 100L in designCapacityUah * 85L..designCapacityUah * 115L
                )
            }
            if (notFull && !stalled) {
                // I3: на неполном заряде остаток не ноль.
                val remaining = state.forecast.remainingSec
                assertNotNull("$ctx: I3 остаток пропал", remaining)
                assertTrue(
                    "$ctx: I3 остаток $remaining не больше нуля",
                    remaining!! > 0L
                )
            }
            if (notFull && !stalled) {
                // I5: показанные скорость и остаток складываются во время.
                val pct = state.forecast.remainingPct
                val sec = state.forecast.remainingSec
                val speed = state.forecast.speedPctPerMin
                if (pct != null && sec != null && sec > 0L && speed != null) {
                    val implied = pct / (sec / 60f)
                    assertTrue(
                        "$ctx: I5 скорость $speed ≠ остаток/время $implied",
                        kotlin.math.abs(implied - speed) <= 0.01f * maxOf(1f, implied)
                    )
                }
            }
            if (notFull && !stalled && state.forecast.accuracy == ForecastAccuracy.PRECISE) {
                // I4: «точно» только на согласованном входе.
                assertTrue(
                    "$ctx: I4 «точно» при противоречивом входе (counter=$counter, cap=$capacity)",
                    counter == null || capacity == null || counter < capacity
                )
            }
        }
    }

    @Test
    fun acCharge_invariantsHold() {
        assertInvariants(load("ac_60_to_100.csv"), designCapacityUah = 4_030_000L)
    }

    @Test
    fun weakUsb_invariantsHold() {
        assertInvariants(load("usb_weak_pauses.csv"))
    }

    @Test
    fun cableWithoutCharging_invariantsHold() {
        assertInvariants(load("cable_no_charge.csv"))
    }

    @Test
    fun cableWithoutCharging_entersStalledAndRecovers() {
        // 60 замеров без CHARGING и роста уровня — STALLED; возврат
        // CHARGING снимает состояние и возвращает прогноз (5.1).
        val engine = SessionEngine()
        var stalledAt: Long? = null
        var recoveredAt: Long? = null
        for (snap in snapshots(load("cable_no_charge.csv"))) {
            val state = engine.update(snap, snap.timestampMs)
            if (state.chargePhase == ChargePhase.STALLED) {
                if (stalledAt == null) stalledAt = snap.timestampMs
                assertNull("в STALLED прогноза зарядки нет", state.forecast.remainingSec)
            } else if (stalledAt != null && snap.isCharging && recoveredAt == null) {
                recoveredAt = snap.timestampMs
                assertNotNull("прогноз вернулся после возобновления зарядки", state.forecast.remainingSec)
            }
        }
        org.junit.Assert.assertEquals(59_000L, stalledAt!!)
        org.junit.Assert.assertEquals(600_000L, recoveredAt!!)
    }
}
