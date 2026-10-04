package com.dskja.betterstreamflix.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerienStreamBypassHelperTest {

    @Test
    fun looksLikeAccountSession_rejectsAnonymousCfJar() {
        val jar = "cf_clearance=abc; PHPSESSID=xyz; __ddg1=noise"
        assertTrue(SerienStreamBypassHelper.looksLikeBypassSolved(jar))
        assertTrue(SerienStreamBypassHelper.looksLikeClearanceSolved(jar))
        assertFalse(SerienStreamBypassHelper.looksLikeAccountSession(jar))
    }

    @Test
    fun looksLikeClearanceSolved_rejectsBareLaravelSession() {
        val jar = "laravel_session=abc; XSRF-TOKEN=tok"
        assertTrue(SerienStreamBypassHelper.looksLikeBypassSolved(jar))
        assertFalse(SerienStreamBypassHelper.looksLikeClearanceSolved(jar))
        // Anonymous session alone must still show captcha.
        assertFalse(SerienStreamBypassHelper.canSkipInteractiveBypass(jar, accountConfirmed = false))
    }

    @Test
    fun canSkipInteractiveBypass_acceptsConfirmedLoginWithoutClearance() {
        val jar = "laravel_session=abc; XSRF-TOKEN=tok"
        assertTrue(
            SerienStreamBypassHelper.canSkipInteractiveBypass(jar, accountConfirmed = true),
        )
    }

    @Test
    fun canSkipInteractiveBypass_acceptsRememberLoginWithoutClearance() {
        val jar = "laravel_session=abc; rememberLogin=1"
        assertTrue(
            SerienStreamBypassHelper.canSkipInteractiveBypass(jar, accountConfirmed = false),
        )
    }

    @Test
    fun canSkipInteractiveBypass_acceptsClearance() {
        assertTrue(
            SerienStreamBypassHelper.canSkipInteractiveBypass(
                "cf_clearance=ok; PHPSESSID=x",
                accountConfirmed = false,
            ),
        )
    }

    @Test
    fun mergeOutgoingCookieHeader_prefersStoredSession() {
        val merged = SerienStreamBypassHelper.mergeOutgoingCookieHeader(
            "PHPSESSID=old",
            "laravel_session=keep; PHPSESSID=new",
        )
        assertTrue(merged.contains("laravel_session=keep"))
        assertTrue(merged.contains("PHPSESSID=new"))
    }

    @Test
    fun looksLikeAccountSession_acceptsRememberLogin() {
        val jar = "PHPSESSID=xyz; rememberLogin=1; cf_clearance=ok"
        assertTrue(SerienStreamBypassHelper.looksLikeAccountSession(jar))
    }

    @Test
    fun sanitize_dropsDuckDuckGoNoise() {
        val cleaned = SerienStreamBypassHelper.sanitizeSessionCookies(
            "__ddg1=a; cf_clearance=token; PHPSESSID=s",
        )
        assertFalse(cleaned.contains("__ddg1"))
        assertTrue(cleaned.contains("cf_clearance"))
    }

    @Test
    fun looksLikeAccountSession_rejectsRememberWebNoiseAlone() {
        // Must still require a real account marker — bare session cookies stay false.
        assertFalse(
            SerienStreamBypassHelper.looksLikeAccountSession("laravel_session=x; XSRF-TOKEN=y"),
        )
    }

    @Test
    fun looksLikeAuthenticatedSession_acceptsLaravelAfterLoginHtml() {
        val jar = "laravel_session=abc123; XSRF-TOKEN=tok"
        val html = """
            <div id="chat-root" data-auth="1" data-user-id="42"></div>
            <nav>
              <a href="/account">Mein Konto</a>
              <a href="/logout">Abmelden</a>
            </nav>
            <footer><a href="/login">Anmelden</a></footer>
        """.trimIndent()
        assertTrue(SerienStreamBypassHelper.hasWebSessionCookie(jar))
        assertTrue(SerienStreamBypassHelper.looksLikeLoggedInHtml(html))
        assertTrue(
            SerienStreamBypassHelper.looksLikeAuthenticatedSession(
                cookieHeader = jar,
                html = html,
                leftLoginAfterVisit = true,
            ),
        )
    }

    @Test
    fun looksLikeAuthenticatedSession_acceptsAccountUrlAfterLogin() {
        val jar = "laravel_session=abc123; XSRF-TOKEN=tok"
        assertTrue(
            SerienStreamBypassHelper.looksLikeAuthenticatedSession(
                cookieHeader = jar,
                html = "",
                leftLoginAfterVisit = true,
                pageUrl = "https://serienstream.to/account",
            ),
        )
    }

    @Test
    fun looksLikeLoggedInHtml_acceptsDataAuthEvenWithFooterLogin() {
        val html = """
            <div id="chat-root" data-auth="1" data-user-id="9"></div>
            <footer><a href="/login">Anmelden</a></footer>
        """.trimIndent()
        assertTrue(SerienStreamBypassHelper.looksLikeLoggedInHtml(html))
    }

    @Test
    fun looksLikeAuthenticatedSession_rejectsWarmHomepage() {
        val jar = "laravel_session=abc123; cf_clearance=x"
        val html = """
            <div id="chat-root" data-auth="0" data-user-id=""></div>
            <nav><a href="/login">Anmelden</a></nav>
            <h1>SerienStream</h1>
        """.trimIndent()
        // Warm homepage must never count as signed in — even with a session cookie.
        assertFalse(
            SerienStreamBypassHelper.looksLikeAuthenticatedSession(
                cookieHeader = jar,
                html = html,
                leftLoginAfterVisit = false,
            ),
        )
        assertFalse(
            SerienStreamBypassHelper.looksLikeAuthenticatedSession(
                cookieHeader = jar,
                html = html,
                leftLoginAfterVisit = true,
            ),
        )
    }

    @Test
    fun looksLikeLoggedInHtml_rejectsLoginForm() {
        val html = """
            <form>
              <input name="email" />
              <input name="password" />
              <button>Einloggen</button>
            </form>
        """.trimIndent()
        assertFalse(SerienStreamBypassHelper.looksLikeLoggedInHtml(html))
    }

    @Test
    fun mergeCookieHeaders_preservesAccountSession() {
        val merged = SerienStreamBypassHelper.mergeCookieHeaders(
            "laravel_session=keep; XSRF-TOKEN=a",
            "cf_clearance=new; XSRF-TOKEN=b",
        )
        assertTrue(merged.contains("laravel_session=keep"))
        assertTrue(merged.contains("cf_clearance=new"))
        assertTrue(merged.contains("XSRF-TOKEN=b") || merged.contains("xsrf-token=b"))
    }
}
