package com.example.chargeforecast

import com.example.chargeforecast.forecast.SpeedFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Fusion трёх спидометров по ярусам возраста (см. design D1):
// все ветки таблицы ярусов.
class SpeedFusionTest {

    private fun estimates(
        s1Short: Float? = null,
        s1Long: Float? = null,
        s2: Float? = null,
        s3: Float? = null
    ) = SpeedFusion.Estimates(s1Short, s1Long, s2, s3)

    @Test
    fun tierMeasuring_usesCurrentWithPrior() {
        // 0-20 сек: только S2; приор затеняет мусор рампы.
        val fused = SpeedFusion.fuse(estimates(s2 = 0.05f), 10_000L, priorPctPerMin = 2.0f)
        assertEquals(2.0f, fused.speedPctPerMin!!, 1e-6f)
        assertFalse(fused.refining)
        // Мусор рампы ниже порога — приор остаётся единственной опорой.
        val shaded = SpeedFusion.fuse(estimates(s2 = 0.01f), 10_000L, priorPctPerMin = 2.0f)
        assertEquals(2.0f, shaded.speedPctPerMin!!, 1e-6f)
        // Приора нет, мусор ниже порога — цифр нет.
        val noData = SpeedFusion.fuse(estimates(s2 = 0.01f), 10_000L, priorPctPerMin = null)
        assertNull(noData.speedPctPerMin)
    }

    @Test
    fun tierShort_s1DominatesWithS2CrossCheck() {
        // 20-90 сек: S1(30с) доминирует, S2 кросс-чек; согласие — бленд 0.7/0.3.
        val fused = SpeedFusion.fuse(
            estimates(s1Short = 1.0f, s2 = 1.2f), 60_000L
        )
        assertEquals(1.0f * 0.7f + 1.2f * 0.3f, fused.speedPctPerMin!!, 1e-4f)
        assertFalse(fused.refining)
    }

    @Test
    fun tierShort_divergence_refinesToConservative() {
        // Расхождение >40% — меньшая скорость и «уточняю».
        val fused = SpeedFusion.fuse(
            estimates(s1Short = 1.0f, s2 = 2.0f), 60_000L
        )
        assertEquals(1.0f, fused.speedPctPerMin!!, 1e-6f)
        assertTrue(fused.refining)
    }

    @Test
    fun tierMature_s1LongDominatesWithS3WeakCrossCheck() {
        // >90 сек: S1(2м) доминирует с большим весом, S3 — слабый кросс-чек.
        val fused = SpeedFusion.fuse(
            estimates(s1Short = 1.5f, s1Long = 1.0f, s3 = 1.0f), 120_000L
        )
        assertEquals(1.0f, fused.speedPctPerMin!!, 1e-4f)
        assertFalse(fused.refining)
        // Короткого окна нет — S1(30с) становится доминантой (бленд 0.85/0.15).
        val fallback = SpeedFusion.fuse(
            estimates(s1Short = 1.2f, s3 = 1.0f), 120_000L
        )
        assertEquals(1.2f * 0.85f + 1.0f * 0.15f, fallback.speedPctPerMin!!, 1e-4f)
    }

    @Test
    fun tierMature_divergenceOfS1S2_refinesToConservative() {
        // Правило расхождения — пара S1/S2: меньшая скорость и «уточняю».
        val fused = SpeedFusion.fuse(
            estimates(s1Long = 1.0f, s2 = 2.0f), 120_000L
        )
        assertEquals(1.0f, fused.speedPctPerMin!!, 1e-6f)
        assertTrue(fused.refining)
    }

    @Test
    fun tierMature_coarseS3Divergence_ignored() {
        // S3 (шаг уровня 1%) штатно отстаёт (1% за минуту по парам точек)
        // — его расхождение не «топит» честный S1 и не даёт «уточняю».
        val fused = SpeedFusion.fuse(
            estimates(s1Long = 1.0f, s3 = 0.4f), 120_000L
        )
        assertEquals(1.0f, fused.speedPctPerMin!!, 1e-6f)
        assertFalse(fused.refining)
    }

    @Test
    fun missingCrossCheck_takesDominant() {
        val fused = SpeedFusion.fuse(
            estimates(s1Long = 1.0f, s3 = null), 120_000L
        )
        assertEquals(1.0f, fused.speedPctPerMin!!, 1e-6f)
        assertFalse(fused.refining)
        // Вообще ничего — цифр нет.
        assertNull(SpeedFusion.fuse(estimates(), 120_000L).speedPctPerMin)
    }

    @Test
    fun tierShort_garbageS1S2_fallsBackToLevel() {
        // Регрессия Huawei P60 Pro: счётчик рывками (МНК даёт крошку),
        // ток 7 мА рампы — оба мусор ниже порога; уровень 3.74 —
        // единственный живой источник, он и должен победить.
        val fused = SpeedFusion.fuse(
            estimates(s1Short = 0.0004f, s2 = 0.029f, s3 = 3.74f),
            46_000L,
            levelPct = 60
        )
        assertEquals(3.74f, fused.speedPctPerMin!!, 1e-4f)
        assertFalse(fused.refining)
    }

    @Test
    fun tierShort_garbageS1S2_taperZone_slowIsCredible() {
        // В доводке (уровень ≥ 80) медленные скорости честные —
        // мусор-фильтр не применяется.
        val fused = SpeedFusion.fuse(
            estimates(s1Short = 0.0004f, s2 = 0.029f, s3 = 0.05f),
            46_000L,
            levelPct = 85
        )
        // Оба мусорные есть, кросс-чек расходится — консервативный min.
        assertEquals(0.0004f, fused.speedPctPerMin!!, 1e-6f)
        assertTrue(fused.refining)
    }

    @Test
    fun divergence_relativeToLarger() {
        assertEquals(0.5f, SpeedFusion.divergence(1.0f, 2.0f), 1e-6f)
        assertEquals(0.5f, SpeedFusion.divergence(2.0f, 1.0f), 1e-6f)
        assertEquals(0f, SpeedFusion.divergence(1.5f, 1.5f), 1e-6f)
    }

    @Test
    fun liveSession_garbageCurrent_movingLevelVetoes() {
        // Регрессия живой сессии автора: уровень 87→90 за ~90 сек
        // (≈2 %/мин), счётчик стоит (S1 — null), ток 7 мА даёт мусор
        // S2 — 0.003. Движущийся уровень — ground truth: итог обязан
        // быть S3, а не мусорным минимумом.
        val fused = SpeedFusion.fuse(
            estimates(s1Short = null, s1Long = null, s2 = 0.003f, s3 = 2.0f),
            120_000L,
            priorPctPerMin = null,
            levelPct = 87
        )
        assertEquals(2.0f, fused.speedPctPerMin!!, 1e-4f)
        assertFalse(fused.refining)
    }
}
