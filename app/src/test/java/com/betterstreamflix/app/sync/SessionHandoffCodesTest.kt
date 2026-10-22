package com.betterstreamflix.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHandoffCodesTest {

    @Test
    fun newShortCode_isSixCharsFromSafeAlphabet() {
        val code = SessionHandoffCodes.newShortCode { 0 }
        assertEquals(6, code.length)
        assertTrue(SessionHandoffCodes.isValid(code))
        assertFalse(code.contains('0'))
        assertFalse(code.contains('1'))
        assertFalse(code.contains('O'))
        assertFalse(code.contains('I'))
        assertFalse(code.contains('L'))
    }

    @Test
    fun normalize_mapsAmbiguousAndStripsDash() {
        assertEquals("ABCMDE", SessionHandoffCodes.normalize("abcmde"))
        // 0/O→Q, 1/I/L→J (generator alphabet) — avoid literal O/I/L in expected codes
        assertEquals("QJCMDE", SessionHandoffCodes.normalize("01cmde"))
        assertEquals("ABCDEF", SessionHandoffCodes.normalize("ABC-DEF"))
        assertEquals("ABCDEF", SessionHandoffCodes.normalize("ABC DEF"))
        assertNull(SessionHandoffCodes.normalize("ABC"))
        assertNull(SessionHandoffCodes.normalize("ABCDEFG"))
    }

    @Test
    fun formatDisplay_insertsDash() {
        assertEquals("ABC-DEF", SessionHandoffCodes.formatDisplay("ABCDEF"))
    }

    @Test
    fun links_parseCodeFromQuery() {
        val uri = SessionHandoffLinks.buildCodeUri("ABCDEF")
        assertNotNull(SessionHandoffLinks.parseCode(uri))
        assertEquals("ABCDEF", SessionHandoffLinks.parseCode(uri))
    }
}
