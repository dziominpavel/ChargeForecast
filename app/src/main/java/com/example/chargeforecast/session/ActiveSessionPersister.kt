package com.example.chargeforecast.session

// Троттлинг записи live-снимка активной сессии. Частый тик (1 Гц) не
// должен писать SharedPreferences каждую секунду, но потеря при kill
// ограничена интервалом. Старт сессии, смена уровня и финиш пишутся
// сразу — они важнее плавности.
internal class ActiveSessionPersister(
    private val saveSnapshot: (ActiveSessionSnapshot) -> Unit,
    private val clearSnapshot: () -> Unit,
    private val minIntervalMs: Long = MIN_INTERVAL_MS
) {
    constructor(
        runtimeStore: SessionRuntimeStore?,
        minIntervalMs: Long = MIN_INTERVAL_MS
    ) : this(
        saveSnapshot = { runtimeStore?.saveActiveSession(it) },
        clearSnapshot = { runtimeStore?.clearActiveSession() },
        minIntervalMs = minIntervalMs
    )

    private var lastSessionId: String? = null
    private var lastLevelPct: Int? = null
    private var lastWrittenAtMs: Long? = null

    fun persist(snapshot: ActiveSessionSnapshot?) {
        if (snapshot == null) {
            if (lastSessionId != null) {
                clearSnapshot()
                lastSessionId = null
                lastLevelPct = null
                lastWrittenAtMs = null
            }
            return
        }
        val newSession = lastSessionId != snapshot.id
        val levelChanged = lastLevelPct != snapshot.lastLevelPct
        val sinceLastWrite = lastWrittenAtMs?.let {
            snapshot.lastUpdatedAtMs - it
        }
        val due = sinceLastWrite == null || sinceLastWrite >= minIntervalMs
        if (!newSession && !levelChanged && !due) return
        saveSnapshot(snapshot)
        lastSessionId = snapshot.id
        lastLevelPct = snapshot.lastLevelPct
        lastWrittenAtMs = snapshot.lastUpdatedAtMs
    }

    companion object {
        // Верхняя граница из design D5: не чаще раза в 30 секунд.
        const val MIN_INTERVAL_MS = 30_000L
    }
}
