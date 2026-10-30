package com.example.chargeforecast

import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.DischargeSample
import com.example.chargeforecast.forecast.DischargeState
import com.example.chargeforecast.session.ActiveSessionSnapshot
import com.example.chargeforecast.session.BootIdentity
import com.example.chargeforecast.session.SessionRuntimeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Машинное состояние в отдельном session_runtime prefs: переживает
// смерть процесса, но не переносится через backup.
class SessionRuntimeStoreTest {

    @Test
    fun bootIdentity_roundTrip() {
        val store = SessionRuntimeStore(FakeSharedPreferences())
        assertNull(store.loadBootIdentity())
        val identity = BootIdentity(
            bootCount = 42,
            wallClockMs = 1_700_000_000_000L,
            elapsedRealtimeMs = 900_000L
        )
        store.saveBootIdentity(identity)
        assertEquals(identity, store.loadBootIdentity())
    }

    @Test
    fun bootIdentity_withUnknownCount_roundTrip() {
        val store = SessionRuntimeStore(FakeSharedPreferences())
        store.saveBootIdentity(BootIdentity(null, 1L, 2L))
        assertNull(store.loadBootIdentity()!!.bootCount)
    }

    @Test
    fun capacity_roundTrip() {
        val store = SessionRuntimeStore(FakeSharedPreferences())
        assertNull(store.loadCapacity())
        store.saveCapacity(4_000_000L)
        assertEquals(4_000_000L, store.loadCapacity()!!)
        store.saveCapacity(0L)
        assertEquals(4_000_000L, store.loadCapacity()!!)
    }

    @Test
    fun discharge_roundTrip_withBootCount() {
        val store = SessionRuntimeStore(FakeSharedPreferences())
        assertNull(store.loadDischarge())
        val state = DischargeState(
            samples = listOf(
                DischargeSample(atMs = 8_100_000L, durationMs = 900_000L, dischargedUah = 100_000L),
                DischargeSample(atMs = 9_000_000L, durationMs = 900_000L, dischargedUah = 95_000L)
            ),
            lastSampleMs = 9_000_000L,
            lastCounterUah = 1_840_000L,
            bootCount = 11
        )
        store.saveDischarge(state)
        assertEquals(state, store.loadDischarge())
    }

    @Test
    fun discharge_survivesProcessKill() {
        // OEM-оболочка убила процесс: prefs — тот же файл, экземпляры новые.
        val prefs = FakeSharedPreferences()
        SessionRuntimeStore(prefs).saveDischarge(
            DischargeState(
                samples = listOf(
                    DischargeSample(
                        atMs = 1_500_000L,
                        durationMs = 1_500_000L,
                        dischargedUah = 120_000L
                    )
                ),
                lastSampleMs = 1_500_000L,
                lastCounterUah = 2_000_000L,
                bootCount = 3
            )
        )
        val restored = SessionRuntimeStore(prefs).loadDischarge()!!
        assertEquals(1_500_000L, restored.lastSampleMs)
        assertEquals(2_000_000L, restored.lastCounterUah)
        assertEquals(3, restored.bootCount)
        assertEquals(1, restored.samples.size)
        assertEquals(120_000L, restored.samples.first().dischargedUah)
    }

    @Test
    fun discharge_emptyWindowAndNullAnchors_areNotConfusedWithValues() {
        val store = SessionRuntimeStore(FakeSharedPreferences())
        store.saveDischarge(DischargeState(emptyList(), null, null, bootCount = null))
        val loaded = store.loadDischarge()!!
        assertEquals(emptyList<DischargeSample>(), loaded.samples)
        assertNull(loaded.lastSampleMs)
        assertNull(loaded.lastCounterUah)
        assertNull(loaded.bootCount)
    }

