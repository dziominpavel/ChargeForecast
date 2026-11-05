package com.example.chargeforecast

import com.example.chargeforecast.diag.DiagEvent
import com.example.chargeforecast.diag.DiagEventKind
import com.example.chargeforecast.diag.DiagLog
import com.example.chargeforecast.diag.DiagTick
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Писатель журнала: троттлинг тика, отсутствие записи без кабеля,
// ротация и неблокирующая постановка в очередь.
class DiagLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dir: File

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dir = tmp.newFolder("diag")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun sampleTick(atMs: Long, plugged: Boolean = true) = DiagTick(
        atMs = atMs,
        levelPct = 81,
        plugged = plugged,
        charging = true,
        full = false,
        chargeType = "AC",
        currentMa = 4f,
        counterUah = 3_268_000L,
        capacityUah = 4_035_200L,
        s1ShortPctPerMin = 2.94f,
        s1LongPctPerMin = null,
        s2PctPerMin = null,
        s3PctPerMin = 3.01f,
        fusedPctPerMin = 3.01f,
        baseRatePctPerMin = 5.47f,
        taperFactor = 0.55f,
        rawRemainingSec = 676L,
        shownRemainingSec = 671L,
        accuracy = "PRECISE",
        refining = false
    )

    private fun awaitLines(file: File, atLeast: Int, timeoutMs: Long = 5_000L): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.exists()) {
                val lines = file.readLines()
                if (lines.size >= atLeast) return lines
            }
            Thread.sleep(20)
        }
        return if (file.exists()) file.readLines() else emptyList()
    }

    @Test
    fun tickThrottledToOnePerSecond() {
        val log = DiagLog(dir, scope)

        log.recordTick(sampleTick(1_000L))
        log.recordTick(sampleTick(1_500L))
        log.recordTick(sampleTick(1_999L))
        log.recordTick(sampleTick(2_000L))

        val lines = awaitLines(log.file(), 2)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("t=1000;"))
        assertTrue(lines[1].startsWith("t=2000;"))
    }

    @Test
    fun noTicksWithoutCable() {
        val log = DiagLog(dir, scope)

        repeat(5) { log.recordTick(sampleTick(it * 1_000L, plugged = false)) }
        Thread.sleep(300L)

        assertTrue("файла быть не должно", !log.file().exists() || log.file().length() == 0L)
        assertEquals(0, log.ring().size)
    }

    @Test
    fun enqueueDoesNotBlockCaller() {
        val log = DiagLog(dir, scope)
        val started = System.nanoTime()

        repeat(1_000) { log.recordTick(sampleTick(it * 1_000L)) }

        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("постановка в очередь заняла " + elapsedMs + " мс", elapsedMs < 3_000L)
        assertEquals(200, log.ring().size)
    }

    @Test
    fun rotationKeepsTwoBoundedFiles() {
        val log = DiagLog(dir, scope, maxBytes = 300L)
        val event = DiagEvent(DiagEventKind.ACCURACY, "from=APPROXIMATE;to=PRECISE")

        repeat(60) { log.record(event) }

        val previous = File(dir, DiagLog.PREVIOUS_FILE_NAME)
        val deadline = System.currentTimeMillis() + 5_000L
        while (!previous.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertTrue("ротация не сработала", previous.exists())
        assertTrue("старый файл разросся", previous.length() <= 600L)
        assertTrue("текущий файл разросся", log.file().length() <= 600L)
        assertTrue("свежие строки не в текущем файле", log.file().length() > 0L)
    }
}