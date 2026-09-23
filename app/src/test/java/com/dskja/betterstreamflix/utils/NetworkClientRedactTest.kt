package com.dskja.betterstreamflix.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkClientRedactTest {
    @Test
    fun redactsAuthorizationAndCookieLines() {
        val auth = NetworkClient.redactSensitiveLogLine("Authorization: Bearer super-secret-token")
        assertTrue(auth.contains("❰redacted❱"))
        assertFalse(auth.contains("super-secret-token"))

        val cookie = NetworkClient.redactSensitiveLogLine("Cookie: session=abc; path=/")
        assertTrue(cookie.contains("❰redacted❱"))
        assertFalse(cookie.contains("session=abc"))
    }

    @Test
    fun leavesOrdinaryHeadersAlone() {
        val line = "Content-Type: application/json"
        assertTrue(NetworkClient.redactSensitiveLogLine(line) == line)
    }
}
