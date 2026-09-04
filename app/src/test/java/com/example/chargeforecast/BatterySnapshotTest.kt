package com.example.chargeforecast

import android.os.BatteryManager
import com.example.chargeforecast.battery.BatteryExtras
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.battery.sanitizeCurrentUa
import com.example.chargeforecast.battery.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Чистый маппинг extras -> snapshot, без Android-рантайма.
class BatterySnapshotTest {

    private fun extras(
        level: Int = 42,
        scale: Int = 100,
        status: Int = BatteryManager.BATTERY_STATUS_CHARGING,
        plugged: Int = BatteryManager.BATTERY_PLUGGED_AC,
        temperatureTenthsC: Int? = 285,
        voltageMv: Int? = 4100,
        currentNowUa: Long? = 1_500_000L,
        chargeCounterUah: Long? = 2_000_000L
    ) = BatteryExtras(
        level = level,
        scale = scale,
        status = status,
        plugged = plugged,
        temperatureTenthsC = temperatureTenthsC,
        voltageMv = voltageMv,
        currentNowUa = currentNowUa,
        chargeCounterUah = chargeCounterUah
    )

    @Test
    fun level_mapsToPercent() {
        assertEquals(42, extras(level = 42, scale = 100).toSnapshot(0L).levelPct)
        assertEquals(42, extras(level = 420, scale = 1000).toSnapshot(0L).levelPct)
    }

    @Test
    fun level_clampedToValidRange() {
        assertEquals(100, extras(level = 150).toSnapshot(0L).levelPct)
        assertEquals(0, extras(level = -5).toSnapshot(0L).levelPct)
        assertEquals(0, extras(level = 50, scale = 0).toSnapshot(0L).levelPct)
    }

    @Test
    fun status_chargingAndFull() {
        val charging = extras(status = BatteryManager.BATTERY_STATUS_CHARGING)
            .toSnapshot(0L)
        assertTrue(charging.isCharging)
        assertFalse(charging.isFull)

        val full = extras(status = BatteryManager.BATTERY_STATUS_FULL)
            .toSnapshot(0L)
        assertTrue(full.isFull)
        assertFalse(full.isCharging)

        val idle = extras(
            status = BatteryManager.BATTERY_STATUS_DISCHARGING,
            plugged = 0
        ).toSnapshot(0L)
        assertFalse(idle.isCharging)
        assertFalse(idle.isFull)
    }

    @Test
    fun plugged_mapsToChargeType() {
        val ac = extras(plugged = BatteryManager.BATTERY_PLUGGED_AC).toSnapshot(0L)
        assertTrue(ac.isPlugged)
        assertEquals(ChargeType.AC, ac.chargeType)

        val usb = extras(plugged = BatteryManager.BATTERY_PLUGGED_USB).toSnapshot(0L)
        assertEquals(ChargeType.USB, usb.chargeType)

        val wireless = extras(
            plugged = BatteryManager.BATTERY_PLUGGED_WIRELESS
        ).toSnapshot(0L)
        assertEquals(ChargeType.WIRELESS, wireless.chargeType)

        val none = extras(plugged = 0).toSnapshot(0L)
        assertFalse(none.isPlugged)
        assertEquals(ChargeType.NONE, none.chargeType)

        val unknown = extras(plugged = 8).toSnapshot(0L)
        assertTrue(unknown.isPlugged)
        assertEquals(ChargeType.UNKNOWN, unknown.chargeType)
    }

    @Test
    fun temperature_convertsTenths() {
        assertEquals(28.5f, extras(temperatureTenthsC = 285).toSnapshot(0L).temperatureC!!)
        assertNull(extras(temperatureTenthsC = null).toSnapshot(0L).temperatureC)
    }

    @Test
    fun voltage_convertsMillivolts() {
        assertEquals(4.1f, extras(voltageMv = 4100).toSnapshot(0L).voltageV!!)
        assertNull(extras(voltageMv = 0).toSnapshot(0L).voltageV)
        assertNull(extras(voltageMv = null).toSnapshot(0L).voltageV)
    }

    @Test
    fun current_shownOnlyWhileCharging() {
        val charging = extras(currentNowUa = 1_500_000L).toSnapshot(0L)
        assertEquals(1500f, charging.currentMa!!)

        // Разрядка: ток отрицательный — не показываем.
        val discharging = extras(
            status = BatteryManager.BATTERY_STATUS_DISCHARGING,
            plugged = 0,
            currentNowUa = -500_000L
        ).toSnapshot(0L)
        assertNull(discharging.currentMa)

        // Датчика нет — fallback на дельту уровня, поле пустое.
        assertNull(extras(currentNowUa = null).toSnapshot(0L).currentMa)
    }

    @Test
    fun counter_passesThroughPositiveOnly() {
        assertEquals(2_000_000L, extras().toSnapshot(0L).chargeCounterUah)
        assertNull(extras(chargeCounterUah = null).toSnapshot(0L).chargeCounterUah)
        assertNull(extras(chargeCounterUah = 0L).toSnapshot(0L).chargeCounterUah)
        assertNull(
            extras(chargeCounterUah = Long.MIN_VALUE).toSnapshot(0L).chargeCounterUah
        )
    }

    @Test
    fun sanitizeCurrent_handlesDriverSentinels() {
        assertNull(sanitizeCurrentUa(Long.MIN_VALUE))
        assertNull(sanitizeCurrentUa(0L))
        assertEquals(1_500_000L, sanitizeCurrentUa(1_500_000L))
        assertEquals(-500_000L, sanitizeCurrentUa(-500_000L))
    }
}
