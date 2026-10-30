package com.example.chargeforecast.battery

// Чистый трекер доступности датчиков: одиночный пропуск драйвера не
// должен превращаться в «датчика нет». MISSING ставится только после
// threshold пустых чтений подряд; первое валидное значение сразу
// возвращает AVAILABLE.
class BatterySensorTracker(private val threshold: Int = DEFAULT_THRESHOLD) {

    private var currentStreak = 0
    private var counterStreak = 0
    private var quality = BatteryDataQuality.UNKNOWN

    val dataQuality: BatteryDataQuality
        get() = quality

    fun onReading(currentUa: Long?, counterUah: Long?): BatteryDataQuality {
        val current = advance(quality.currentSensor, currentUa != null, currentStreak)
        currentStreak = current.streak
        val counter = advance(quality.counterSensor, counterUah != null, counterStreak)
        counterStreak = counter.streak
        quality = BatteryDataQuality(
            currentSensor = current.availability,
            counterSensor = counter.availability
        )
        return quality
    }

    private fun advance(
        previous: SensorAvailability,
        available: Boolean,
        streak: Int
    ): Advance {
        if (available) return Advance(SensorAvailability.AVAILABLE, 0)
        val nextStreak = streak + 1
        val availability = if (nextStreak >= threshold) {
            SensorAvailability.MISSING
        } else {
            previous
        }
        return Advance(availability, nextStreak)
    }

    private data class Advance(val availability: SensorAvailability, val streak: Int)

    companion object {
        const val DEFAULT_THRESHOLD = 3
    }
}
