package com.example.chargeforecast.session

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

// Идентичность загрузки устройства. Монотонные метки elapsedRealtime
// сравнимы только внутри одной загрузки, поэтому состояние, пережившее
// ребут, должно нести bootCount (или запасной признак).
data class BootIdentity(
    // Settings.Global.BOOT_COUNT, null если настройка недоступна.
    val bootCount: Int?,
    // Настенные часы — только для диагностики, не для расчёта окна.
    val wallClockMs: Long,
    // Монотонная метка на момент чтения.
    val elapsedRealtimeMs: Long
) {
    companion object {
        fun current(context: Context): BootIdentity {
            val bootCount = runCatching {
                Settings.Global.getInt(
                    context.contentResolver,
                    Settings.Global.BOOT_COUNT
                )
            }.getOrNull()?.takeIf { it >= 0 }
            return BootIdentity(
                bootCount = bootCount,
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = SystemClock.elapsedRealtime()
            )
        }

        // Сравнение сохранённого состояния с текущей загрузкой.
        //
        // Если оба номера загрузки известны — они решают всё. Если хотя бы
        // один неизвестен (OEM без BOOT_COUNT), остаётся консервативная
        // эвристика: после ребута elapsedRealtime начинается заново, поэтому
        // сохранённая метка окажется больше текущей. Совпадение номера
        // загрузки сильнее эвристики; отсутствие метки у сохранённого
        // состояния (старая версия/backup) трактуется как чужая загрузка.
        fun sameBoot(
            savedBootCount: Int?,
            savedElapsedRealtimeMs: Long?,
            currentBootCount: Int?,
            currentElapsedRealtimeMs: Long
        ): Boolean = when {
            savedBootCount == null && currentBootCount != null -> false
            savedBootCount != null && currentBootCount != null ->
                savedBootCount == currentBootCount
            savedElapsedRealtimeMs == null -> false
            else -> savedElapsedRealtimeMs <= currentElapsedRealtimeMs
        }
    }
}
