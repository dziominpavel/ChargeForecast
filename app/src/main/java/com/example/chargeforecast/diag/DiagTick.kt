package com.example.chargeforecast.diag

// Снимок одного тика конвейера: входы датчиков, промежуточные оценки и
// то, что в итоге увидел пользователь. Живёт ради разбора инцидентов
// вроде «Осталось 0 с при 81%»: по строке видно, какая величина его
// породила. Только примитивы — без ссылок на Android и на модели сессии,
// чтобы журнал можно было писать из любого слоя.
data class DiagTick(
    // Монотонная метка тика (elapsedRealtime), как в остальных расчётах.
    val atMs: Long,
    val levelPct: Int,
    val plugged: Boolean,
    val charging: Boolean,
    val full: Boolean,
    val chargeType: String,
    val currentMa: Float?,
    val counterUah: Long?,
    val capacityUah: Long?,
    // Профиль квантования счётчика: шаг, отставание и режим (COARSE/FINE).
    val counterStepUah: Long? = null,
    val counterLagPct: Float? = null,
    val counterMode: String = "COARSE",
    // Три спидометра: S1 в двух окнах, S2 по току, S3 по уровню.
    val s1ShortPctPerMin: Float?,
    val s1LongPctPerMin: Float?,
    val s2PctPerMin: Float?,
    val s3PctPerMin: Float?,
    // Итог fusion и нормализованная к линейной зоне база.
    val fusedPctPerMin: Float?,
    val baseRatePctPerMin: Float?,
    val taperFactor: Float,
    // Сырой остаток до фильтра показа и то, что показано.
    val rawRemainingSec: Long?,
    val shownRemainingSec: Long?,
    // Показываемая (эффективная) скорость: остаток ÷ показанное время.
    // По ней сверяют «скорость и остаток согласованы» на устройстве.
    val shownSpeedPctPerMin: Float? = null,
    val accuracy: String,
    val refining: Boolean,
    // Фаза зарядки: CHARGING, FULL или STALLED (кабель есть, зарядки нет).
    val phase: String = "CHARGING"
)