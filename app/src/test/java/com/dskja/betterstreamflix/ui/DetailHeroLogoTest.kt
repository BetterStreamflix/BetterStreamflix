package com.dskja.betterstreamflix.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailHeroLogoTest {

    @Test
    fun tagMatchesLogo_sameFileAcrossSizes() {
        val original = "https://image.tmdb.org/t/p/original/logoABC.png"
        val w500 = "https://image.tmdb.org/t/p/w500/logoABC.png"
        assertTrue(DetailHeroLogo.tagMatchesLogo(w500, original))
        assertTrue(DetailHeroLogo.tagMatchesLogo(original, original))
    }

    @Test
    fun tagMatchesLogo_rejectsAnotherTitle() {
        val current = "https://image.tmdb.org/t/p/original/showLogo.png"
        val other = "https://image.tmdb.org/t/p/w500/movieLogo.png"
        assertFalse(DetailHeroLogo.tagMatchesLogo(other, current))
        assertFalse(DetailHeroLogo.tagMatchesLogo(null, current))
        assertFalse(DetailHeroLogo.tagMatchesLogo(other, null))
    }
}
