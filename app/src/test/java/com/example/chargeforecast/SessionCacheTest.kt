package com.example.chargeforecast

import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.session.ActiveSessionSnapshot
import com.example.chargeforecast.session.SessionCache
import com.example.chargeforecast.session.SessionRuntimeStore
import com.example.chargeforecast.session.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun summary_withSessionId_roundTrip() {
        val cache = SessionCache(FakeSharedPreferences())
        cache.saveSummary(summary().copy(id = "session-42"))
        assertEquals("session-42", cache.loadSummary()!!.id)
    }

    @Test
    fun summary_withoutId_loadsAsLegacy() {
        // Старая запись не содержит summary_id — это не ошибка.
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putLong("summary_ended_ms", 1_000L)
            .putLong("summary_duration_ms", 2_000L)
            .putInt("summary_gained_pct", 10)
            .putFloat("summary_avg_cc_speed", 1.2f)
            .putString("summary_charge_type", ChargeType.AC.name)
            .apply()
        assertNull(SessionCache(prefs).loadSummary()!!.id)
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
    fun summary_feedsHistoryPrior() {
        // Опора следующей сессии — средняя CC-фаза сводки через историю
        // (ленивая миграция), а не хвост прошлой сессии: отсечение на
        // доводке не занижает следующую сессию.
        val cache = SessionCache(FakeSharedPreferences())
        cache.saveSummary(summary(avgCcSpeed = 1.5f))
        assertEquals(1.5f, cache.loadHistoryMedian(ChargeType.AC)!!, 1e-6f)
    }

    @Test
    fun summary_rewrittenOnlyByNewSessionEnd() {
        // Сводка перезаписывается только новой сводкой; «текущая» сессия
        // кэш прошлой не трогает — история стабильна.
        val cache = SessionCache(FakeSharedPreferences())
        cache.saveSummary(summary(avgCcSpeed = 1.5f))
        assertEquals(1.5f, cache.loadSummary()!!.avgCcSpeedPctPerMin, 1e-6f)
        cache.saveSummary(summary(avgCcSpeed = 0.9f))
        assertEquals(0.9f, cache.loadSummary()!!.avgCcSpeedPctPerMin, 1e-6f)
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

    @Test
    fun history_emptyBufferReturnsNull() {
        // Пустая история — null, без ошибок (первая сессия устройства).
        val cache = SessionCache(FakeSharedPreferences())
        assertNull(cache.loadHistoryMedian(ChargeType.AC))
    }

    @Test
    fun history_sixthSessionEvictsOldest() {
        // Кольцевой буфер: шестая сессия вытесняет первую (N=5).
        val cache = SessionCache(FakeSharedPreferences())
        listOf(1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f).forEach {
            cache.appendHistory(ChargeType.AC, it)
        }
        // Буфер: 1.1 1.2 1.3 1.4 1.5 — медиана 1.3 (1.0 вытеснен).
        assertEquals(1.3f, cache.loadHistoryMedian(ChargeType.AC)!!, 1e-6f)
    }

    @Test
    fun history_medianRobustToOutlier() {
        // Медиана вместо среднего: одна чудная сессия 0.5 не двигает
        // оценку с 2.0 (среднее дало бы 1.7).
        val cache = SessionCache(FakeSharedPreferences())
        listOf(2.0f, 2.0f, 2.0f, 2.0f, 0.5f).forEach {
            cache.appendHistory(ChargeType.AC, it)
        }
        assertEquals(2.0f, cache.loadHistoryMedian(ChargeType.AC)!!, 1e-6f)
    }

    @Test
    fun history_evenCountAveragesMiddlePair() {
        // Чётный размер буфера — медиана среднее двух центральных.
        val cache = SessionCache(FakeSharedPreferences())
        cache.appendHistory(ChargeType.AC, 1.0f)
        cache.appendHistory(ChargeType.AC, 3.0f)
        assertEquals(2.0f, cache.loadHistoryMedian(ChargeType.AC)!!, 1e-6f)
    }

    @Test
    fun history_isolatedByChargeType() {
        // История по типу подключения: AC не видит USB-сессии.
        val cache = SessionCache(FakeSharedPreferences())
        cache.appendHistory(ChargeType.AC, 2.0f)
        assertNull(cache.loadHistoryMedian(ChargeType.USB))
        assertEquals(2.0f, cache.loadHistoryMedian(ChargeType.AC)!!, 1e-6f)
    }

    @Test
    fun history_lazyMigrationSeedsFromSummary() {
        // Ленивая миграция (design D5): буфер пуст, сводка от старой
        // версии есть — первое чтение засеивает буфер её скоростью.
        val cache = SessionCache(FakeSharedPreferences())
        cache.saveSummary(summary(avgCcSpeed = 1.4f))
        assertEquals(1.4f, cache.loadHistoryMedian(ChargeType.AC)!!, 1e-6f)
        // Засеянный буфер живёт своей жизнью: новая сессия добавляется.
        cache.appendHistory(ChargeType.AC, 1.8f)
        assertEquals(1.6f, cache.loadHistoryMedian(ChargeType.AC)!!, 1e-6f)
    }

    @Test
    fun history_migrationSkipsSummaryOfOtherType() {
        // Сводка от другого профиля не засевает буфер текущего типа.
        val cache = SessionCache(FakeSharedPreferences())
        cache.saveSummary(summary(avgCcSpeed = 1.4f, chargeType = ChargeType.USB))
        assertNull(cache.loadHistoryMedian(ChargeType.AC))
    }

    @Test
    fun history_nonPositiveAppendsIgnored() {
        val cache = SessionCache(FakeSharedPreferences())
        cache.appendHistory(ChargeType.AC, 0f)
        cache.appendHistory(ChargeType.AC, -1f)
        assertNull(cache.loadHistoryMedian(ChargeType.AC))
    }

    @Test
    fun finalizeActiveSession_writesSummaryOnce_withIdAndHistory() {
        val prefs = FakeSharedPreferences()
        val runtimePrefs = FakeSharedPreferences()
        val cache = SessionCache(prefs)
        val runtime = SessionRuntimeStore(runtimePrefs)
        val snapshot = activeSnapshot(
            id = "session-1",
            startLevel = 40,
            lastLevel = 55,
            ccSpeedSum = 15f,
            ccSampleCount = 10
        )
        runtime.saveActiveSession(snapshot)

        val summary = cache.finalizeActiveSession(snapshot, runtime)!!

        assertEquals("session-1", summary.id)
        assertEquals(15, summary.gainedPct)
        assertEquals(1.5f, summary.avgCcSpeedPctPerMin, 1e-6f)
        assertEquals(summary, cache.loadSummary())
        assertEquals(1.5f, cache.loadHistoryMedian(ChargeType.AC)!!, 1e-6f)
        assertNull(runtime.loadActiveSession())
    }

    @Test
    fun finalizeActiveSession_repeatDoesNotDuplicateHistory() {
        val cache = SessionCache(FakeSharedPreferences())
        val runtime = SessionRuntimeStore(FakeSharedPreferences())
        listOf(1.0f, 1.1f, 1.2f, 1.3f, 1.4f).forEach {
            cache.appendHistory(ChargeType.AC, it)
        }
        val snapshot = activeSnapshot(id = "session-1", ccSpeedSum = 20f, ccSampleCount = 10)
        runtime.saveActiveSession(snapshot)

        cache.finalizeActiveSession(snapshot, runtime)
        // Буфер [1.1, 1.2, 1.3, 1.4, 2.0] — медиана 1.3.
        assertEquals(1.3f, cache.loadHistoryMedian(ChargeType.AC)!!, 1e-6f)

        runtime.saveActiveSession(snapshot)
        cache.finalizeActiveSession(snapshot, runtime)
        // Повторная финализация не добавляет 2.0 второй раз:
        // иначе буфер стал бы [1.2, 1.3, 1.4, 2.0, 2.0] с медианой 1.4.
        assertEquals(1.3f, cache.loadHistoryMedian(ChargeType.AC)!!, 1e-6f)
    }

    @Test
    fun finalizeActiveSession_belowGate_clearsSnapshotWithoutSummary() {
        val cache = SessionCache(FakeSharedPreferences())
        val runtime = SessionRuntimeStore(FakeSharedPreferences())
        // Набрано всего 1% — порог сводки не пройден.
        val snapshot = activeSnapshot(id = "short", startLevel = 50, lastLevel = 51)
        runtime.saveActiveSession(snapshot)

        assertNull(cache.finalizeActiveSession(snapshot, runtime))
        assertNull(cache.loadSummary())
        assertNull(runtime.loadActiveSession())
    }

    @Test
    fun portableCache_keepsNoRuntimeKeys() {
        // session_runtime.xml исключён из backup; в charge_session_cache
        // не должно появиться машинных ключей.
        val prefs = FakeSharedPreferences()
        val cache = SessionCache(prefs)
        val runtime = SessionRuntimeStore(FakeSharedPreferences())
        val snapshot = activeSnapshot(id = "session-runtime-keys")
        runtime.saveActiveSession(snapshot)
        cache.finalizeActiveSession(snapshot, runtime)
        cache.saveCurve(ChargeType.AC, floatArrayOf(1f, 1f, 1f, 1f))

        val keys = prefs.getAll().keys
        assertFalse(keys.any { it.startsWith("active_session_") })
        assertFalse(keys.any { it.startsWith("discharge_") })
        assertFalse(keys.contains("capacity_uah"))
        assertTrue(keys.contains("summary_avg_cc_speed"))
    }

    private fun activeSnapshot(
        id: String,
        startLevel: Int = 40,
        lastLevel: Int = 55,
        ccSpeedSum: Float = 15f,
        ccSampleCount: Int = 10
    ) = ActiveSessionSnapshot(
        id = id,
        bootCount = 1,
        startedAtMs = 1_000L,
        startLevelPct = startLevel,
        chargeType = ChargeType.AC,
        ccSpeedSum = ccSpeedSum,
        ccSampleCount = ccSampleCount,
        peakCurrentUa = 2_400_000L,
        lastLevelPct = lastLevel,
        lastUpdatedAtMs = 1_801_000L
    )
}
