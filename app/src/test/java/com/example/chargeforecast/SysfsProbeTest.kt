package com.example.chargeforecast

import com.example.chargeforecast.battery.SysfsProbe
import java.io.File
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Проба sysfs: отказ запоминается на процесс, успех — нет.
// Иначе запрещённый узел читается каждый тик и забивает лог avc: denied.
class SysfsProbeTest {

    private val path = "/sys/class/power_supply/battery/current_now"

    @Before
    fun setUp() {
        SysfsProbe.reset()
    }

    @After
    fun tearDown() {
        SysfsProbe.reset()
    }

    @Test
    fun deniedPathIsNotReadTwice() {
        var calls = 0
        val reader: (File) -> String = {
            calls++
            throw FileNotFoundException(it.path)
        }

        assertNull(SysfsProbe.readText(path, reader))
        assertNull(SysfsProbe.readText(path, reader))

        assertEquals(1, calls)
        assertTrue(SysfsProbe.isDenied(path))
    }

    @Test
    fun successfulReadIsNotCached() {
        var calls = 0
        val reader: (File) -> String = {
            calls++
            "4200"
        }

        assertEquals("4200", SysfsProbe.readText(path, reader))
        assertEquals("4200", SysfsProbe.readText(path, reader))

        assertEquals(2, calls)
        assertFalse(SysfsProbe.isDenied(path))
    }
}