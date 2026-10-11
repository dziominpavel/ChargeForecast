package com.example.chargeforecast.forecast

import kotlin.math.max

// Смесь истории последних сессий и живых замеров (design D2–D4):
//
//   0-10 сек   → 100% истории (остаток с первой секунды);
//   10-180 сек → smoothstep: вес истории плавно уходит в 0;
//   ≥180 сек   → 100% живых замеров (созревает окно S1(2м)).
//
// Точка врезки — базовая скорость (пространство линейной зоны): смесь
// стоит ПОСЛЕ fusion (fusion остаётся чистым оценщиком живых спидометров)
// и ДО интеграла корзин доводки — смешивается причина (скорость), а не
// следствие (остаток).
// Чистый объект — полностью тестируем на JVM.
object HistoryBlender {

    // Удержание 100% истории и граница полного ухода от неё.
    const val HOLD_MS = 10_000L
    const val FULL_MS = 180_000L

    // «Уточняю» (расхождение спидометров >40%): история держит минимум
    // половину оценки, пока живые не сойдутся.
    const val REFINING_MIN_WEIGHT = 0.5f

    // Вес истории по возрасту сессии: smoothstep с нулевой производной
    // на обоих концах — без изломов на 10с и 180с.
    fun weightOf(ageMs: Long): Float {
        if (ageMs <= HOLD_MS) return 1f
        if (ageMs >= FULL_MS) return 0f
        val u = (ageMs - HOLD_MS).toFloat() / (FULL_MS - HOLD_MS)
        return 1f - (3f * u * u - 2f * u * u * u)
    }

    // Итоговая базовая скорость: null — данных нет нигде.
    // Апдейт-правила поверх расписания (design D3):
    //   истории нет            -> только живые (w = 0);
    //   live null/мусорный     -> только история (w = 1);
    //   live «уточняю»         -> w не ниже половины.
    fun blend(
        historyBaseRatePctPerMin: Float?,
        liveBaseRatePctPerMin: Float?,
        refining: Boolean,
        ageMs: Long
    ): Float? {
        val history = historyBaseRatePctPerMin?.takeIf { it > 0f }
        val live = liveBaseRatePctPerMin?.takeIf {
            it >= InstantSpeedEstimator.MIN_CREDIBLE_SPEED_PCT_PER_MIN
        }
        if (history == null) return live
        if (live == null) return history
        var w = weightOf(ageMs)
        if (refining) w = max(w, REFINING_MIN_WEIGHT)
        return w * history + (1f - w) * live
    }
}
