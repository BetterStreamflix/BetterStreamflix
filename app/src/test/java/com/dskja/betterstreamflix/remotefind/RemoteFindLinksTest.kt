package com.dskja.betterstreamflix.remotefind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFindLinksTest {

    @Test
    fun deepLinkRoundTrip() {
        val link = RemoteFindLinks.buildDeepLink(
            wsUrl = "ws://192.168.1.20:47822",
            token = "abcdef12abcdef12",
            code = "XK9M2P",
        )
        assertTrue(link.startsWith("betterstreamflix://remotefind?"))
        val target = RemoteFindLinks.parse(link)
        assertNotNull(target)
        assertEquals("ws://192.168.1.20:47822", target!!.wsUrl)
        assertEquals("abcdef12abcdef12", target.token)
        assertEquals("XK9M2P", target.code)
    }

    @Test
    fun httpsMirrorParses() {
        val link = RemoteFindLinks.buildHttpsLink(
            wsUrl = "ws://10.0.0.5:47822",
            token = "tokentokentoken12",
            code = "ABC234",
        )
        val target = RemoteFindLinks.parse(link)
        assertEquals("tokentokentoken12", target?.token)
        assertEquals("ABC234", target?.code)
    }

    @Test
    fun rejectsForeignHosts() {
        assertNull(
            RemoteFindLinks.parse(
                "https://evil.example/remotefind?ws=ws://1.2.3.4:9&token=abcdef12abcdef12",
            ),
        )
        assertNull(RemoteFindLinks.parse("betterstreamflix://cast?token=abcdef12abcdef12"))
    }

    @Test
    fun shortCodeHelpers() {
        assertEquals(6, RemoteFindCode.newShortCode().length)
        assertTrue(RemoteFindCode.isValid(RemoteFindCode.newShortCode()))
        assertEquals("ABCOI2", RemoteFindCode.normalize("abc012"))
        assertNull(RemoteFindCode.normalize("short"))
    }

    @Test
    fun discoveryOfferParse() {
        val offer = RemoteFindDiscovery.parseOffer(
            "remotefind:offer:XK9M2P:abcdef12abcdef12:ws://192.168.0.8:47822",
            fromHost = "192.168.0.8",
        )
        assertNotNull(offer)
        assertEquals("XK9M2P", offer!!.code)
        assertEquals("abcdef12abcdef12", offer.token)
        assertEquals("ws://192.168.0.8:47822", offer.wsUrl)
    }
}
