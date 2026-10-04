package com.dskja.betterstreamflix.extractors

import org.junit.Assert.assertEquals
import org.junit.Test

class HosterPlaybackLinkTest {

    @Test
    fun aniWorldVoeLanguageSuffixStillUsesCanonicalHost() {
        assertEquals(
            "https://voe.sx/e/abc",
            canonicalHosterUrl("VOE - DUB", "https://random.example/e/abc", "/e/abc"),
        )
        assertEquals(
            "https://voe.sx/e/abc",
            canonicalHosterUrl("VOE - SUB English", "https://random.example/e/abc", "/e/abc"),
        )
        assertEquals(
            "https://voe.sx/e/abc",
            canonicalHosterUrl("voe", "https://random.example/e/abc", "/e/abc"),
        )
    }

    @Test
    fun otherHostersKeepTheResolvedUrl() {
        assertEquals(
            "https://filemoon.sx/e/abc",
            canonicalHosterUrl("Filemoon - SUB", "https://filemoon.sx/e/abc", "/e/abc"),
        )
    }
}
