package com.dskja.betterstreamflix.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowLookupTest {

    @Test
    fun titlesMatch_ignoresPunctuationAndCase() {
        assertTrue(ShowLookup.titlesMatch("The Office (US)", "the office us"))
        assertTrue(ShowLookup.titlesMatch("Spider-Man: No Way Home", "spiderman no way home"))
        assertFalse(ShowLookup.titlesMatch("Breaking Bad", "Better Call Saul"))
        assertFalse(ShowLookup.titlesMatch("", "Anything"))
    }
}
