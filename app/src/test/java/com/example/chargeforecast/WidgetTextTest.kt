package com.example.chargeforecast

import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.ChargeForecast
import com.example.chargeforecast.forecast.DischargeEstimate
import com.example.chargeforecast.forecast.ForecastAccuracy
import com.example.chargeforecast.session.SessionState
import com.example.chargeforecast.tracking.TrackingHealth
import com.example.chargeforecast.tracking.TrackingStatus
import com.example.chargeforecast.widget.WidgetMode
import com.example.chargeforecast.widget.WidgetSize
import com.example.chargeforecast.widget.WidgetText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Модель виджета — чистое решение по состоянию сессии, без Context
// (спека battery-widget: один режим, один бакет, честная деградация).
class WidgetTextTest {

    private fun snap(level: Int, plugged: Boolean) = BatterySnapshot(
        levelPct = level,
        isPlugged = plugged,
        isCharging = plugged,
        isFull = false,
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
        discharge: DischargeEstimate = DischargeEstimate.NONE,
        tracking: TrackingHealth = TrackingHealth.running()
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
            discharge = discharge,
            tracking = tracking
        )
    }

    @Test
    fun charging_showsCountdownAndLevel() {
        val model = WidgetText.model(
            state(level = 60, plugged = true, remainingSec = 5_400L)
        )
        assertEquals(WidgetMode.CHARGING, model.mode)
        assertEquals(5_400L, model.remainingSec)
        assertEquals(60, model.levelPct)
        assertTrue("отсчёт по уровню, но ещё <примерно>", model.approximate)
    }

    @Test
    fun charging_withoutHistory_priorNumber_showsCountdown() {
        // «До 100% с первой секунды»: прошлых сессий нет, но приор типа
        // подключения уже даёт число — заглушки не показываем.
        val model = WidgetText.model(
            state(level = 35, plugged = true, remainingSec = 6_714L)
        )
        assertEquals(WidgetMode.CHARGING, model.mode)
        assertEquals(6_714L, model.remainingSec)
        assertEquals(35, model.levelPct)
    }

    @Test
    fun charging_withoutNumber_hidesTime_notPlaceholderText() {
        // Числа нет — строка времени просто скрывается, «Измеряю…» на
        // виджете не появляется.
        val model = WidgetText.model(
            state(level = 35, plugged = true, remainingSec = null)
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
            )
        )
        assertEquals(false, model.approximate)
    }

    @Test
    fun fullCharge_showsFullState_notZeroCountdown() {
        // «Зарядка завершена»: виджет показывает состояние, а не нулевой
        // отсчёт.
        val model = WidgetText.model(
            state(level = 100, plugged = true, isFull = true, remainingSec = 0L)
        )
        assertEquals(WidgetMode.FULL, model.mode)
        assertNull("полный заряд — не отсчёт, даже если пришёл 0", model.remainingSec)
        assertEquals(100, model.levelPct)
    }

    @Test
    fun unplugged_withPriorEstimate_isApproximateDischarge() {
        // Приор «полный заряд ≈ 24 ч»: 58% × 24 ч = 50 112 с — число есть
        // с первой секунды, текстов-заглушек нет.
        val model = WidgetText.model(
            state(
                level = 42,
                plugged = false,
                discharge = DischargeEstimate(remainingSec = 50_112L)
            )
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
            state(level = 42, plugged = false, discharge = DischargeEstimate.NONE)
        )
        assertEquals(WidgetMode.NO_DATA, model.mode)
        assertNull(model.remainingSec)
        assertEquals(42, model.levelPct)
    }

    @Test
    fun stoppedTracking_neverShowsStaleNumber() {
        // «Слежение остановлено»: любое число могло замерзнуть —
        // виджет честно показывает остановку, а не последнее значение.
        val model = WidgetText.model(
            state(
                level = 60,
                plugged = true,
                remainingSec = 5_400L,
                discharge = DischargeEstimate(remainingSec = 39_600L),
                tracking = TrackingHealth(status = TrackingStatus.STOPPED)
            )
        )
        assertEquals(WidgetMode.NOT_TRACKING, model.mode)
        assertNull("застывший отсчёт не показывается", model.remainingSec)
    }

    @Test
    fun startingTracking_isTreatedAsOperational() {
        // Холодный старт: STARTING ещё не RUNNING, но виджет не должен
        // мигать «слежение остановлено» на время запуска сервиса.
        val model = WidgetText.model(
            state(
                level = 60,
                plugged = true,
                remainingSec = 5_400L,
                tracking = TrackingHealth(status = TrackingStatus.STARTING)
            )
        )
        assertEquals(WidgetMode.CHARGING, model.mode)
        assertEquals(5_400L, model.remainingSec)
    }

    @Test
    fun noStateYet_showsNoData() {
        val model = WidgetText.model(state(level = null))
        assertEquals(WidgetMode.NO_DATA, model.mode)
        assertNull(model.remainingSec)
        assertNull(model.levelPct)
    }

    @Test
    fun sizeBucket_picksNarrowStrip_forOneRowCell() {
        // Полоса 1×3: ширина любая, высота — одна строка.
        assertEquals(WidgetSize.NARROW, WidgetText.sizeBucket(210, 80))
        // Широкая, но низкая ячейка тоже узкая: по высоте не влезает.
        assertEquals(WidgetSize.NARROW, WidgetText.sizeBucket(400, 80))
        // Очень узкая ячейка — тоже узкая, там всё равно одна полоса.
        assertEquals(WidgetSize.NARROW, WidgetText.sizeBucket(130, 200))
    }

    @Test
    fun sizeBucket_keepsMedium_forTypicalCell() {
        // Привычная 3×2 (180×110 dp в минимуме) — средний бакет.
        assertEquals(WidgetSize.MEDIUM, WidgetText.sizeBucket(180, 110))
        assertEquals(WidgetSize.MEDIUM, WidgetText.sizeBucket(320, 170))
    }

    @Test
    fun sizeBucket_picksWide_forLargeField() {
        // Широкая/высокая ячейка — крупный бакет.
        assertEquals(WidgetSize.WIDE, WidgetText.sizeBucket(340, 180))
        assertEquals(WidgetSize.WIDE, WidgetText.sizeBucket(420, 300))
        // Широкая, но низкая — не wide: высоты только на строку.
        assertEquals(WidgetSize.MEDIUM, WidgetText.sizeBucket(420, 140))
    }
}
