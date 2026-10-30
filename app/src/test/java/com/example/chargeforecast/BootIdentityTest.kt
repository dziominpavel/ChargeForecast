package com.example.chargeforecast

import com.example.chargeforecast.session.BootIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Сравнение загрузок: номер BOOT_COUNT сильнее эвристики по
// elapsedRealtime; без номера остаётся консервативная проверка.
class BootIdentityTest {

    @Test
    fun sameBootCount_isSameBoot() {
        assertTrue(
            BootIdentity.sameBoot(
                savedBootCount = 12,
                savedElapsedRealtimeMs = 900_000_000L,
                currentBootCount = 12,
                currentElapsedRealtimeMs = 10_000L
            )
        )
    }

    @Test
    fun differentBootCount_isNewBoot_evenIfElapsedLooksPlausible() {
        assertFalse(
            BootIdentity.sameBoot(
                savedBootCount = 12,
                savedElapsedRealtimeMs = 10_000L,
                currentBootCount = 13,
                currentElapsedRealtimeMs = 20_000L
            )
        )
    }

    @Test
    fun unknownBootCount_usesMonotonicElapsedHeuristic() {
        // Сохранённая метка не больше текущей — похоже на ту же загрузку.
        assertTrue(
            BootIdentity.sameBoot(
                savedBootCount = null,
                savedElapsedRealtimeMs = 10_000L,
                currentBootCount = null,
                currentElapsedRealtimeMs = 20_000L
            )
        )
        // Сохранённая метка больше текущей — шкала начиналась заново.
        assertFalse(
            BootIdentity.sameBoot(
                savedBootCount = null,
                savedElapsedRealtimeMs = 20_000L,
                currentBootCount = null,
                currentElapsedRealtimeMs = 10_000L
            )
        )
    }

    @Test
    fun legacyStateWithoutBootCount_isNotGluedToKnownBoot() {
        // Старая версия не сохраняла номер загрузки: при известном текущем
        // номере считаем состояние чужим и начинаем заново.
        assertFalse(
            BootIdentity.sameBoot(
                savedBootCount = null,
                savedElapsedRealtimeMs = 10_000L,
                currentBootCount = 7,
                currentElapsedRealtimeMs = 20_000L
            )
        )
    }

    @Test
    fun savedBootCountWithoutCurrentValue_fallsBackToElapsed() {
        assertTrue(
            BootIdentity.sameBoot(
                savedBootCount = 7,
                savedElapsedRealtimeMs = 10_000L,
                currentBootCount = null,
                currentElapsedRealtimeMs = 20_000L
            )
        )
        assertFalse(
            BootIdentity.sameBoot(
                savedBootCount = 7,
                savedElapsedRealtimeMs = 20_000L,
                currentBootCount = null,
                currentElapsedRealtimeMs = 10_000L
            )
        )
    }

    @Test
    fun missingElapsedAnchor_isNotEnoughToResume() {
        assertFalse(
            BootIdentity.sameBoot(
                savedBootCount = null,
                savedElapsedRealtimeMs = null,
                currentBootCount = null,
                currentElapsedRealtimeMs = 10_000L
            )
        )
    }
}
