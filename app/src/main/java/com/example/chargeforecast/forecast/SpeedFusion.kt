package com.example.chargeforecast.forecast

// Чистый fusion трёх спидометров по ярусам возраста сессии (см. design D1):
//
//   0-20 сек  → S2 (ток EMA) + приор прошлой сессии — единственный
//               источник с секунды ноль;
//   20-90 сек → S1(30с) доминирует, S2 кросс-чек;
//   >90 сек   → S1(2м) доминирует, S3 кросс-чек;
//   расхождение доминантного и кросс-чека > 40% → консервативная
//   (меньшая) скорость и метка «уточняю».
//
// Мусор-фильтр (урок Huawei P60 Pro): вне доводки скорость ниже порога
// доверия — это НЕ данные, а их отсутствие (рывки счётчика, ток рампы).
// Два мусорных спидометра не должны «затапливать» хороший уровень-дельту.
//
// Тестируем на JVM: все ветки таблицы ярусов покрыты тестами.
object SpeedFusion {

    data class Estimates(
        val counterShortPctPerMin: Float?, // S1(30с)
        val counterLongPctPerMin: Float?,  // S1(2м)
        val currentPctPerMin: Float?,      // S2
        val levelPctPerMin: Float?         // S3
    )

    data class Fused(
        val speedPctPerMin: Float?,
        val refining: Boolean
    )

    fun fuse(
        estimates: Estimates,
        sessionAgeMs: Long,
        priorPctPerMin: Float? = null,
        levelPct: Int = 0
    ): Fused {
        // В доводке (уровень ≥ 80%) медленные скорости честные —
        // мусор-фильтр там не применяется.
        fun credible(rate: Float?): Float? =
            rate?.takeIf {
                levelPct >= TaperCurve.TAPER_START_LEVEL ||
                    it >= InstantSpeedEstimator.MIN_CREDIBLE_SPEED_PCT_PER_MIN
            }
        val raw = when {
            sessionAgeMs < TIER_MEASURING_MAX_MS -> {
                // Секунда ноль: живой источник один — ток; мусор рампы
                // затеняется приором прошлой сессии (см. InstantSpeedEstimator).
                Fused(
                    InstantSpeedEstimator.selectInstantSpeed(
                        estimates.currentPctPerMin, priorPctPerMin
                    ),
                    refining = false
                )
            }
            sessionAgeMs < TIER_SHORT_MAX_MS -> {
                // 20-90 сек: S1(30с) доминирует, S2 кросс-чек.
                tier(
                    dominant = credible(estimates.counterShortPctPerMin),
                    cross = credible(estimates.currentPctPerMin),
                    fallback = credible(estimates.levelPctPerMin),
                    dominantWeight = DOMINANT_WEIGHT
                )
            }
            else -> {
                // >90 сек: S1(2м) доминирует; правило расхождения — только
                // пара S1/S2 (оба живут ~1 Гц); S3 (шаг уровня 1%) — слабый
                // кросс-чек: артефактные 0.5 %/мин не должны «топить» S1.
                val s1 = credible(
                    estimates.counterLongPctPerMin
                        ?: estimates.counterShortPctPerMin
                )
                val s2 = credible(estimates.currentPctPerMin)
                val s3 = credible(estimates.levelPctPerMin)
                when {
                    s1 != null && s2 != null &&
                        divergence(s1, s2) > DIVERGENCE_LIMIT ->
                        Fused(minOf(s1, s2), refining = true)
                    s1 != null && s2 != null -> Fused(
                        s1 * MATURE_DOMINANT_WEIGHT + s2 * (1f - MATURE_DOMINANT_WEIGHT),
                        refining = false
                    )
                    s1 != null && s3 != null &&
                        divergence(s1, s3) <= DIVERGENCE_LIMIT -> Fused(
                        s1 * MATURE_DOMINANT_WEIGHT + s3 * (1f - MATURE_DOMINANT_WEIGHT),
                        refining = false
                    )
                    else -> Fused(s1 ?: s2 ?: s3, refining = false)
                }
            }
        }
        // Вето движущегося уровня (design D1–D4): S3 — единственный ground
        // truth из коробки устройства. Если уровень явно едет (≥ 0.2 %/мин),
        // а итог fusion — null или меньше 30% от S3, итогом становится S3
        // с обычной (не «уточняю») точностью. Применяется ко всем ярусам
        // одинаково как пост-фильтр поверх кандидата.
        val s3 = estimates.levelPctPerMin
        return if (s3 != null && s3 >= MOVING_LEVEL_MIN_PCT_PER_MIN &&
            (raw.speedPctPerMin == null || raw.speedPctPerMin < MOVING_LEVEL_VETO_RATIO * s3)
        ) {
            Fused(s3, refining = false)
        } else {
            raw
        }
    }

    private fun tier(
        dominant: Float?,
        cross: Float?,
        fallback: Float?,
        dominantWeight: Float
    ): Fused {
        if (dominant != null && cross != null &&
            divergence(dominant, cross) > DIVERGENCE_LIMIT
        ) {
            // Расходимся: консервативная скорость и «уточняю» до схождения.
            return Fused(minOf(dominant, cross), refining = true)
        }
        if (dominant != null && cross != null) {
            return Fused(
                dominant * dominantWeight + cross * (1f - dominantWeight),
                refining = false
            )
        }
        val speed = dominant ?: cross ?: fallback
        return Fused(speed, refining = false)
    }

    // Относительное расхождение двух скоростей (0..1+).
    fun divergence(a: Float, b: Float): Float =
        kotlin.math.abs(a - b) / maxOf(a, b)

    const val TIER_MEASURING_MAX_MS = 20_000L
    const val TIER_SHORT_MAX_MS = 90_000L
    const val DIVERGENCE_LIMIT = 0.40f
    const val DOMINANT_WEIGHT = 0.7f
    const val MATURE_DOMINANT_WEIGHT = 0.85f
    // Вето движущегося уровня (design D2–D3): порог здоровья S3 и
    // коэффициент вето. Кандидат ниже 30% от здорового S3 — мусор.
    const val MOVING_LEVEL_MIN_PCT_PER_MIN = 0.2f
    const val MOVING_LEVEL_VETO_RATIO = 0.3f
}
