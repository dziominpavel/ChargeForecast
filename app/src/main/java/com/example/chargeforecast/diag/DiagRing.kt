package com.example.chargeforecast.diag

// Кольцевой буфер последних строк журнала: даёт «что было прямо перед
// сбоем» без чтения файла и без роста памяти. Вытесняется самое старое.
class DiagRing(private val capacity: Int = CAPACITY) {

    private val items = ArrayDeque<String>(capacity)

    @Synchronized
    fun add(line: String) {
        if (capacity <= 0) return
        if (items.size >= capacity) items.removeFirst()
        items.addLast(line)
    }

    @Synchronized
    fun lines(): List<String> = items.toList()

    @Synchronized
    fun size(): Int = items.size

    @Synchronized
    fun clear() = items.clear()

    companion object {
        const val CAPACITY = 200
    }
}