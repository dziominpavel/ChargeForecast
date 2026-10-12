package com.example.chargeforecast

import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.DischargeSample
import com.example.chargeforecast.forecast.DischargeState
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
    fun discharge_roundTrip() {
        // Кольцо интервалов окна переживает запись и чтение целиком —
        // оценка продолжается от прежней базы, а не с нуля.
        val cache = SessionCache(FakeSharedPreferences())
        assertNull(cache.loadDischarge())
        val state = DischargeState(
            samples = listOf(
                DischargeSample(atMs = 8_100_000L, durationMs = 900_000L, dischargedUah = 100_000L),
                DischargeSample(atMs = 9_000_000L, durationMs = 900_000L, dischargedUah = 95_000L)
            ),
            lastSampleMs = 9_000_000L,
            lastCounterUah = 1_840_000L
        )
        cache.saveDischarge(state)
        assertEquals(state, cache.loadDischarge())
    }

    @Test
    fun discharge_survivesProcessKill() {
        // OEM-оболочка убила процесс: SharedPreferences — тот же файл, но
        // экземпляры кэша новые. Накопление восстановлено, база прежняя.
        val prefs = FakeSharedPreferences()
        SessionCache(prefs).saveDischarge(
            DischargeState(
                samples = listOf(
                    DischargeSample(atMs = 1_500_000L, durationMs = 1_500_000L, dischargedUah = 120_000L)
                ),
                lastSampleMs = 1_500_000L,
                lastCounterUah = 2_000_000L
            )
        )
        val restored = SessionCache(prefs).loadDischarge()!!
        assertEquals(1_500_000L, restored.lastSampleMs)
        assertEquals(2_000_000L, restored.lastCounterUah)
        assertEquals(1, restored.samples.size)
        assertEquals(120_000L, restored.samples.first().dischargedUah)
    }

    @Test
    fun discharge_emptyWindowAndNullAnchors_areNotConfusedWithValues() {
        // Пустое окно и null-якоря — не нули: null и 0 разное.
        val cache = SessionCache(FakeSharedPreferences())
        cache.saveDischarge(DischargeState(emptyList(), null, null))
        val loaded = cache.loadDischarge()!!
        assertEquals(emptyList<DischargeSample>(), loaded.samples)
        assertNull(loaded.lastSampleMs)
        assertNull(loaded.lastCounterUah)
    }

    @Test
    fun discharge_oldScalarKeysIgnored() {
        // Прежние четыре скаляра накопителя не читаются: окно начинается
        // пустым, а не с устаревшего сглаженного тока (design D9).
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putFloat("discharge_smoothed_ua", 412_500f)
            .putLong("discharge_tracked_ms", 7_200_000L)
            .putLong("discharge_last_sample_ms", 1_500_000L)
            .putLong("discharge_last_counter_uah", 2_000_000L)
            .apply()
        assertNull(SessionCache(prefs).loadDischarge())
    }

    @Test
    fun discharge_corruptSampleStringDecodesToEmpty() {
        // Повреждённая строка не роняет приложение и не даёт ложных
        // интервалов: интервалы просто отсутствуют.
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("discharge_samples_v2", "broken:entry;12:xx:34").apply()
        val loaded = SessionCache(prefs).loadDischarge()!!
        assertEquals(emptyList<DischargeSample>(), loaded.samples)
    }

    @Test
    fun discharge_notTouchedByOtherCacheWrites() {
        // Сводка, ёмкость и кривые не затирают накопление расхода.
        val cache = SessionCache(FakeSharedPreferences())
        val state = DischargeState(
            samples = listOf(
                DischargeSample(atMs = 1_500_000L, durationMs = 1_500_000L, dischargedUah = 120_000L)
            ),
            lastSampleMs = 1_500_000L,
            lastCounterUah = 2_000_000L
        )
        cache.saveDischarge(state)
        cache.saveSummary(summary())
        cache.saveCapacity(4_000_000L)
        cache.appendHistory(ChargeType.AC, 1.2f)
        assertEquals(state, cache.loadDischarge())
    }
}
