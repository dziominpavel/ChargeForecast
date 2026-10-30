package com.example.chargeforecast

import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.session.ActiveSessionPersister
import com.example.chargeforecast.session.ActiveSessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

// Троттлинг live-снимка: старт/уровень/финиш пишутся сразу, рутинные
// тики — не чаще раза в 30 секунд.
class ActiveSessionPersisterTest {

    private fun snapshot(
        tMs: Long,
        level: Int = 50,
        id: String = "session-1"
    ) = ActiveSessionSnapshot(
        id = id,
        bootCount = 1,
        startedAtMs = 0L,
        startLevelPct = 50,
        chargeType = ChargeType.AC,
        ccSpeedSum = 1f,
        ccSampleCount = 1,
        peakCurrentUa = null,
        lastLevelPct = level,
        lastUpdatedAtMs = tMs
    )

    @Test
    fun sixtySecondsOfTicks_writesAtMostThreeSnapshots() {
        val writes = mutableListOf<ActiveSessionSnapshot>()
        val persister = ActiveSessionPersister(
            saveSnapshot = { writes += it },
            clearSnapshot = {},
            minIntervalMs = 30_000L
        )
        for (t in 0L..60_000L step 1_000L) {
            persister.persist(snapshot(t))
        }
        assertEquals(3, writes.size)
        assertEquals(listOf(0L, 30_000L, 60_000L), writes.map { it.lastUpdatedAtMs })
    }

    @Test
    fun levelBoundary_writesImmediately() {
        val writes = mutableListOf<ActiveSessionSnapshot>()
        val persister = ActiveSessionPersister(
            saveSnapshot = { writes += it },
            clearSnapshot = {},
            minIntervalMs = 30_000L
        )
        persister.persist(snapshot(0L, level = 50))
        persister.persist(snapshot(5_000L, level = 51))
        assertEquals(2, writes.size)
        assertEquals(5_000L, writes.last().lastUpdatedAtMs)
    }

    @Test
    fun newSession_writesImmediately() {
        val writes = mutableListOf<ActiveSessionSnapshot>()
        val persister = ActiveSessionPersister(
            saveSnapshot = { writes += it },
            clearSnapshot = {},
            minIntervalMs = 30_000L
        )
        persister.persist(snapshot(0L, id = "session-1"))
        persister.persist(snapshot(1_000L, id = "session-2"))
        assertEquals(2, writes.size)
        assertEquals("session-2", writes.last().id)
    }

    @Test
    fun noSession_clearsPersistedSnapshotOnce() {
        var clears = 0
        val persister = ActiveSessionPersister(
            saveSnapshot = {},
            clearSnapshot = { clears++ },
            minIntervalMs = 30_000L
        )
        persister.persist(snapshot(0L))
        persister.persist(null)
        persister.persist(null)
        assertEquals(1, clears)
    }
}
