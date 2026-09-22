package com.dskja.betterstreamflix.utils

import org.junit.Assert.assertEquals
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

    @Test
    fun humanizeSlug_stripsYearAndSeparators() {
        assertEquals("the dark knight", ShowLookup.humanizeSlug("the-dark-knight-2008"))
        assertEquals("spider man", ShowLookup.humanizeSlug("/movie/spider_man_2021"))
        assertEquals(null, ShowLookup.humanizeSlug("550"))
        assertEquals(2008, ShowLookup.yearFromSlug("the-dark-knight-2008"))
    }
}
