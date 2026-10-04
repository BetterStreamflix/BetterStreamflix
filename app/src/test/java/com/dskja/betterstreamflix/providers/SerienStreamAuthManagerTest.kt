package com.dskja.betterstreamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SerienStreamAuthManagerTest {

    @Test
    fun authCookieNamesExtractsDistinctNames() {
        val names = SerienStreamAuthManager.authCookieNames(
            "cf_clearance=a; PHPSESSID=b; cf_clearance=c; __ddg1=noise",
        )
        assertTrue(names.any { it.equals("cf_clearance", ignoreCase = true) })
        assertTrue(names.any { it.equals("PHPSESSID", ignoreCase = true) || it.equals("phpsessid", ignoreCase = true) })
        assertFalse(names.any { it.startsWith("__ddg", ignoreCase = true) })
    }

    @Test
    fun parseDisplayNameFromUserChrome() {
        val html = """
            <div class="user-name">MaxMustermann</div>
            <title>Account | SerienStream</title>
        """.trimIndent()
        assertEquals("MaxMustermann", SerienStreamAuthManager.parseDisplayName(html))
    }

    @Test
    fun parseDisplayNameIgnoresSiteTitleOnly() {
        val html = "<title>SerienStream.to</title><body>hello</body>"
        assertNull(SerienStreamAuthManager.parseDisplayName(html))
    }

    @Test
    fun needsReauthHintDetectsChallengeAndLogin() {
        assertTrue(SerienStreamAuthManager.needsReauthHint("Just a moment..."))
        assertTrue(
            SerienStreamAuthManager.needsReauthHint(
                """<form id="login"><input name="email"><input name="password"></form>""",
            ),
        )
        assertFalse(SerienStreamAuthManager.needsReauthHint("seriesListContainer /serie/foo"))
    }

    @Test
    fun evaluateAccountProbe_rejectsWarmHomepage() {
        val body = """
            <div id="chat-root" data-auth="0"></div>
            <nav><a href="/login">Anmelden</a></nav>
            <a href="/serie/foo">Serie</a>
        """.trimIndent()
        assertEquals(
            SerienStreamAuthManager.AccountProbeVerdict.NOT_AUTHENTICATED,
            SerienStreamAuthManager.evaluateAccountProbe(
                finalUrl = "https://serienstream.to/",
                body = body,
                httpSuccessful = true,
            ),
        )
    }

    @Test
    fun evaluateAccountProbe_acceptsAccountWithAuthChrome() {
        val body = """
            <div id="chat-root" data-auth="1" data-user-id="42"></div>
            <a href="/logout">Abmelden</a>
            <a href="/account">Mein Konto</a>
        """.trimIndent()
        assertEquals(
            SerienStreamAuthManager.AccountProbeVerdict.OK,
            SerienStreamAuthManager.evaluateAccountProbe(
                finalUrl = "https://serienstream.to/account",
                body = body,
                httpSuccessful = true,
            ),
        )
    }

    @Test
    fun evaluateAccountProbe_detectsLoginRedirect() {
        val body = """
            <form method="POST" action="/login">
              <input name="email" />
              <input name="password" />
            </form>
        """.trimIndent()
        assertEquals(
            SerienStreamAuthManager.AccountProbeVerdict.LOGIN,
            SerienStreamAuthManager.evaluateAccountProbe(
                finalUrl = "https://serienstream.to/login",
                body = body,
                httpSuccessful = true,
            ),
        )
    }

    @Test
    fun evaluateAccountProbe_detectsChallenge() {
        assertEquals(
            SerienStreamAuthManager.AccountProbeVerdict.CHALLENGE,
            SerienStreamAuthManager.evaluateAccountProbe(
                finalUrl = "https://serienstream.to/account",
                body = "<html>Just a moment...</html>",
                httpSuccessful = true,
            ),
        )
    }

    @Test
    fun evaluateAccountProbe_accountUrlWithPasswordFieldStillOk() {
        // Account settings often include a password-change form.
        val body = """
            <div id="chat-root" data-auth="1" data-user-id="7"></div>
            <form><input name="password" /><input name="password_confirmation" /></form>
        """.trimIndent()
        assertEquals(
            SerienStreamAuthManager.AccountProbeVerdict.OK,
            SerienStreamAuthManager.evaluateAccountProbe(
                finalUrl = "https://serienstream.to/account",
                body = body,
                httpSuccessful = true,
            ),
        )
    }

    @Test
    fun evaluateAccountProbe_rejectsGuestAccountShell() {
        val body = """
            <div id="chat-root" data-auth="0"></div>
            <nav><a href="/login">Anmelden</a></nav>
        """.trimIndent()
        assertEquals(
            SerienStreamAuthManager.AccountProbeVerdict.NOT_AUTHENTICATED,
            SerienStreamAuthManager.evaluateAccountProbe(
                finalUrl = "https://serienstream.to/account",
                body = body,
                httpSuccessful = true,
            ),
        )
    }

    @Test
    fun parseLoginCsrfToken_readsMetaAndInput() {
        val html = """
            <html><head><meta name="csrf-token" content="abcDEF123456"></head>
            <body><input type="hidden" name="_token" value="xyzTOKEN9999"></body></html>
        """.trimIndent()
        assertEquals("xyzTOKEN9999", SerienStreamAuthManager.parseLoginCsrfToken(html))
        assertEquals(
            "metaOnly88",
            SerienStreamAuthManager.parseLoginCsrfToken(
                """<meta name="csrf-token" content="metaOnly88">""",
            ),
        )
        assertNull(SerienStreamAuthManager.parseLoginCsrfToken("<html></html>"))
    }

    @Test
    fun canSkipInteractiveBypass_helpersAlignWithAccountMarkers() {
        // Pure helper coverage — AuthManager.canSkipInteractiveBypass needs prefs/Android.
        assertTrue(
            com.dskja.betterstreamflix.player.SerienStreamBypassHelper.canSkipInteractiveBypass(
                "rememberLogin=1; laravel_session=x",
                accountConfirmed = false,
            ),
        )
        assertFalse(
            com.dskja.betterstreamflix.player.SerienStreamBypassHelper.canSkipInteractiveBypass(
                "laravel_session=x",
                accountConfirmed = false,
            ),
        )
    }
}