    @Test
    fun discharge_oldScalarKeysIgnored() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putFloat("discharge_smoothed_ua", 412_500f)
            .putLong("discharge_tracked_ms", 7_200_000L)
            .putLong("discharge_last_sample_ms", 1_500_000L)
            .putLong("discharge_last_counter_uah", 2_000_000L)
            .apply()
        assertNull(SessionRuntimeStore(prefs).loadDischarge())
    }

    @Test
    fun discharge_corruptSampleStringDecodesToEmpty() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("discharge_samples_v2", "broken:entry;12:xx:34").apply()
        val loaded = SessionRuntimeStore(prefs).loadDischarge()!!
        assertEquals(emptyList<DischargeSample>(), loaded.samples)
    }

    @Test
    fun activeSession_roundTrip() {
        val store = SessionRuntimeStore(FakeSharedPreferences())
        assertNull(store.loadActiveSession())
        val snapshot = ActiveSessionSnapshot(
            id = "session-1",
            bootCount = 5,
            startedAtMs = 1_000_000L,
            startLevelPct = 42,
            chargeType = ChargeType.AC,
            ccSpeedSum = 12.5f,
            ccSampleCount = 10,
            peakCurrentUa = 2_400_000L,
            lastLevelPct = 55,
            lastUpdatedAtMs = 2_800_000L
        )
        store.saveActiveSession(snapshot)
        assertEquals(snapshot, store.loadActiveSession())
    }

    @Test
    fun activeSession_nullableFields_roundTrip() {
        val store = SessionRuntimeStore(FakeSharedPreferences())
        val snapshot = ActiveSessionSnapshot(
            id = "session-2",
            bootCount = null,
            startedAtMs = 0L,
            startLevelPct = 10,
            chargeType = ChargeType.USB,
            ccSpeedSum = 0f,
            ccSampleCount = 0,
            peakCurrentUa = null,
            lastLevelPct = 10,
            lastUpdatedAtMs = 0L
        )
        store.saveActiveSession(snapshot)
        assertEquals(snapshot, store.loadActiveSession())
    }

    @Test
    fun activeSession_clearRemovesSnapshot() {
        val prefs = FakeSharedPreferences()
        val store = SessionRuntimeStore(prefs)
        store.saveActiveSession(
            ActiveSessionSnapshot(
                id = "session-3",
                bootCount = 1,
                startedAtMs = 100L,
                startLevelPct = 20,
                chargeType = ChargeType.AC,
                ccSpeedSum = 1f,
                ccSampleCount = 1,
                peakCurrentUa = null,
                lastLevelPct = 21,
                lastUpdatedAtMs = 200L
            )
        )
        store.clearActiveSession()
        assertNull(store.loadActiveSession())
        assertTrue(prefs.getAll().keys.none { it.startsWith("active_session_") })
    }

    @Test
    fun corruptActiveSession_isIgnored() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString("active_session_id", "broken")
            .putLong("active_session_started_at_ms", 1_000L)
            .putInt("active_session_start_level_pct", 50)
            .putInt("active_session_last_level_pct", 49)
            .putLong("active_session_last_updated_at_ms", 500L)
            .putString("active_session_charge_type", "AC")
            .apply()
        assertNull(SessionRuntimeStore(prefs).loadActiveSession())
    }

    @Test
    fun runtimeStore_keepsNoPortableCacheKeys() {
        // Сводка/история/кривая живут в charge_session_cache; runtime-файл
        // не должен их дублировать.
        val prefs = FakeSharedPreferences()
        val store = SessionRuntimeStore(prefs)
        store.saveCapacity(4_000_000L)
        store.saveDischarge(DischargeState(emptyList(), null, null, bootCount = 1))
        store.saveBootIdentity(BootIdentity(1, 2L, 3L))
        val keys = prefs.getAll().keys
        assertFalse(keys.any { it.startsWith("summary_") })
        assertFalse(keys.any { it.startsWith("hist_") })
        assertFalse(keys.any { it.startsWith("curve_") })
    }
}
