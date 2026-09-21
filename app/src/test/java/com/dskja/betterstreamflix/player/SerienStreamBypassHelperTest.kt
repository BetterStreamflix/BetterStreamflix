package com.dskja.betterstreamflix.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerienStreamBypassHelperTest {

    @Test
    fun looksLikeAccountSession_rejectsAnonymousCfJar() {
        val jar = "cf_clearance=abc; PHPSESSID=xyz; __ddg1=noise"
        assertTrue(SerienStreamBypassHelper.looksLikeBypassSolved(jar))
        assertFalse(SerienStreamBypassHelper.looksLikeAccountSession(jar))
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
}
