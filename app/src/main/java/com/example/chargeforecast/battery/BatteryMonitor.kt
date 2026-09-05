package com.example.chargeforecast.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Живой источник состояния батареи. Обновления по системным
// broadcast (ACTION_BATTERY_CHANGED) плюс тик опроса: на зарядке —
// раз в секунду (ток и счётчик живут ~1 Гц, телефон у розетки),
// на разряде — раз в 10 секунд (экономим батарею). Все метки —
// elapsedRealtime: подстройка системных часов не скачет расчёты.
class BatteryMonitor(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    private val _snapshots = MutableStateFlow<BatterySnapshot?>(null)
    val snapshots: StateFlow<BatterySnapshot?> = _snapshots.asStateFlow()

    private val batteryManager: BatteryManager? =
        appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            scope.launch { _snapshots.value = readSnapshot(intent) }
        }
    }

    private var started = false
    private var pollJob: Job? = null

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // Sticky intent сразу вернёт текущее состояние синхронно.
        val sticky = ContextCompat.registerReceiver(
            appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (sticky != null) {
            _snapshots.value = readSnapshot(sticky)
        } else {
            readSnapshotNow()?.let { _snapshots.value = it }
        }
        // Тик с замерами: broadcast приходит только при изменении
        // батареи, а ток/счётчик плывут непрерывно (~1 Гц). На зарядке
        // экономить нечего — опрос раз в секунду; на разряде — 10 сек.
        pollJob = scope.launch {
            while (isActive && started) {
                delay(pollDelayMs(_snapshots.value?.isPlugged == true))
                try {
                    val fresh = readSnapshotNow()
                    val prev = _snapshots.value
                    if (fresh != null && (prev == null || !sameReading(prev, fresh))) {
                        _snapshots.value = fresh
                    }
                } catch (_: Exception) {
                    // Пропускаем тик, следующий попробует снова.
                }
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        pollJob?.cancel()
        pollJob = null
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Уже отписан — не ошибка.
        }
    }

    // Синхронное чтение без подписки (для стартовой проверки и тестов логики).
    fun readSnapshotNow(): BatterySnapshot? {
        val sticky = appContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null
        return readSnapshot(sticky)
    }

    private fun readSnapshot(intent: Intent): BatterySnapshot {
        val currentRaw = try {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?: Long.MIN_VALUE.toInt()
        } catch (_: Exception) {
            Long.MIN_VALUE.toInt()
        }
        // getIntProperty отдаёт int; Long.MIN_VALUE как int невозможен,
        // поэтому ошибка драйвера видна как Int.MIN_VALUE. Часть драйверов
        // (Huawei) свойство не отдаёт вовсе — fallback в sysfs.
        val currentUa: Long? = sanitizeCurrentUa(
            if (currentRaw == Int.MIN_VALUE) Long.MIN_VALUE else currentRaw.toLong()
        ) ?: SysfsCurrent.readCurrentNowUa()
        // Счётчик остатка для оценки ёмкости с первого замера.
        // Long.MIN_VALUE — ошибка драйвера, отсекается в toSnapshot.
        val counterUah: Long? = try {
            batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        } catch (_: Exception) {
            null
        }
        val extras = BatteryExtras(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
            status = intent.getIntExtra(
                BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN
            ),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            temperatureTenthsC = intent.getIntExtra(
                BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE
            ).takeIf { it != Int.MIN_VALUE },
            voltageMv = intent.getIntExtra(
                BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE
            ).takeIf { it != Int.MIN_VALUE },
            currentNowUa = currentUa,
            chargeCounterUah = counterUah
        )
        return extras.toSnapshot(clock())
    }

    companion object {
        const val POLL_MS = 10_000L
        const val POLL_ACTIVE_MS = 1_000L

        // Чистая функция для unit-теста интервалов по состоянию.
        fun pollDelayMs(plugged: Boolean): Long =
            if (plugged) POLL_ACTIVE_MS else POLL_MS
    }
}

// Одинаковые показания без учёта метки времени — для тика опроса.
fun sameReading(a: BatterySnapshot, b: BatterySnapshot): Boolean =
    a.levelPct == b.levelPct &&
        a.isPlugged == b.isPlugged &&
        a.isCharging == b.isCharging &&
        a.isFull == b.isFull &&
        a.chargeType == b.chargeType &&
        a.temperatureC == b.temperatureC &&
        a.voltageV == b.voltageV &&
        a.currentMa == b.currentMa &&
        a.chargeCounterUah == b.chargeCounterUah
