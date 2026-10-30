package com.example.chargeforecast.battery

// Доступность датчиков, от которых зависит прогноз. UNKNOWN — данных
// ещё недостаточно, чтобы утверждать, что датчика нет.
enum class SensorAvailability {
    UNKNOWN,
    AVAILABLE,
    MISSING
}

data class BatteryDataQuality(
    val currentSensor: SensorAvailability = SensorAvailability.UNKNOWN,
    val counterSensor: SensorAvailability = SensorAvailability.UNKNOWN
) {
    val currentMissing: Boolean
        get() = currentSensor == SensorAvailability.MISSING

    val counterMissing: Boolean
        get() = counterSensor == SensorAvailability.MISSING

    companion object {
        val UNKNOWN = BatteryDataQuality()
    }
}
