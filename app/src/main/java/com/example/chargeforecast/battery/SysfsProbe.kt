package com.example.chargeforecast.battery

import java.io.File
import java.util.concurrent.ConcurrentHashMap

// Однократная проба узлов sysfs. На части устройств (Huawei) каталог
// power_supply закрыт SELinux: каждая попытка чтения даёт отказ и строку
// avc: denied в системном логе. Запоминаем отказ по конкретному пути на
// время жизни процесса: повторных обращений и повторного шума нет.
// Успешное чтение ничего не кэширует.
object SysfsProbe {

    private val deniedPaths = ConcurrentHashMap.newKeySet<String>()

    // reader инжектируется ради теста: в бою это обычное чтение файла.
    fun readText(path: String, reader: (File) -> String = { it.readText() }): String? {
        if (deniedPaths.contains(path)) return null
        return try {
            reader(File(path))
        } catch (_: Exception) {
            deniedPaths.add(path)
            null
        }
    }

    fun isDenied(path: String): Boolean = deniedPaths.contains(path)

    fun reset() {
        deniedPaths.clear()
    }
}