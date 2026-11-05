package com.example.chargeforecast

import com.example.chargeforecast.diag.DiagEvent
import com.example.chargeforecast.diag.DiagEventKind
import com.example.chargeforecast.diag.DiagText
import com.example.chargeforecast.diag.DiagTick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Формат журнала: ключ=значение через «;», пропуск — «-».
// Строка читается глазами и грепается без парсера.
class DiagTextTest {

    private fun tick(
        atMs: Long = 1_000L,
        s1Long: Float? = null,
        s2: Float? = null
    ) = DiagTick(
        atMs = atMs,
        levelPct = 81,
        plugged = true,
        charging = true,
        full = false,
        chargeType = "AC",
        currentMa = 4f,
        counterUah = 3_268_000L,
        capacityUah = 4_035_200L,
        s1ShortPctPerMin = 2.94f,
        s1LongPctPerMin = s1Long,
        s2PctPerMin = s2,
        s3PctPerMin = 3.01f,
        fusedPctPerMin = 3.01f,
        baseRatePctPerMin = 5.47f,
        taperFactor = 0.55f,
        rawRemainingSec = 676L,
        shownRemainingSec = 671L,
        accuracy = "PRECISE",
        refining = false
    )

    @Test
    fun tickHasFixedFieldOrder() {
        val line = DiagText.tick(tick())

        assertEquals(
            "t=1000;lvl=81;plug=1;chg=1;full=0;type=AC;ma=4;cnt=3268000;cap=4035200;" +
                "s1=2.94;s1l=-;s2=-;s3=3.01;fus=3.01;base=5.47;k=0.55;raw=676;shown=671;" +
                "eff=-;acc=PRECISE;ref=0;ph=CHARGING",
            line
        )
        assertEquals(22, line.split(";").size)
    }

    @Test
    fun tickKeepsFieldCountWhenValuesMissing() {
        val missing = DiagText.tick(tick(atMs = 5_000L, s1Long = 1.5f, s2 = 2.5f))
        val full = DiagText.tick(tick())

        assertEquals(full.split(";").size, missing.split(";").size)
        assertTrue(missing.contains("s1l=1.50"))
        assertTrue(missing.contains("s2=2.50"))
        assertTrue(missing.contains("t=5000"))
    }

    @Test
    fun eventHasKindAndFields() {
        val line = DiagText.event(
            7_000L,
            DiagEvent(DiagEventKind.CAPACITY, "was=4035200;now=3230000;why=refined")
        )

        assertEquals("t=7000;ev=cap;was=4035200;now=3230000;why=refined", line)
    }
}