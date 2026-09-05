package com.example.chargeforecast

import com.example.chargeforecast.battery.BatterySnapshot
import com.example.chargeforecast.battery.ChargeType
import com.example.chargeforecast.forecast.ForecastAccuracy
import com.example.chargeforecast.session.SessionCache
import com.example.chargeforecast.session.SessionEngine
import com.example.chargeforecast.session.SessionState
import com.example.chargeforecast.session.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Жизненный цикл сессии и секундный конвейер (фаза 1).
class SessionEngineTest {

    private val capacity = 4_000_000L

    private fun snap(
        tMs: Long,
        level: Int,
        currentMa: Float? = 2_400f,
        // Счётчик — заряд в батарее, непрерывный (как у реального FuelGauge):
        // уровень-компонента + плавный ход внутри минуты. Согласован с
        // уровнем: +40k мкА·ч/мин = 1%/мин при ёмкости 4 млн.
        counterUah: Long? = capacity * level / 100L +
            (tMs % 60_000L) * 40_000L / 60_000L,
        charging: Boolean = true,
        plugged: Boolean = true,
        full: Boolean = false
    ) = BatterySnapshot(
        levelPct = level,
        isPlugged = plugged,
        isCharging = charging,
        isFull = full,
        chargeType = ChargeType.AC,
        temperatureC = null,
        voltageV = null,
        currentMa = currentMa,
        chargeCounterUah = counterUah,
        timestampMs = tMs
    )

    @Test
    fun plugLifecycle_ageFromCable_notFromCharging() {
        // Рампа Huawei P60 Pro: CHARGING подтверждается с запаздыванием —
        // секунда ноль всё равно от кабеля.
        val engine = SessionEngine()
        engine.update(snap(0, 40, charging = false), 0)
        val at = engine.update(snap(35_000, 40, charging = false), 35_000)
        assertEquals(35_000L, at.sessionAgeMs)
    }

    @Test
    fun firstSeconds_showForecastImmediately() {
        // Цифры с первой валидной опоры (обычно 1-3 сек сессии), без
        // ожидания 20-секундной завесы: приор/ток/счётчик дают опору.
        val engine = SessionEngine()
        // Ток живой с первого сэмпла — цифры есть уже в секунду ноль
        // («примерно», интеграл в секундах).
        val early = engine.update(snap(0, 50), 0)
        assertEquals(ForecastAccuracy.APPROXIMATE, early.forecast.accuracy)
        assertEquals(5595L, early.forecast.remainingSec)
        val ready = engine.update(snap(5_000, 50), 5_000)
        assertEquals(ForecastAccuracy.APPROXIMATE, ready.forecast.accuracy)
        // Интеграл с ~49.5% остатка → ~92.9 мин = ~5572 с.
        assertTrue(
            "ожидали ~5572, получили ${ready.forecast.remainingSec}",
            ready.forecast.remainingSec!! in 5500L..5650L
        )
    }

    @Test
    fun steadySession_displayedNonIncreasing_thenPrecise() {
        val engine = SessionEngine()
        var prev: Long? = null
        var t = 0L
        while (t <= 12 * 60_000L) {
            val level = (50 + t / 60_000L).toInt().coerceAtMost(99)
            val st = engine.update(snap(t, level), t)
            val displayed = st.forecast.remainingSec
            val previous = prev
            if (displayed != null && previous != null && t > 90_000L) {
                assertTrue(
                    "показанный остаток возрос: $previous → $displayed на ${t / 1000} сек",
                    displayed <= previous
                )
            }
            if (displayed != null) prev = displayed
            t += 1_000L
        }
        val last = engine.update(
            snap(12 * 60_000L, 62), 12 * 60_000L
        )
        assertEquals(ForecastAccuracy.PRECISE, last.forecast.accuracy)
        // Честный интеграл: 18 линейных (62→80) + 63.3 корзин ≈ 81 мин
        // = ~4875 с; погрешность МНК на ступенчатом счётчике — минуты.
        // Фильтр ведёт показанное к истине.
        assertTrue(
            "ожидали ~4875, получили ${last.forecast.remainingSec}",
            last.forecast.remainingSec!! in 4500L..5100L
        )
    }

    @Test
    fun counterMissing_currentOnlyWorks() {
        // Счётчика нет: S2 по току через sysfs-ёмкость, уровень — fallback.
        val engine = SessionEngine(sysfsCapacityUah = { capacity })
        engine.update(snap(0, 50, counterUah = null), 0)
        val st = engine.update(snap(30_000, 50, counterUah = null), 30_000)
        assertEquals(
            "ожидали ~1.0 по току",
            1.0f,
            st.forecast.speedPctPerMin!!,
            0.05f
        )
        // Интеграл с уровня 50: 30 линейных + хвост корзин = 93.3 мин
        // = 5595 с.
        assertTrue(
            "ожидали ~5595, получили ${st.forecast.remainingSec}",
            st.forecast.remainingSec!! in 5500L..5700L
        )
    }

