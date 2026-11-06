package com.example.chargeforecast.forecast

// Чистый fusion трёх спидометров по ярусам возраста сессии (см. design D1)
// с поправкой на режим счётчика (design D4):
//
//   точный счётчик:
//     0-20 сек  → S2 (ток EMA) с credible-фильтром;
//     20-90 сек → S1(30с) доминирует, S2 кросс-чек;
//     >90 сек   → S1(2м) доминирует, S3 кросс-чек;
//   грубый счётчик (шаг 1% ёмкости, фаза отстаёт):
//     0-20 сек  → S2, только если датчик тока валиден;
//     20-90 сек → S1(2м) доминирует, S3 кросс-чек; S1(30с) не доминирует;
//     >90 сек   → S3 ведёт (0.85/0.15), S1(2м) кросс-чек; при расхождении
//                 >40% — S3 с «уточняю».
//
// Мусор-фильтр (урок Huawei P60 Pro): вне доводки скорость ниже порога
// доверия — это НЕ данные, а их отсутствие. S2 дополнительно требует
// валидного датчика тока: обход порога в доводке не должен пропускать
// мёртвый 0.00 (MNA-LX9 отдаёт 1-12 мА).
//
// Тестируем на JVM: все ветки таблицы ярусов покрыты тестами.
object SpeedFusion {

    data class Estimates(
        val counterShortPctPerMin: Float?, // S1(30с)
        val counterLongPctPerMin: Float?,  // S1(2м)
        val currentPctPerMin: Float?,      // S2
        val levelPctPerMin: Float?,        // S3
        // Длинное окно счётчика (10 мин) — для гейта «точно» в грубом
        // режиме и как резерв доминанты.
        val counterMaturePctPerMin: Float? = null
    )

    data class Fused(
        val speedPctPerMin: Float?,
        val refining: Boolean
    )

    fun fuse(
        estimates: Estimates,
        sessionAgeMs: Long,
        levelPct: Int = 0,
        counterMode: CounterMode = CounterMode.FINE,
        currentValid: Boolean = true
    ): Fused {
        // S2 допускается только при валидном токе: на грубом счётчике
        // мёртвый 0.00 иначе проходит обход порога в доводке.
        val s2 = if (currentValid) {
            credible(estimates.currentPctPerMin, levelPct)
        } else {
            null
        }
        val raw = when {
            sessionAgeMs < TIER_MEASURING_MAX_MS -> {
                // Секунда ноль: живой источник один — ток (если валиден).
                // Мусор рампы — это отсутствие данных, HistoryBlender
                // подхватит историю.
                Fused(s2, refining = false)
            }
            sessionAgeMs < TIER_SHORT_MAX_MS -> when (counterMode) {
                CounterMode.FINE -> tier(
                    dominant = credible(estimates.counterShortPctPerMin, levelPct),
                    cross = s2,
                    fallback = credible(estimates.levelPctPerMin, levelPct),
                    dominantWeight = DOMINANT_WEIGHT
                )
                CounterMode.COARSE -> tier(
                    dominant = credible(estimates.counterLongPctPerMin, levelPct)
                        ?: credible(estimates.counterMaturePctPerMin, levelPct),
                    cross = credible(estimates.levelPctPerMin, levelPct),
                    fallback = s2,
                    dominantWeight = DOMINANT_WEIGHT
                )
            }
            else -> when (counterMode) {
                CounterMode.FINE -> matureFine(estimates, s2, levelPct)
                CounterMode.COARSE -> matureCoarse(estimates, s2, levelPct)
            }
        }
        // Вето движущегося уровня (design D1–D4): S3 — единственный ground
        // truth из коробки устройства. Если уровень явно едет (≥ 0.2 %/мин),
        // а итог fusion — null или меньше 30% от S3, итогом становится S3
        // с обычной (не «уточняю») точностью.
        val s3 = estimates.levelPctPerMin
        return if (s3 != null && s3 >= MOVING_LEVEL_MIN_PCT_PER_MIN &&
            (raw.speedPctPerMin == null || raw.speedPctPerMin < MOVING_LEVEL_VETO_RATIO * s3)
        ) {
            Fused(s3, refining = false)
        } else {
            raw
        }
    }

    private fun matureFine(estimates: Estimates, s2: Float?, levelPct: Int): Fused {
        val s1 = credible(
            estimates.counterLongPctPerMin ?: estimates.counterShortPctPerMin,
            levelPct
        )
        val s3 = credible(estimates.levelPctPerMin, levelPct)
        return when {
            s1 != null && s2 != null && divergence(s1, s2) > DIVERGENCE_LIMIT ->
                Fused(minOf(s1, s2), refining = true)
            s1 != null && s2 != null -> Fused(
                s1 * MATURE_DOMINANT_WEIGHT + s2 * (1f - MATURE_DOMINANT_WEIGHT),
                refining = false
            )
            s1 != null && s3 != null && divergence(s1, s3) <= DIVERGENCE_LIMIT -> Fused(
                s1 * MATURE_DOMINANT_WEIGHT + s3 * (1f - MATURE_DOMINANT_WEIGHT),
                refining = false
            )
            else -> Fused(s1 ?: s2 ?: s3, refining = false)
        }
    }

    // Грубый режим: уровень — ground truth, счётчик фазово отстаёт.
    private fun matureCoarse(estimates: Estimates, s2: Float?, levelPct: Int): Fused {
        val s1 = credible(
            estimates.counterLongPctPerMin ?: estimates.counterMaturePctPerMin,
            levelPct
        )
        val s3 = credible(estimates.levelPctPerMin, levelPct)
        return when {
            s3 != null && s1 != null && divergence(s1, s3) > DIVERGENCE_LIMIT ->
                Fused(s3, refining = true)
            s3 != null && s1 != null -> Fused(
                s3 * COARSE_LEVEL_WEIGHT + s1 * (1f - COARSE_LEVEL_WEIGHT),
                refining = false
            )
            s3 != null -> Fused(s3, refining = false)
            s1 != null -> Fused(s1, refining = false)
            else -> Fused(s2, refining = false)
        }
    }

    private fun credible(rate: Float?, levelPct: Int): Float? =
        rate?.takeIf {
            levelPct >= TaperCurve.TAPER_START_LEVEL ||
                it >= InstantSpeedEstimator.MIN_CREDIBLE_SPEED_PCT_PER_MIN
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
    // Грубый режим: вес уровня как доминанты зрелого яруса.
    const val COARSE_LEVEL_WEIGHT = 0.85f
    // Вето движущегося уровня (design D2–D3): порог здоровья S3 и
    // коэффициент вето. Кандидат ниже 30% от здорового S3 — мусор.
    const val MOVING_LEVEL_MIN_PCT_PER_MIN = 0.2f
    const val MOVING_LEVEL_VETO_RATIO = 0.3f
}
