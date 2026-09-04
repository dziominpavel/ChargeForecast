package com.example.chargeforecast

import com.example.chargeforecast.battery.SysfsCapacity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Санитайзер sysfs-ёмкости: мА·ч вместо мкА·ч отбрасываем,
// лучше null, чем ошибка в тысячу раз.
class SysfsCapacityTest {

    @Test
    fun saneMicroAmpHours_passThrough() {
        assertEquals(4_000_000L, SysfsCapacity.sanitizeCapacityUah(4_000_000L))
        assertEquals(500_000L, SysfsCapacity.sanitizeCapacityUah(500_000L))
        assertEquals(30_000_000L, SysfsCapacity.sanitizeCapacityUah(30_000_000L))
    }

    @Test
    fun garbage_rejected() {
        assertNull(SysfsCapacity.sanitizeCapacityUah(null))
        assertNull(SysfsCapacity.sanitizeCapacityUah(0L))
        assertNull(SysfsCapacity.sanitizeCapacityUah(-10L))
        // Похоже на мА·ч, а не мкА·ч — не берём.
        assertNull(SysfsCapacity.sanitizeCapacityUah(4_000L))
        assertNull(SysfsCapacity.sanitizeCapacityUah(100_000_000L))
        assertNull(SysfsCapacity.sanitizeCapacityUah(Long.MIN_VALUE))
    }
}