    @Test
    fun currentMissing_counterOnlyWorks() {
        // Тока нет: S1 по счётчику (МНК, непрерывный), S3 — fallback.
        val engine = SessionEngine()
        engine.update(snap(0, 50, currentMa = null, counterUah = 2_000_000L), 0)
        val st = engine.update(
            snap(
                30_000, 50, currentMa = null,
                counterUah = 2_000_000L + 30_000L * 40_000L / 60_000L
            ),
            30_000
        )
        assertEquals(
            "ожидали ~1.0 по счётчику",
            1.0f,
            st.forecast.speedPctPerMin!!,
            0.05f
        )
        // Интеграл с уровня 50: ≈90.8 мин = ~5445 с.
        assertTrue(
            "ожидали ~5445, получили ${st.forecast.remainingSec}",
            st.forecast.remainingSec!! in 5350L..5550L
        )
    }

    @Test
    fun fullBattery_showsCharged() {
        val engine = SessionEngine()
        engine.update(snap(0, 99), 0)
        val st = engine.update(snap(1_000, 100, full = true), 1_000)
        assertTrue(st.forecast.isFull)
        assertEquals(0L, st.forecast.remainingSec)
    }

    @Test
    fun unplug_writesSummaryOnce_freezesPrior() {
        val cache = SessionCache(FakeSharedPreferences())
        val engine = SessionEngine(cache)
        var t = 0L
        while (t <= 5 * 60_000L) {
            val level = (50 + t / 60_000L).toInt().coerceAtMost(99)
            engine.update(snap(t, level), t)
            t += 1_000L
        }
        engine.update(
            snap(5 * 60_000L, 55, plugged = false, charging = false), 5 * 60_000L
        )
        val summary = cache.loadSummary()!!
        assertEquals(
            "средняя CC-фаза должна быть ~1.0",
            1.0f,
            summary.avgCcSpeedPctPerMin,
            0.05f
        )
        // Повторное простое сводку не перезаписывает (пишется один раз).
        engine.update(
            snap(6 * 60_000L, 55, plugged = false, charging = false), 6 * 60_000L
        )
        assertEquals(summary, cache.loadSummary())
        // Приор следующей сессии — из сводки, заморожен.
        assertEquals(1.0f, cache.loadLastSpeed()!!, 0.05f)
    }

    @Test
    fun clockContract_engineUsesOnlyPassedTimestamps() {
        // Все расчёты — от переданной монотонной метки (elapsedRealtime
        // в рантайме): подстройка системных часов не влияет на возраст.
        val engine = SessionEngine()
        engine.update(snap(0, 50), 1_000_000L)
        val st = engine.update(snap(1_010_000L, 50), 1_010_000L)
        assertEquals(10_000L, st.sessionAgeMs)
    }

    @Test
    fun frozenCounterAndLevel_staysCollecting() {
        // Симптом автора: счётчик живой, но СТОИТ (EMUI Smart-зарядка
        // ставит зарядку на паузу), уровень замерзает → честное
        // «собираю данные», без цифр из пустоты.
        val engine = SessionEngine()
        var t = 0L
        var last: SessionState? = null
        while (t <= 150_000L) {
            last = engine.update(
                snap(t, 70, currentMa = null, counterUah = 2_114_000L), t
            )
            t += 1_000L
        }
        assertNull(last!!.forecast.remainingSec)
        assertEquals(ForecastAccuracy.COLLECTING, last.forecast.accuracy)
    }

    @Test
    fun stalledSession_doesNotSaveSummaryOrPrior() {
        // Сценарий автора: тихая сессия (счётчик и уровень стоят) —
        // сводка не пишется, приор прошлой сессии не портится нулями.
        val cache = SessionCache(FakeSharedPreferences())
        val engine = SessionEngine(cache)
        var t = 0L
        while (t <= 5 * 60_000L) {
            engine.update(
                snap(t, 70, currentMa = null, counterUah = 2_114_000L), t
            )
            t += 1_000L
        }
        engine.update(
            snap(5 * 60_000L, 70, currentMa = null, counterUah = 2_114_000L, plugged = false, charging = false),
            5 * 60_000L
        )
        assertNull(cache.loadSummary())
        assertNull(cache.loadLastSpeed())
    }

