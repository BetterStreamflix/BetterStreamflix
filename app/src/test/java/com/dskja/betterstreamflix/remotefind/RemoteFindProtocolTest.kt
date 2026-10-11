package com.dskja.betterstreamflix.remotefind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFindProtocolTest {

    @Test
    fun helloAndKeyboardRoundTrip() {
        assertEquals(
            RemoteFindProtocol.Inbound.Hello("XK9M2P"),
            RemoteFindProtocol.parseInbound(
                RemoteFindProtocol.encodeHello("XK9M2P"),
            ),
        )
        assertEquals(
            RemoteFindProtocol.Inbound.KeySet("matrix"),
            RemoteFindProtocol.parseInbound(RemoteFindProtocol.encodeKeySet("matrix")),
        )
        assertEquals(
            RemoteFindProtocol.Inbound.KeyAppend(" neo"),
            RemoteFindProtocol.parseInbound(RemoteFindProtocol.encodeKeyAppend(" neo")),
        )
        assertEquals(
            RemoteFindProtocol.Inbound.KeyBack,
            RemoteFindProtocol.parseInbound(RemoteFindProtocol.encodeKeyBack()),
        )
        assertEquals(
            RemoteFindProtocol.Inbound.Submit,
            RemoteFindProtocol.parseInbound(RemoteFindProtocol.encodeSubmit()),
        )
    }

    @Test
    fun welcomeCarriesToken() {
        val raw = RemoteFindProtocol.encodeWelcome("XK9M2P", "abcdef12abcdef12")
        val welcome = RemoteFindProtocol.parseWelcome(raw)
        assertNotNull(welcome)
        assertEquals("XK9M2P", welcome!!.code)
        assertEquals("abcdef12abcdef12", welcome.token)
    }

    @Test
    fun padAndNav() {
        val pad = RemoteFindProtocol.parseInbound(RemoteFindProtocol.encodePad(12.5f, -3f))
        assertTrue(pad is RemoteFindProtocol.Inbound.Pad)
        pad as RemoteFindProtocol.Inbound.Pad
        assertEquals(12.5f, pad.dx, 0.01f)
        assertEquals(-3f, pad.dy, 0.01f)
        assertEquals(
            RemoteFindProtocol.Inbound.Tap,
            RemoteFindProtocol.parseInbound(RemoteFindProtocol.encodeTap()),
        )
        assertEquals(
            RemoteFindProtocol.Inbound.Back,
            RemoteFindProtocol.parseInbound(RemoteFindProtocol.encodeBack()),
        )
    }

    @Test
    fun sanitizeStripsControlChars() {
        val text = RemoteFindProtocol.sanitizeText("hi\u0000there\nworld")
        assertEquals("hi there world", text)
    }

    @Test
    fun codedDiscoverMatching_usesFullDiscoverPrefix() {
        assertEquals("", RemoteFindDiscovery.parseDiscoverWanted("remotefind:discover"))
        assertEquals(
            "XK9M2P",
            RemoteFindDiscovery.parseDiscoverWanted("remotefind:discover:XK9M2P"),
        )
        assertEquals(
            "abc234",
            RemoteFindDiscovery.parseDiscoverWanted("REMOTEFIND:DISCOVER:abc234"),
        )
        // Bug regression: substringAfter(':') alone would yield "discover:CODE".
        val buggy = "remotefind:discover:XK9M2P".substringAfter(':').trim()
        assertEquals("discover:XK9M2P", buggy)
        assertNull(RemoteFindCode.normalize(buggy))
        val wanted = RemoteFindDiscovery.parseDiscoverWanted("remotefind:discover:XK9M2P")!!
        assertEquals("XK9M2P", RemoteFindCode.normalize(wanted))
        assertNull(RemoteFindDiscovery.parseDiscoverWanted("noise"))
    }
}
