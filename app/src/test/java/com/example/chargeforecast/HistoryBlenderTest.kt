package com.example.chargeforecast

import com.example.chargeforecast.forecast.HistoryBlender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Смесь истории последних сессий и живых замеров (design D2/D3):
// вес истории держится 100% первые 10с, плавно уходит в 0 к 180с.
class HistoryBlenderTest {

    // Снапшоты кривой smoothstep (design D2).
    @Test
    fun weight_scheduleSnapshots() {
        assertEquals(1f, HistoryBlender.weightOf(0L), 1e-6f)
        assertEquals(1f, HistoryBlender.weightOf(10_000L), 1e-6f)
        assertEquals(0.96f, HistoryBlender.weightOf(30_000L), 0.01f)
        assertEquals(0.79f, HistoryBlender.weightOf(60_000L), 0.01f)
        assertEquals(0.54f, HistoryBlender.weightOf(90_000L), 0.01f)
        assertEquals(0.28f, HistoryBlender.weightOf(120_000L), 0.01f)
        assertEquals(0.08f, HistoryBlender.weightOf(150_000L), 0.01f)
        assertEquals(0f, HistoryBlender.weightOf(180_000L), 1e-6f)
        assertEquals(0f, HistoryBlender.weightOf(600_000L), 1e-6f)
    }

    @Test
    fun weight_monotonicallyDecreasing() {
        var prev = Float.MAX_VALUE
        for (t in 0L..200_000L step 1_000L) {
            val w = HistoryBlender.weightOf(t)
            assertTrue("вес вырос на ${t / 1000}с: $w > $prev", w <= prev + 1e-6f)
            prev = w
        }
    }

    @Test
    fun blend_noHistory_passesLiveThrough() {
        // Первая сессия устройства: истории нет — только живые данные.
        assertEquals(
            1.5f,
            HistoryBlender.blend(null, 1.5f, refining = false, ageMs = 0L)!!,
            1e-6f
        )
    }

    @Test
    fun blend_noLive_carriesHistory() {
        // Рампа/нет датчиков: живой источник пуст — несёт история.
        assertEquals(
            2.0f,
            HistoryBlender.blend(2.0f, null, refining = false, ageMs = 0L)!!,
            1e-6f
        )
        assertEquals(
            2.0f,
            HistoryBlender.blend(
                2.0f, null, refining = false, ageMs = 180_000L
            )!!,
            1e-6f
        )
    }

    @Test
    fun blend_liveBelowCredibility_carriesHistory() {
        // Живая оценка мельче порога доверия — это отсутствие данных.
        assertEquals(
            2.0f,
            HistoryBlender.blend(
                2.0f, 0.01f, refining = false, ageMs = 60_000L
            )!!,
            1e-6f
        )
    }

    @Test
    fun blend_pureEndpoints() {
        // Секунда ноль: 100% история. 180-я секунда: 100% живые.
        assertEquals(
            2.0f,
            HistoryBlender.blend(2.0f, 1.0f, refining = false, ageMs = 0L)!!,
            1e-6f
        )
        assertEquals(
            1.0f,
            HistoryBlender.blend(
                2.0f, 1.0f, refining = false, ageMs = 180_000L
            )!!,
            1e-6f
        )
    }

    @Test
    fun blend_weightedMixAtNinetySeconds() {
        // 90с: w≈0.544 → 0.544·2.0 + 0.456·1.0 ≈ 1.544.
        val blended = HistoryBlender.blend(
            2.0f, 1.0f, refining = false, ageMs = 90_000L
        )!!
        assertTrue("ожидали ~1.544, получили $blended", blended in 1.50f..1.59f)
    }

    @Test
    fun blend_refiningFloorsWeightAtHalf() {
        // «Уточняю»: история держит минимум половину оценки.
        val blended = HistoryBlender.blend(
            2.0f, 1.0f, refining = true, ageMs = 150_000L
        )!!
        assertEquals("w срезан снизу до 0.5", 1.5f, blended, 1e-3f)
        // И рано, когда расписание и так выше половины — правило не занижает.
        val early = HistoryBlender.blend(
            2.0f, 1.0f, refining = true, ageMs = 30_000L
        )!!
        assertTrue("w≈0.96 должен сохраниться", early > 1.9f)
    }

    @Test
    fun blend_bothNull_returnsNull() {
        assertNull(HistoryBlender.blend(null, null, refining = false, ageMs = 0L))
    }

    @Test
    fun blend_neverExtrapolatesOutsideInputs() {
        // Смесь — выпуклая комбинация: результат всегда между историей и live.
        for (t in 0L..200_000L step 5_000L) {
            val b = HistoryBlender.blend(2.0f, 1.0f, refining = false, ageMs = t)!!
            assertTrue("смесь вне [1,2] на ${t / 1000}с: $b", b in 1f..2f)
        }
    }
}
