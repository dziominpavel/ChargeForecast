package com.example.chargeforecast.diag

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

// Писатель журнала: единственная очередь и единственный писатель.
// Строку кладёт вызывающий (trySend, без ожидания), пишет отдельная
// корутина на IO. Переполнение теряет СТАРЫЕ строки, а не свежие:
// при разборе сбоя важнее то, что было перед ним.
// Запись не должна влиять на прогноз — любая ошибка файла гасится.
class DiagLog(
    private val dir: File,
    private val scope: CoroutineScope,
    private val writerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxBytes: Long = MAX_BYTES,
    private val minTickIntervalMs: Long = MIN_TICK_INTERVAL_MS,
    ringCapacity: Int = DiagRing.CAPACITY
) {

    private val ring = DiagRing(ringCapacity)
    private val queue = Channel<String>(QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)
    private val logFile = File(dir, FILE_NAME)

    private var lastTickAtMs: Long? = null

    init {
        scope.launch(writerDispatcher) {
            for (line in queue) append(line)
        }
    }

    // Тик пишется только на зарядке и не чаще раза в секунду: журнал не
    // должен расти от фонового ожидания и от повторных эмиссий состояния.
    fun recordTick(tick: DiagTick) {
        if (!tick.plugged) return
        val previous = lastTickAtMs
        if (previous != null && tick.atMs - previous < minTickIntervalMs) return
        lastTickAtMs = tick.atMs
        emit(DiagText.tick(tick))
    }

    fun record(event: DiagEvent) {
        emit(DiagText.event(clockMs(), event))
    }

    fun ring(): List<String> = ring.lines()

    fun file(): File = logFile

    fun close() {
        queue.close()
    }

    private fun emit(line: String) {
        ring.add(line)
        queue.trySend(line)
    }

    private fun append(line: String) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            rotateIfNeeded()
            logFile.appendText(line + "\n")
        }
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < maxBytes) return
        val previous = File(dir, PREVIOUS_FILE_NAME)
        if (previous.exists()) previous.delete()
        logFile.renameTo(previous)
    }

    companion object {
        // Очередь на ~4 минуты тика: больше не нужно, лишнее теряется.
        const val QUEUE_CAPACITY = 256
        // Два файла по мегабайту — примерно сутки зарядки.
        const val MAX_BYTES = 1_000_000L
        const val MIN_TICK_INTERVAL_MS = 1_000L
        const val DIR_NAME = "diag"
        const val FILE_NAME = "session.log"
        const val PREVIOUS_FILE_NAME = "session.log.old"
    }
}