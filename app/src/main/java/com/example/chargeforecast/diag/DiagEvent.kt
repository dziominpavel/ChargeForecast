package com.example.chargeforecast.diag

// Тип события журнала. Токен короткий: строку читают глазами и грепают.
enum class DiagEventKind(val token: String) {
    // Пересчёт оценки ёмкости: было / стало / причина.
    CAPACITY("cap"),
    // Смена яруса точности прогноза.
    ACCURACY("acc"),
    // Смена статуса фонового слежения.
    TRACKING("track"),
    // Смена доступности датчика тока или счётчика.
    SENSOR("sensor"),
    // События сервиса: отказ foreground, остановка.
    SERVICE("svc")
}

// Событие журнала: тип плюс уже собранные поля в формате key=value;...
// Строка события не троттлится тиком — она редкая и важная.
data class DiagEvent(
    val kind: DiagEventKind,
    val fields: String
)