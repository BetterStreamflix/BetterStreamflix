package com.dskja.betterstreamflix.logo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoPersistTest {

    @Test
    fun shouldUpdate_whenUrlChanges() {
        assertTrue(
            LogoPersist.shouldUpdate(
                existingLogo = "https://image.tmdb.org/t/p/original/a.png",
                existingLang = "en",
                newLogo = "https://image.tmdb.org/t/p/original/b.png",
                newLang = "en",
            ),
        )
    }

    @Test
    fun shouldUpdate_whenLanguageDiffersEvenIfUrlSame() {
        val url = "https://image.tmdb.org/t/p/original/same.png"
        assertTrue(
            LogoPersist.shouldUpdate(
                existingLogo = url,
                existingLang = "en",
                newLogo = url,
                newLang = "de",
            ),
        )
        assertFalse(
            LogoPersist.shouldUpdate(
                existingLogo = url,
                existingLang = "de",
                newLogo = url,
                newLang = "de-DE",
            ),
        )
    }
}
