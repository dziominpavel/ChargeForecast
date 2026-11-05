package com.example.chargeforecast.diag

// Данные для выгрузки журнала, собранные без Android: URI строит
// вызывающая сторона (FileProvider), а тип и подпись — здесь, чтобы
// их можно было проверить обычным JVM-тестом.
object DiagExport {

    data class ShareRequest(
        val uri: String,
        val type: String,
        val subject: String,
        val grantReadPermission: Boolean
    )

    const val MIME_TYPE = "text/plain"
    const val SUBJECT = "Диагностика ChargeForecast"

    // null — выгружать нечего: экран покажет понятный текст.
    fun buildRequest(uri: String?): ShareRequest? {
        if (uri.isNullOrBlank()) return null
        return ShareRequest(
            uri = uri,
            type = MIME_TYPE,
            subject = SUBJECT,
            grantReadPermission = true
        )
    }

    // Журнал считается пригодным к выгрузке, если он есть и не пуст.
    fun isShareable(file: java.io.File?): Boolean =
        file != null && file.exists() && file.length() > 0L
}