package com.dskja.betterstreamflix.extractors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoeRedirectTest {

    @Test
    fun prefersLocationHopOverCdnStylesheet() {
        val html = """
            <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font.css">
            <script>window.location.href = "https://walterprettytheir.com/e/abc123";</script>
        """.trimIndent()

        assertEquals(
            "https://walterprettytheir.com/",
            VoeRedirect.baseUrl(html, "voe.sx"),
        )
    }

    @Test
    fun skipsCdnWhenNoExplicitRedirect() {
        val html = """
            <script src="https://cdn.jsdelivr.net/npm/player.js"></script>
            <a href="https://jessicayeahcatch.com/e/xyz">play</a>
        """.trimIndent()

        assertEquals(
            "https://jessicayeahcatch.com/",
            VoeRedirect.baseUrl(html, "voe.sx"),
        )
    }

    @Test
    fun ignoresSameHostAndKnownCdns() {
        val html = """
            <script src="https://voe.sx/player.js"></script>
            <link href="https://fonts.googleapis.com/css?family=Roboto">
        """.trimIndent()

        assertNull(VoeRedirect.baseUrl(html, "voe.sx"))
    }
}
