package com.dskja.betterstreamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniWorldAuthManagerTest {

    @Test
    fun evaluateAccountProbeAcceptsAuthChromeViaSharedProbe() {
        val body = """
            <div id="chat-root" data-auth="1" data-user-id="42"></div>
            <a href="/logout">Abmelden</a>
            <a href="/account">Mein Konto</a>
        """.trimIndent()
        assertEquals(
            SerienStreamAuthManager.AccountProbeVerdict.OK,
            SerienStreamAuthManager.evaluateAccountProbe(
                finalUrl = "https://aniworld.to/account",
                body = body,
                httpSuccessful = true,
            ),
        )
    }

    @Test
    fun parseDisplayNameWorksForAniWorldTitle() {
        val html = """
            <div class="user-name">AnimeFan</div>
            <title>Account | AniWorld</title>
        """.trimIndent()
        assertEquals("AnimeFan", SerienStreamAuthManager.parseDisplayName(html))
    }

    @Test
    fun csrfTokenParsingSharedWithSerienStream() {
        assertEquals(
            "abcDEF12345678",
            SerienStreamAuthManager.parseLoginCsrfToken(
                """<meta name="csrf-token" content="abcDEF12345678">""",
            ),
        )
        assertTrue(SerienStreamAuthManager.needsReauthHint("Just a moment..."))
        assertFalse(SerienStreamAuthManager.needsReauthHint("animeListContainer /anime/stream/foo"))
    }
}
