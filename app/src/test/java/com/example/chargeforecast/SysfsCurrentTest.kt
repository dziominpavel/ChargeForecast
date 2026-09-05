package com.example.chargeforecast

import com.example.chargeforecast.battery.SysfsCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Чтение тока из sysfs: эвристика единиц (мА vs мкА) и санитайзер.
class SysfsCurrentTest {

    @Test
    fun parse_milliamps_scaledToMicroamps() {
        // HiSilicon пишет мА: 6000 мА = 6 А → 6 000 000 мкА.
        assertEquals(6_000_000L, SysfsCurrent.parseCurrentUa("6000"))
        assertEquals(300_000L, SysfsCurrent.parseCurrentUa("300"))
        // Знак игнорируем (у драйверов разный).
        assertEquals(6_000_000L, SysfsCurrent.parseCurrentUa("-6000"))
    }

    @Test
    fun parse_microamps_keptAsIs() {
        assertEquals(6_000_000L, SysfsCurrent.parseCurrentUa("6000000"))
        assertEquals(1_500_000L, SysfsCurrent.parseCurrentUa("1500000"))
    }

    @Test
    fun parse_garbage_null() {
        assertNull(SysfsCurrent.parseCurrentUa("abc"))
        assertNull(SysfsCurrent.parseCurrentUa(""))
        // Мусор вне границ зарядного тока отбрасываем.
        assertNull(SysfsCurrent.parseCurrentUa("50"))        // 50 мА — мелочь
        assertNull(SysfsCurrent.parseCurrentUa("60000000"))  // 60 А — абсурд
    }
}
