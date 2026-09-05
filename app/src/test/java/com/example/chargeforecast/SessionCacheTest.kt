package com.example.chargeforecast

import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.session.SessionCache
import com.example.chargeforecast.session.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Кэш сессии на фейковом SharedPreferences: замороженная сводка и приор.
class SessionCacheTest {

    private fun summary(
        avgCcSpeed: Float = 1.2f,
        peakMa: Float? = 2_400f,
        chargeType: ChargeType? = ChargeType.AC
    ) = SessionSummary(
        endedAtMs = 1_000L,
        durationMs = 45 * 60_000L,
        gainedPct = 38,
        avgCcSpeedPctPerMin = avgCcSpeed,
        peakCurrentMa = peakMa,
        chargeType = chargeType
    )

    @Test
    fun summary_roundTrip() {
        val cache = SessionCache(FakeSharedPreferences())
        assertNull(cache.loadSummary())
        cache.saveSummary(summary())
        assertEquals(summary(), cache.loadSummary())
    }

    @Test
    fun summary_withoutPeak_andNullType() {
        val cache = SessionCache(FakeSharedPreferences())
        cache.saveSummary(summary(peakMa = null, chargeType = null))
        val loaded = cache.loadSummary()!!
        assertNull(loaded.peakCurrentMa)
        assertNull(loaded.chargeType)
    }

    @Test
    fun summary_feedsPriorLastSpeed() {
        // Приор следующей сессии — средняя CC-фаза сводки, а не хвост
        // прошлой сессии (отсечение на доводке не занижает приор).
        val cache = SessionCache(FakeSharedPreferences())
        cache.saveSummary(summary(avgCcSpeed = 1.5f))
        assertEquals(1.5f, cache.loadLastSpeed()!!, 1e-6f)
    }

    @Test
    fun summary_rewrittenOnlyByNewSessionEnd() {
        // Сводка перезаписывается только новой сводкой; «текущая» сессия
        // кэш прошлой не трогает — loadLastSpeed стабилен.
        val cache = SessionCache(FakeSharedPreferences())
        cache.saveSummary(summary(avgCcSpeed = 1.5f))
        assertEquals(1.5f, cache.loadSummary()!!.avgCcSpeedPctPerMin, 1e-6f)
        cache.saveSummary(summary(avgCcSpeed = 0.9f))
        assertEquals(0.9f, cache.loadSummary()!!.avgCcSpeedPctPerMin, 1e-6f)
    }

    @Test
    fun capacity_roundTrip() {
        val cache = SessionCache(FakeSharedPreferences())
        cache.saveCapacity(4_000_000L)
        assertEquals(4_000_000L, cache.loadCapacity()!!)
        assertNull(cache.loadSummary())
    }

    @Test
    fun curve_roundTrip_andProfileIsolation() {
        // Крива доводки — отдельный профиль на тип подключения.
        val cache = SessionCache(FakeSharedPreferences())
        assertNull(cache.loadCurve(ChargeType.AC))
        val ac = floatArrayOf(0.6f, 0.45f, 0.35f, 0.25f)
        cache.saveCurve(ChargeType.AC, ac)
        cache.saveCurve(ChargeType.USB, floatArrayOf(0.9f, 0.9f, 0.9f, 0.9f))
        assertTrue(ac.contentEquals(cache.loadCurve(ChargeType.AC)!!))
        assertEquals(0.9f, cache.loadCurve(ChargeType.USB)!![0], 1e-6f)
        assertNull(cache.loadCurve(ChargeType.WIRELESS))
    }
}
