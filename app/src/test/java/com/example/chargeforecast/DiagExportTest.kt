package com.example.chargeforecast

import com.example.chargeforecast.diag.DiagExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Выгрузка журнала: тип, подпись и признак «есть что отдавать»
// проверяются без Android — сам Intent собирается в Activity.
class DiagExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun noUriMeansNothingToShare() {
        assertNull(DiagExport.buildRequest(null))
        assertNull(DiagExport.buildRequest("  "))
    }

    @Test
    fun requestCarriesTypeAndSubject() {
        val request = DiagExport.buildRequest("content://com.example.chargeforecast.diag/diag/session.log")

        assertNotNull(request)
        assertEquals(DiagExport.MIME_TYPE, request!!.type)
        assertEquals(DiagExport.SUBJECT, request.subject)
        assertTrue(request.grantReadPermission)
        assertTrue(request.uri.startsWith("content://"))
    }

    @Test
    fun emptyOrMissingFileIsNotShareable() {
        assertFalse(DiagExport.isShareable(null))

        val empty = tmp.newFile("empty.log")
        assertFalse(DiagExport.isShareable(empty))

        val filled = tmp.newFile("filled.log")
        filled.writeText("t=1;lvl=81\n")
        assertTrue(DiagExport.isShareable(filled))
    }
}