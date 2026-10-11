package com.dskja.betterstreamflix.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLinkCodesTest {

    @Test
    fun newShortCode_isSixCharsFromSafeAlphabet() {
        val code = DeviceLinkCodes.newShortCode { 0 }
        assertEquals(6, code.length)
        assertTrue(DeviceLinkCodes.isValid(code))
        assertFalse(code.contains('0'))
        assertFalse(code.contains('1'))
    }

    @Test
    fun normalize_stripsDashAndMapsAmbiguous() {
        assertEquals("ABCDEF", DeviceLinkCodes.normalize("abc-def"))
        assertEquals("QJCQDE", DeviceLinkCodes.normalize("01code"))
        assertNull(DeviceLinkCodes.normalize("ABC"))
        assertNull(DeviceLinkCodes.normalize("ABCDEFG"))
    }

    @Test
    fun formatDisplay_insertsDash() {
        assertEquals("ABC-DEF", DeviceLinkCodes.formatDisplay("ABCDEF"))
    }
}
