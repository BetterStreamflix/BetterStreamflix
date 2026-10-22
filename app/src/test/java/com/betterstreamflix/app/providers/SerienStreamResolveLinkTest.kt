package com.betterstreamflix.app.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SerienStreamResolveLinkTest {

    @Test
    fun parsesBetterstreamflixDeepLink() {
        val target = SerienStreamResolveLink.parse(
            "betterstreamflix://resolve?ws=ws%3A%2F%2F192.168.1.10%3A8765&token=abc-123",
        )
        assertNotNull(target)
        assertEquals("ws://192.168.1.10:8765", target!!.ws)
        assertEquals("abc-123", target.token)
    }

    @Test
    fun parsesStreamflixDeepLink() {
        val target = SerienStreamResolveLink.parse(
            "streamflix://resolve?ws=ws://10.0.0.2:9000&token=tok",
        )
        assertNotNull(target)
        assertEquals("ws://10.0.0.2:9000", target!!.ws)
        assertEquals("tok", target.token)
    }

    @Test
    fun parsesHttpLandingQr() {
        val target = SerienStreamResolveLink.parse(
            "http://192.168.0.20:8080/resolve?token=tv-token&ws=ws://192.168.0.20:8765",
        )
        assertNotNull(target)
        assertEquals("ws://192.168.0.20:8765", target!!.ws)
        assertEquals("tv-token", target.token)
        val deepLink = target.toDeepLink().toString()
        assertTrue(deepLink.startsWith("betterstreamflix://resolve"))
        assertTrue(deepLink.contains("token=tv-token") || deepLink.contains("token=tv%2Dtoken") || deepLink.contains("tv-token"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(SerienStreamResolveLink.parse(""))
        assertNull(SerienStreamResolveLink.parse("https://google.com/search?q=resolve"))
        assertNull(SerienStreamResolveLink.parse("betterstreamflix://trakt/oauth"))
        assertFalse(SerienStreamResolveLink.isResolveLink("not-a-link"))
    }

    @Test
    fun rejectsPublicAttackerWebSocket() {
        assertNull(
            SerienStreamResolveLink.parse(
                "betterstreamflix://resolve?ws=ws://evil.example.com:9999&token=abc",
            ),
        )
        assertFalse(
            SerienStreamResolveLink.isAllowedResolverWs("ws://evil.example.com:9999"),
        )
    }

    @Test
    fun allowsOverrideHost() {
        val target = SerienStreamResolveLink.parse(
            "betterstreamflix://resolve?ws=ws://emulator.host:8765&token=abc",
            advertisedHostOverride = "emulator.host",
        )
        assertNotNull(target)
        assertEquals("ws://emulator.host:8765", target!!.ws)
    }

    @Test
    fun rejectsPublicHttpLandingHost() {
        assertNull(
            SerienStreamResolveLink.parse(
                "https://evil.example/resolve?ws=ws://192.168.1.1:8765&token=abc",
            ),
        )
    }
}
