package com.example.chargeforecast

import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.ChargeForecast
import com.example.chargeforecast.forecast.DischargeEstimate
import com.example.chargeforecast.forecast.ForecastAccuracy
import com.example.chargeforecast.session.SessionState
import com.example.chargeforecast.widget.WidgetMode
import com.example.chargeforecast.widget.WidgetSize
import com.example.chargeforecast.widget.WidgetText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Выбор режима виджета — чистое решение от состояния сессии, без Context
// (спека battery-widget: оба режима, полный заряд, честная деградация).
class WidgetTextTest {

    private fun snap(level: Int, plugged: Boolean) = BatterySnapshot(
        levelPct = level,
        isPlugged = plugged,
        isCharging = plugged,
        isFull = !plugged && false,
        chargeType = if (plugged) ChargeType.AC else ChargeType.NONE,
        temperatureC = null,
        voltageV = null,
        currentMa = if (plugged) 2_400f else null,
        dischargeCurrentUa = if (plugged) null else 400_000L,
        chargeCounterUah = 4_000_000L * level / 100L,
        timestampMs = 0L
    )

    private fun forecast(
        remainingSec: Long? = null,
        accuracy: ForecastAccuracy = ForecastAccuracy.APPROXIMATE,
        isFull: Boolean = false
    ) = ChargeForecast(
        speedPctPerMin = 1.2f,
        remainingSec = remainingSec,
        accuracy = accuracy,
        isFull = isFull
    )

    private fun state(
        level: Int? = null,
        plugged: Boolean = false,
        isFull: Boolean = false,
        remainingSec: Long? = null,
        accuracy: ForecastAccuracy = ForecastAccuracy.APPROXIMATE,
        discharge: DischargeEstimate = DischargeEstimate.NONE
    ): SessionState {
        val snapshot = level?.let { snap(it, plugged) }
        return SessionState(
            snapshot = snapshot,
            forecast = if (snapshot == null) {
                ChargeForecast(null, null, ForecastAccuracy.COLLECTING, false)
            } else {
                forecast(remainingSec, accuracy, isFull)
            },
            sessionAgeMs = 0L,
            discharge = discharge
        )
    }

    @Test
    fun charging_showsCountdownAndLevel() {
        val model = WidgetText.model(
            state(level = 60, plugged = true, remainingSec = 5_400L),
            tracking = true
        )
        assertEquals(WidgetMode.CHARGING, model.mode)
        assertEquals(5_400L, model.remainingSec)
        assertEquals(60, model.levelPct)
        assertTrue("остаток по ярусу точности, не всегда «примерно»", model.approximate)
    }

    @Test
    fun charging_withoutHistory_priorNumber_showsCountdown() {
        // «Нет истории зарядок»: движок подставляет в слот истории приор
        // типа подключения — виджет показывает CHARGING с числом, а не
        // состояние сбора данных (спека battery-widget).
        val model = WidgetText.model(
            state(level = 35, plugged = true, remainingSec = 6_714L),
            tracking = true
        )
        assertEquals(WidgetMode.CHARGING, model.mode)
        assertEquals(6_714L, model.remainingSec)
        assertEquals(35, model.levelPct)
    }

    @Test
    fun charging_withoutNumber_hidesTime_notPlaceholderText() {
        // Модель не выдумывает тексты: числа нет — строка времени просто
        // отсутствует, «Измеряю…» больше негде взяться.
        val model = WidgetText.model(
            state(level = 35, plugged = true, remainingSec = null),
            tracking = true
        )
        assertEquals(WidgetMode.CHARGING, model.mode)
        assertNull(model.remainingSec)
        assertEquals(35, model.levelPct)
    }

    @Test
    fun charging_preciseForecast_isNotMarkedApproximate() {
        val model = WidgetText.model(
            state(
                level = 80,
                plugged = true,
                remainingSec = 900L,
                accuracy = ForecastAccuracy.PRECISE
            ),
            tracking = true
        )
        assertEquals(false, model.approximate)
    }

