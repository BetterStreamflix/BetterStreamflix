package com.dskja.betterstreamflix.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionShortCodesTest {

    @Test
    fun normalizeStripsDashesAndMapsAmbiguous() {
        assertEquals("ABCQJJ", CompanionShortCodes.normalize("ABC-0I1"))
        assertEquals("ABCDEF", CompanionShortCodes.normalize("abc def"))
        assertNull(CompanionShortCodes.normalize("ABC"))
        assertEquals("QQQQQQ", CompanionShortCodes.normalize("OOOOOO"))
    }

    @Test
    fun normalizeMapsOAndIIntoAlphabet() {
        // O/0 → Q, I/1/L → J — always land in generator alphabet.
        assertEquals("QQJJJJ", CompanionShortCodes.normalize("OO1111"))
        assertTrue(CompanionShortCodes.isValid("QQJJJJ"))
    }

    @Test
    fun formatDisplayAddsDash() {
        assertEquals("ABC-DEF", CompanionShortCodes.formatDisplay("ABCDEF"))
    }

    @Test
    fun mintUniqueRetriesOnNull() {
        var attempts = 0
        val result = CompanionShortCodes.mintUnique(maxAttempts = 3) { code ->
            attempts++
            if (attempts < 3) null else "ok:$code"
        }
        assertTrue(result.startsWith("ok:"))
        assertEquals(3, attempts)
    }
}
