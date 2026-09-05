package com.example.chargeforecast.forecast

// Накопитель наблюдаемых множителей доводки за сессию: в корзинах
// 80-100 собираем измеренную скорость относительно линейной опоры.
// По итогам сессии корзины с достаточной статистикой отдают выученные
// множители; недобравшие — NaN (слияние их пропускает).
// Чистый класс — тестируем на JVM.
class TaperCurveLearner(
    private val minSamplesPerBin: Int = MIN_SAMPLES_PER_BIN
) {
    private val factorSum = FloatArray(TaperCurve.BIN_COUNT)
    private val counts = IntArray(TaperCurve.BIN_COUNT)

    fun add(levelPct: Int, measuredRatePctPerMin: Float?, linearBaseRatePctPerMin: Float?) {
        val bin = TaperCurve.binIndex(levelPct) ?: return
        if (measuredRatePctPerMin == null || measuredRatePctPerMin <= 0f) return
        if (linearBaseRatePctPerMin == null || linearBaseRatePctPerMin <= 0f) return
        factorSum[bin] += measuredRatePctPerMin / linearBaseRatePctPerMin
        counts[bin]++
    }

    fun learnedFactors(): FloatArray? {
        var any = false
        val out = FloatArray(TaperCurve.BIN_COUNT) { Float.NaN }
        for (i in 0 until TaperCurve.BIN_COUNT) {
            if (counts[i] >= minSamplesPerBin) {
                out[i] = factorSum[i] / counts[i]
                any = true
            }
        }
        return if (any) out else null
    }

    fun reset() {
        for (i in 0 until TaperCurve.BIN_COUNT) {
            factorSum[i] = 0f
            counts[i] = 0
        }
    }

    companion object {
        const val MIN_SAMPLES_PER_BIN = 30
    }
}