    @Test
    fun fullCharge_showsFullState_notZeroCountdown() {
        // «Зарядка завершена»: состояние полного заряда, а не нулевой отсчёт.
        val model = WidgetText.model(
            state(level = 100, plugged = true, isFull = true, remainingSec = 0L),
            tracking = true
        )
        assertEquals(WidgetMode.FULL, model.mode)
        assertNull("полный заряд — не отсчёт, даже если конвейер вернул 0", model.remainingSec)
        assertEquals(100, model.levelPct)
    }

    @Test
    fun unplugged_withPriorEstimate_isApproximateDischarge() {
        // Приор «полный заряд ≈ 24 ч»: 58% × 24 ч = 50 112 с — число
        // есть с первой секунды, текстов-заглушек нет.
        val model = WidgetText.model(
            state(
                level = 42,
                plugged = false,
                discharge = DischargeEstimate(remainingSec = 50_112L)
            ),
            tracking = true
        )
        assertEquals(WidgetMode.DISCHARGE, model.mode)
        assertEquals(50_112L, model.remainingSec)
        assertEquals(42, model.levelPct)
        assertTrue("оценка разряда приблизительна по природе", model.approximate)
    }

    @Test
    fun unplugged_withoutNumber_showsNoDataEmpty() {
        // Числа нет только у отключённого оценщика (консервативный
        // режим) — пустое содержимое, без текста-заглушки.
        val model = WidgetText.model(
            state(level = 42, plugged = false, discharge = DischargeEstimate.NONE),
            tracking = true
        )
        assertEquals(WidgetMode.NO_DATA, model.mode)
        assertNull(model.remainingSec)
        assertEquals(42, model.levelPct)
    }

    @Test
    fun stoppedTracking_neverShowsStaleNumber() {
        // «Фоновое слежение остановлено»: любое число могло замерзнуть —
        // видимо состояние остановки, а не застывшее значение.
        val model = WidgetText.model(
            state(
                level = 60,
                plugged = true,
                remainingSec = 5_400L,
                discharge = DischargeEstimate(remainingSec = 39_600L)
            ),
            tracking = false
        )
        assertEquals(WidgetMode.NOT_TRACKING, model.mode)
        assertNull("застрявший отсчёт не должен показываться", model.remainingSec)
    }

    @Test
    fun noStateYet_showsNoData() {
        val model = WidgetText.model(state(level = null), tracking = true)
        assertEquals(WidgetMode.NO_DATA, model.mode)
        assertNull(model.remainingSec)
        assertNull(model.levelPct)
    }

    @Test
    fun sizeBucket_picksNarrowStrip_forOneRowCell() {
        // Полоса 1×3: ширина ячейки привычная, высота — одна строка.
        assertEquals(WidgetSize.NARROW, WidgetText.sizeBucket(210, 80))
        // Низкая, но широкая ячейка тоже узкая: три строки не влезают по высоте.
        assertEquals(WidgetSize.NARROW, WidgetText.sizeBucket(400, 80))
        // Очень узкая ячейка — тоже полоса, там нет места даже строке.
        assertEquals(WidgetSize.NARROW, WidgetText.sizeBucket(130, 200))
    }

    @Test
    fun sizeBucket_keepsMedium_forTypicalCell() {
        // Привычный 3×2 (180×110 dp и крупнее) — средний бакет.
        assertEquals(WidgetSize.MEDIUM, WidgetText.sizeBucket(180, 110))
        assertEquals(WidgetSize.MEDIUM, WidgetText.sizeBucket(320, 170))
    }

    @Test
    fun sizeBucket_picksWide_forLargeField() {
        // Широкое/высокое поле — крупные кегли.
        assertEquals(WidgetSize.WIDE, WidgetText.sizeBucket(340, 180))
        assertEquals(WidgetSize.WIDE, WidgetText.sizeBucket(420, 300))
        // Широко, но низко — не wide: место есть только по одной оси.
        assertEquals(WidgetSize.MEDIUM, WidgetText.sizeBucket(420, 140))
    }
}
