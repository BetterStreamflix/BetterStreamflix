package com.dskja.betterstreamflix.sync

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
    fun normalize_mapsAmbiguousDigitsAndLowercase() {
        assertEquals("ABCODE", SessionHandoffCodes.normalize("abcode"))
        assertEquals("OICODE", SessionHandoffCodes.normalize("01code"))
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