    @Test
    fun refiningSession_unstableSpeedsNotWrittenToPrior() {
        // Счётчик идёт 1%/мин, ток говорит 2.5%/мин — всё время
        // «уточняю»: нестабильная оценка в приор не пишется.
        val cache = SessionCache(FakeSharedPreferences())
        val engine = SessionEngine(cache)
        var t = 0L
        while (t <= 5 * 60_000L) {
            val level = (50 + t / 60_000L).toInt().coerceAtMost(99)
            engine.update(
                snap(
                    t, level, currentMa = 6_000f,
                    counterUah = 2_000_000L + t * 40_000L / 60_000L
                ),
                t
            )
            t += 1_000L
        }
        engine.update(
            snap(
                5 * 60_000L, 55, currentMa = 6_000f,
                counterUah = 2_200_000L, plugged = false, charging = false
            ),
            5 * 60_000L
        )
        assertNull(cache.loadSummary())
        assertNull(cache.loadLastSpeed())
    }

    @Test
    fun curveLearning_deviantPhysics_persistedPerProfile() {        // Физика с отклонением: доводка 0.8 на всех корзинах (не дефолт).
        // Выученные корзины сливаются с прежними 50/50 и персистятся.
        val cache = SessionCache(FakeSharedPreferences())
        assertNull(cache.loadCurve(ChargeType.AC))
        val engine = SessionEngine(cache)
        var remPct = 60.0
        var t = 0L
        while (t <= 55 * 60_000L && remPct > 2.0) {
            val level = (100.0 - remPct).toInt().coerceIn(0, 99)
            val f = if (level >= 80) 0.8f else 1.0f
            engine.update(
                snap(
                    t, level,
                    currentMa = 2_400f * f,
                    counterUah = (capacity * (100.0 - remPct) / 100.0).toLong()
                ),
                t
            )
            remPct -= f * 1_000L / 60_000.0
            t += 1_000L
        }
        engine.update(
            snap(
                t, (100.0 - remPct).toInt().coerceIn(0, 99),
                currentMa = 1_920f,
                counterUah = (capacity * (100.0 - remPct) / 100.0).toLong(),
                plugged = false, charging = false
            ),
            t
        )
        val curve = cache.loadCurve(ChargeType.AC)!!
        // Слияние 50/50: дефолт 0.55/0.40 с выученными 0.8.
        assertEquals(0.5f * 0.55f + 0.5f * 0.8f, curve[0], 0.08f)
        assertEquals(0.5f * 0.40f + 0.5f * 0.8f, curve[1], 0.08f)
        // Другой профиль — изоляция: кривой нет.
        assertNull(cache.loadCurve(ChargeType.USB))
    }

    @Test
    fun rampCurrent_displayedConvergesWithoutUpwardJumps() {
        // консервативной скорости, показанный остаток не прыгает вверх.
        val engine = SessionEngine()
        var prev: Long? = null
        var refiningSeen = false
        var t = 0L
        while (t <= 5 * 60_000L) {
            val level = (50 + t / 60_000L).toInt().coerceAtMost(99)
            val currentMa = if (t < 60_000L) {
                (200_000L + t * 2_200_000L / 60_000L) / 1000f
            } else {
                2_400f
            }
            val st = engine.update(snap(t, level, currentMa = currentMa), t)
            if (st.forecast.accuracy == ForecastAccuracy.REFINING) refiningSeen = true
            val displayed = st.forecast.remainingSec
            val previous = prev
            if (displayed != null && previous != null && t > 90_000L) {
                // Пересмотры вверх допустимы, но ≤25% + минута запаса
                // (границы корзин дают переходные выбросы нормализации).
                assertTrue(
                    "скачок $previous → $displayed на ${t / 1000} сек",
                    displayed <= previous * 1.25f + 60f
                )
            }
            if (displayed != null) prev = displayed
            t += 1_000L
        }
        assertTrue("«уточняю» должен появляться при расхождении S1/S2", refiningSeen)
    }

    @Test
    fun liveSessionReplay_frozenCounterMovingLevel_vetoesGarbageCurrent() {
        // Регрессия живой сессии автора (engine replay): уровень 87→92
        // за 150 сек (≈2 %/мин), счётчик стоит (frozen), ток 7 мА даёт
        // мусор S2 — 0.003. Приора прошлой сессии нет. Движущийся
        // уровень — ground truth: итоговая скорость ≈2 %/мин (а не
        // 0.003), остаток до 100% — минуты, а не сотни часов.
        val engine = SessionEngine()
        var t = 0L
        var last: SessionState? = null
        while (t <= 150_000L) {
            val level = (87 + t / 30_000L).toInt().coerceAtMost(99)
            last = engine.update(
                snap(
                    t, level,
                    currentMa = 7f,
                    // Счётчик заморожен: 87% от 4 млн мкА·ч.
                    counterUah = 3_480_000L
                ),
                t
            )
            t += 1_000L
        }
        val st = last!!
        assertEquals(
            "скорость должна быть ≈2 %/мин, а не мусор 0.003",
            2.0f,
            st.forecast.speedPctPerMin!!,
            0.3f
        )
        assertTrue(
            "остаток должен быть минуты (<15 мин), а не сотни часов; получили ${st.forecast.remainingSec}",
            st.forecast.remainingSec!! < 15L * 60L
        )
    }
}
